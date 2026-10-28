package com.geek.association.cli;

import com.geek.association.config.AppProperties;
import com.geek.association.diff.ChangeDetector;
import com.geek.association.diff.FileDiffCollector;
import com.geek.association.impact.PeerImpactEngine;
import com.geek.association.model.ChangeRecord;
import com.geek.association.model.CodeIndex;
import com.geek.association.model.FileChange;
import com.geek.association.model.PeerImpact;
import com.geek.association.model.RepoSource;
import com.geek.association.source.SourceNames;
import com.geek.association.parse.CodeIndexer;
import com.geek.association.report.AiExplainer;
import com.geek.association.report.ImpactReport;
import com.geek.association.report.MarkdownReport;
import com.geek.association.report.ReportBuilder;
import com.geek.association.source.GitSupport;
import com.geek.association.source.RepositoryPreparer;
import com.geek.association.source.RevisionPair;
import com.geek.association.util.Json;
import com.geek.association.util.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Headless entry point for CI pipelines:
 *
 * <pre>
 * java -jar code-association.jar analyze --location /repo --base main --target HEAD \
 *      --out report.json --fail-on HIGH
 * </pre>
 *
 * The exit code is what a pipeline gates on, so the same engine that powers the UI can block a
 * merge when a high risk impact on another program is found.
 */
@Component
public class CliRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);

    private final AppProperties properties;
    private final RepositoryPreparer preparer;
    private final AiExplainer aiExplainer;
    private int exitCode = 0;

    public CliRunner(AppProperties properties, RepositoryPreparer preparer, AiExplainer aiExplainer) {
        this.properties = properties;
        this.preparer = preparer;
        this.aiExplainer = aiExplainer;
    }

    private List<String> rawArgs = List.of();

    @Override
    public void run(ApplicationArguments args) throws Exception {
        rawArgs = args.getSourceArgs() == null ? List.of() : List.of(args.getSourceArgs());
        long started = System.currentTimeMillis();
        List<String> nonOption = args.getNonOptionArgs();
        if (nonOption.isEmpty() || !"analyze".equals(nonOption.get(0))) {
            return;
        }
        String location = first(args, "location", "path", "repo");
        if (location == null) {
            System.err.println("missing --location <path or git url>");
            exitCode = 2;
            logFailure(started, "missing --location");
            return;
        }
        RepoSource source = new RepoSource();
        source.name = first(args, "name");
        source.location = location;
        source.type = first(args, "type") == null ? guessType(location) : first(args, "type").toUpperCase();
        // --name stays optional; the derived name is what the report prints otherwise.
        SourceNames.withDerivedName(source);
        source.defaultRef = first(args, "target");
        source.credentialEnv = first(args, "token-env");

        RepositoryPreparer.Request request = new RepositoryPreparer.Request();
        request.source = source;
        request.mode = first(args, "mode") == null ? "GIT_DIFF" : first(args, "mode").toUpperCase();
        request.baseRef = first(args, "base");
        request.targetRef = first(args, "target");
        String pr = first(args, "pr");
        if (pr != null) {
            request.pullRequest = Integer.parseInt(pr);
            request.mode = "PULL_REQUEST";
        }
        // --range a..b is the same thing --base/--target spell in two options. It exists because
        // pasting a range from a review tool or a CI variable ("$BASE..$HEAD") is the common case,
        // and splitting it by hand is where the two ends get swapped.
        String range = first(args, "range");
        if (range != null) {
            int sep = range.indexOf("..");
            if (sep < 0) {
                System.err.println("--range needs the form <base>..<target>, e.g. origin/main..HEAD");
                exitCode = 2;
                logFailure(started, "malformed --range, expected <base>..<target>");
                return;
            }
            request.mode = "COMMIT_RANGE";
            request.baseRef = range.substring(0, sep).strip();
            request.targetRef = range.substring(sep + 2).strip();
            if (request.baseRef.isEmpty() || request.targetRef.isEmpty()) {
                System.err.println("--range needs both ends, e.g. origin/main..HEAD");
                exitCode = 2;
                logFailure(started, "--range is missing one end");
                return;
            }
        }

        RevisionPair revisions;
        try {
            revisions = preparer.prepare(request);
        } catch (java.io.IOException e) {
            // A pipeline gates on the exit code, so a range that cannot be resolved has to end as
            // "2 + one sentence", not as a stack trace that the job log buries the reason in. The
            // messages are written for a person: "起始提交 x 不在 y 的历史里" says what to fix.
            System.err.println("cannot prepare the revision range: " + e.getMessage());
            exitCode = 2;
            logFailure(started, "cannot prepare revisions: " + e.getMessage());
            return;
        }
        FileDiffCollector collector = new FileDiffCollector(revisions.base, revisions.head);
        List<FileChange> files = collector.collect();
        Map<String, String> diffs = collector.unifiedDiffs(files, 50, 160);
        CodeIndex headIndex = new CodeIndexer(properties.getMaxFiles())
                .index(revisions.head, revisions.head.label(), revisions.headCommit);
        CodeIndex baseIndex = new CodeIndexer(properties.getMaxFiles())
                .index(revisions.base, revisions.base.label(), revisions.baseCommit);
        List<ChangeRecord> changes = new ChangeDetector(baseIndex, headIndex).detect(files);
        long seq = 1;
        for (ChangeRecord c : changes) {
            c.id = seq++;
        }
        List<String> peerLocations = all(args, "peer");
        if (peerLocations.isEmpty()) {
            // The product scans other programs only. Without a peer there is nothing to report, and
            // silently emitting an empty report would make --fail-on pass on any change.
            System.err.println("missing --peer <path-or-url>: only impacts on other programs are scanned");
            System.err.println("  example: --peer /path/to/order-service --peer https://github.com/org/billing.git");
            exitCode = 2;
            logFailure(started, "missing --peer");
            return;
        }
        List<PeerImpact> peers = peerImpacts(changes, headIndex, peerLocations);
        ImpactReport report = new ReportBuilder().build(revisions, headIndex, baseIndex, changes,
                List.of(), diffs, peers);
        report.peers = peers;
        report.peersNote = peers.isEmpty()
                ? "选中的其他程序都无法读取，本次没有可比对的结果"
                : null;
        // --ai is opt-in rather than following defaultForRuns: a pipeline's output has to be
        // reproducible, and silently adding a network call (and its cost) to every job would make
        // the same commit produce different reports on different days.
        if (args.containsOption("ai")) {
            if (aiExplainer.ready()) {
                report.ai = aiExplainer.explain(report, properties.getAi().getMaxImpacts());
            } else {
                // One line, and the exit code is untouched: an unconfigured or unreachable model
                // must never fail a build gate that is about the static analysis.
                System.out.println("AI 评审说明已跳过：AI 审核未配置完成（"
                        + aiExplainer.notReadyReason() + "）");
            }
        }

        String out = first(args, "out");
        String format = first(args, "format") == null ? "json" : first(args, "format").toLowerCase();
        String payload = "markdown".equals(format) || "md".equals(format)
                ? MarkdownReport.render(report, 20)
                : Json.write(report);
        if (out != null) {
            Files.writeString(Path.of(out), payload);
            System.out.println("report written to " + Path.of(out).toAbsolutePath() + " (" + format + ")");
        }
        printSummary(report);
        String failOn = first(args, "fail-on");
        if (failOn != null) {
            exitCode = shouldFail(report, failOn) ? 1 : 0;
            if (exitCode == 1) {
                System.out.println("FAILED: impact risk reached " + failOn.toUpperCase());
            }
        }
        // 一行收尾：CI 日志里"这次 CLI 跑了什么、结果如何、花了多久"只在这一行里，
        // 退出码与耗时都带上，流水线失败时不必再去翻整段 stdout。
        log.info(LogEvents.of("cli.done",
                "exitCode", exitCode,
                "mode", report.mode,
                "peers", report.peers.size(),
                "high", report.summary.highRisk,
                "medium", report.summary.mediumRisk,
                "durationMs", LogEvents.msSince(started)));
        if (out == null) {
            System.out.println(payload);
        }
    }

    /**
     * 失败分支的统一收尾：一行 WARN 说清退出码与缺什么参数。
     *
     * 这些分支各自 {@code return}，没有 report 可汇总，所以只带 reason；用 WARN 而不是 INFO，
     * 是因为它们都意味着"这次调用没有产出报告"。
     */
    private void logFailure(long started, String reason) {
        log.warn(LogEvents.of("cli.done",
                "exitCode", exitCode,
                "reason", reason,
                "durationMs", LogEvents.msSince(started)));
    }

    private boolean shouldFail(ImpactReport report, String failOn) {
        int threshold = switch (failOn.toUpperCase()) {
            case "CRITICAL" -> 80;
            case "HIGH" -> 60;
            case "MEDIUM" -> 40;
            case "LOW" -> 20;
            default -> 101;
        };
        return report.impacts.stream().anyMatch(i -> i.riskScore >= threshold);
    }

    private void printSummary(ImpactReport report) {
        System.out.println("mode            : " + report.mode
                + (report.pullRequest == null ? "" : " #" + report.pullRequest));
        if (report.commitCount > 0) {
            System.out.println("commits         : " + report.commitCount
                    + (report.rangeNote == null || report.rangeNote.isBlank() ? "" : " (" + report.rangeNote + ")"));
            for (com.geek.association.model.CommitInfo commit : report.commits) {
                System.out.printf("  %-10s %s%n", commit.shortSha, commit.subject);
            }
        }
        System.out.println("changed modules : " + report.summary.changedModules);
        System.out.println("changed symbols : " + report.summary.changedSymbols);
        System.out.println("impacted modules: " + report.summary.impactedModules);
        System.out.println("impacted symbols: " + report.summary.impactedSymbols);
        System.out.println("high / medium   : " + report.summary.highRisk + " / " + report.summary.mediumRisk);
        for (ImpactReport.ModuleImpact module : report.moduleImpacts) {
            System.out.printf("  %-14s %-8s risk=%d symbols=%d%n",
                    module.module, module.riskLevel, module.maxRisk, module.impactedSymbols);
        }
        if (report.peers.isEmpty()) {
            System.out.println("peer programs   : none (self impact is out of scope)");
        } else {
            for (PeerImpact peer : report.peers) {
                System.out.printf("  peer %-18s contracts=%d impacts=%d risk=%d%n",
                        peer.peerSourceName, peer.matches.size(), peer.impactedSymbols, peer.maxRisk);
            }
        }
    }

    /**
     * Indexes each peer and keeps only what this change does to that other program.
     *
     * Peers are accepted as paths or repository URLs. They are indexed as a current snapshot, not
     * diffed, because only the peer's current consumers matter for a cross-program impact.
     */
    private List<PeerImpact> peerImpacts(List<ChangeRecord> changes, CodeIndex ownHead, List<String> locations) {
        List<PeerImpact> out = new ArrayList<>();
        for (String location : locations) {
            RepoSource source = new RepoSource();
            source.location = location;
            source.type = guessType(location);
            SourceNames.withDerivedName(source);
            PeerImpact info = new PeerImpact();
            info.peerSourceName = source.name;
            info.peerType = source.type;
            info.peerLocation = source.location;
            try {
                RepositoryPreparer.Request peerRequest = new RepositoryPreparer.Request();
                peerRequest.source = source;
                peerRequest.mode = "SNAPSHOT";
                RevisionPair peerRevisions = preparer.prepare(peerRequest);
                CodeIndex peerIndex = new CodeIndexer(properties.getMaxFiles())
                        .index(peerRevisions.head, peerRevisions.head.label(), peerRevisions.headCommit);
                long firstImpactId = out.stream().mapToLong(p -> p.impacts.size()).sum() + 1;
                out.add(new PeerImpactEngine(properties).analyze(changes, ownHead, peerIndex, info, firstImpactId));
            } catch (Exception e) {
                info.note = "无法读取该程序：" + e.getMessage();
                // CI 里 --peer 写错路径，报告里只会留下一句"无法读取"的 note，流水线红了也无从排查：
                // 日志必须留下原因链。退出码仍由报告内容决定，这里不改任何业务行为。
                log.warn(LogEvents.of("cli.peer",
                        "peer", info.peerSourceName,
                        "failed", true,
                        "error", GitSupport.describe(e)));
                out.add(info);
            }
        }
        return out;
    }

    private String guessType(String location) {
        if (location.startsWith("http") || location.startsWith("git@")) {
            String lower = location.toLowerCase();
            if (lower.contains("github")) {
                return "GITHUB";
            }
            if (lower.contains("gitlab")) {
                return "GITLAB";
            }
            return "GIT";
        }
        return "LOCAL_DIR";
    }

    /**
     * Reads an option in either supported form: {@code --name=value} (Spring style) or
     * {@code --name value} (the form used in the documented CLI examples).
     */
    private String first(ApplicationArguments args, String... names) {
        for (String name : names) {
            if (args.containsOption(name)) {
                List<String> values = args.getOptionValues(name);
                if (values != null && !values.isEmpty() && values.get(values.size() - 1) != null) {
                    return values.get(values.size() - 1);
                }
            }
        }
        for (String name : names) {
            for (int i = 0; i < rawArgs.size() - 1; i++) {
                if (("--" + name).equals(rawArgs.get(i))) {
                    String value = rawArgs.get(i + 1);
                    if (!value.startsWith("--")) {
                        return value;
                    }
                }
                if (("--" + name + "=").equals(rawArgs.get(i).substring(0, Math.min(rawArgs.get(i).length(),
                        ("--" + name + "=").length())))) {
                    return rawArgs.get(i).substring(("--" + name + "=").length());
                }
            }
        }
        return null;
    }

    /** Reads every occurrence of a repeatable option, in command-line order. */
    private List<String> all(ApplicationArguments args, String name) {
        List<String> out = new ArrayList<>();
        if (args.containsOption(name)) {
            List<String> values = args.getOptionValues(name);
            if (values != null) {
                for (String value : values) {
                    if (value != null && !value.isBlank()) {
                        out.add(value);
                    }
                }
            }
        }
        for (int i = 0; i < rawArgs.size(); i++) {
            String arg = rawArgs.get(i);
            if (("--" + name).equals(arg) && i + 1 < rawArgs.size() && !rawArgs.get(i + 1).startsWith("--")) {
                out.add(rawArgs.get(i + 1));
            } else if (arg.startsWith("--" + name + "=")) {
                String value = arg.substring(("--" + name + "=").length());
                if (!value.isBlank()) {
                    out.add(value);
                }
            }
        }
        return out.stream().distinct().toList();
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
