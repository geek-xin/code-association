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
import com.geek.association.report.ImpactReport;
import com.geek.association.report.MarkdownReport;
import com.geek.association.report.ReportBuilder;
import com.geek.association.source.RepositoryPreparer;
import com.geek.association.source.RevisionPair;
import com.geek.association.util.Json;
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

    private final AppProperties properties;
    private final RepositoryPreparer preparer;
    private int exitCode = 0;

    public CliRunner(AppProperties properties, RepositoryPreparer preparer) {
        this.properties = properties;
        this.preparer = preparer;
    }

    private List<String> rawArgs = List.of();

    @Override
    public void run(ApplicationArguments args) throws Exception {
        rawArgs = args.getSourceArgs() == null ? List.of() : List.of(args.getSourceArgs());
        List<String> nonOption = args.getNonOptionArgs();
        if (nonOption.isEmpty() || !"analyze".equals(nonOption.get(0))) {
            return;
        }
        String location = first(args, "location", "path", "repo");
        if (location == null) {
            System.err.println("missing --location <path or git url>");
            exitCode = 2;
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

        RevisionPair revisions = preparer.prepare(request);
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
        List<PeerImpact> peers = peerImpacts(changes, headIndex, all(args, "peer"));
        ImpactReport report = new ReportBuilder().build(revisions, headIndex, baseIndex, changes,
                List.of(), diffs, peers);
        report.peers = peers;
        report.peersNote = peers.isEmpty()
                ? "未选择 --peer，本次没有扫描任何其他程序；自身影响不在扫描范围内"
                : null;

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
        if (out == null) {
            System.out.println(payload);
        }
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
