package com.geek.association.service;

import com.geek.association.config.AppProperties;
import com.geek.association.diff.ChangeDetector;
import com.geek.association.diff.FileDiffCollector;
import com.geek.association.impact.PeerImpactEngine;
import com.geek.association.model.PeerImpact;
import com.geek.association.model.Analysis;
import com.geek.association.model.ChangeRecord;
import com.geek.association.model.CodeIndex;
import com.geek.association.model.FileChange;
import com.geek.association.model.RepoSource;
import com.geek.association.parse.CodeIndexer;
import com.geek.association.report.AiExplainer;
import com.geek.association.report.ImpactReport;
import com.geek.association.report.ReportBuilder;
import com.geek.association.source.GitSupport;
import com.geek.association.source.RepositoryPreparer;
import com.geek.association.source.RevisionPair;
import com.geek.association.store.Store;
import com.geek.association.util.Json;
import com.geek.association.util.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates one analysis run: snapshot -> diff -> index -> change detection -> impact
 * propagation -> report. Runs asynchronously so the HTTP call returns immediately with an id.
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final Store store;
    private final AppProperties properties;
    private final RepositoryPreparer preparer;
    private final ReportBuilder reportBuilder = new ReportBuilder();
    private final AiExplainer aiExplainer;
    private final ExecutorService executor;
    private final AtomicInteger running = new AtomicInteger();

    public AnalysisService(Store store, AppProperties properties, RepositoryPreparer preparer,
                           AiExplainer aiExplainer) {
        this.store = store;
        this.properties = properties;
        this.preparer = preparer;
        this.aiExplainer = aiExplainer;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "impact-analysis-" + running.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public static final class RunRequest {
        public long sourceId;
        /** GIT_DIFF | WORKTREE_DIFF | COMMIT_RANGE | PULL_REQUEST */
        public String mode;
        public String baseRef;
        public String targetRef;
        public Integer pullRequest;
        /**
         * Hand-picked commits, when the reviewer selected them instead of naming a range.
         *
         * Null-tolerant on purpose: the field is absent from every request the UI made before
         * this existed, and an absent list must mean "use baseRef / targetRef", not "empty
         * selection".
         */
        public List<String> commits;
        public boolean includeTests = false;
        public boolean includeUncommitted = true;
        /** Other programs to scan for cross-program impact, by source id. */
        public List<Long> peerSourceIds = new ArrayList<>();
        /**
         * Whether this run should also generate the AI review.
         *
         * A boxed Boolean on purpose: {@code null} means "the client did not decide", which is
         * resolved to {@code defaultForRuns && ready}. {@code true} on an unconfigured instance is
         * honoured as "skip", never as an error - the static analysis is the product and the AI
         * section is an optional extra.
         */
        public Boolean aiReview;
    }

    public Analysis submit(RunRequest request) {
        RepoSource source = store.source(request.sourceId);
        if (source == null) {
            throw new IllegalArgumentException("unknown source: " + request.sourceId);
        }
        request.peerSourceIds = request.peerSourceIds == null ? new ArrayList<>()
                : request.peerSourceIds.stream().filter(id -> id != null && id != source.id).distinct().toList();
        if (request.peerSourceIds.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个要扫描的其他程序");
        }
        Analysis analysis = new Analysis();
        analysis.sourceId = source.id;
        analysis.sourceName = source.name;
        analysis.sourceType = source.type;
        analysis.mode = request.mode == null ? "GIT_DIFF" : request.mode.toUpperCase();
        analysis.baseRef = request.baseRef;
        analysis.targetRef = request.targetRef;
        store.createAnalysis(analysis);
        // 先记一行再提交任务：run() 失败时这条是"确实有过这次分析"的唯一线索，也是把
        // analysisId 与请求参数（模式、范围、对比程序）关联起来的唯一地方。
        log.info(LogEvents.of("analysis.submit",
                "analysisId", analysis.id,
                "sourceId", source.id,
                "source", source.name,
                "mode", analysis.mode,
                "peers", request.peerSourceIds.size(),
                "base", request.baseRef,
                "target", request.targetRef,
                "pr", request.pullRequest,
                "ai", wantsAiReview(request)));
        executor.submit(() -> run(analysis, source, request));
        return analysis;
    }

    private void run(Analysis analysis, RepoSource source, RunRequest request) {
        long start = System.currentTimeMillis();
        log.info(LogEvents.of("analysis.start",
                "analysisId", analysis.id,
                "source", source.name,
                "mode", analysis.mode));
        try {
            Progress progress = new Progress(analysis.id);
            progress.start("PREPARE", 3, "准备代码版本", source.name);
            RepositoryPreparer.Request prep = new RepositoryPreparer.Request();
            prep.source = source;
            prep.mode = analysis.mode;
            prep.baseRef = request.baseRef;
            prep.targetRef = request.targetRef;
            prep.pullRequest = request.pullRequest;
            prep.includeUncommitted = request.includeUncommitted;
            prep.commits = request.commits == null ? new ArrayList<>() : request.commits;
            long prepareStart = System.currentTimeMillis();
            RevisionPair revisions = preparer.prepare(prep);
            // 每个阶段单独计时：一次分析常常是分钟级，"慢在哪"只能靠这些 durationMs 回答。
            log.info(LogEvents.of("analysis.prepare",
                    "analysisId", analysis.id,
                    "mode", analysis.mode,
                    "baseCommit", shortSha(revisions.baseCommit),
                    "headCommit", shortSha(revisions.headCommit),
                    "durationMs", LogEvents.msSince(prepareStart)));
            progress.complete("PREPARE", "DIFF", 10, "读取代码差异", "比较基线与目标版本");

            analysis.baseCommit = revisions.baseCommit;
            analysis.headCommit = revisions.headCommit;

            long diffStart = System.currentTimeMillis();
            FileDiffCollector collector = new FileDiffCollector(revisions.base, revisions.head);
            List<FileChange> fileChanges = collector.collect();
            Map<String, String> diffs = collector.unifiedDiffs(fileChanges, 80, 200);
            log.info(LogEvents.of("analysis.diff",
                    "analysisId", analysis.id,
                    "files", fileChanges.size(),
                    "durationMs", LogEvents.msSince(diffStart)));
            progress.complete("DIFF", "INDEX_HEAD", 20, "索引目标版本",
                    fileChanges.size() + " 个文件发生变化");

            CodeIndexer indexer = new CodeIndexer(properties.getMaxFiles());
            CodeIndex headIndex = indexer.index(revisions.head, revisions.head.label(), revisions.headCommit,
                    (file, done, total) ->
                            progress.file("INDEX_HEAD", 20, 55, "索引目标版本", file, done, total));
            logIndex(analysis.id, "head", headIndex);
            progress.complete("INDEX_HEAD", "INDEX_BASE", 55, "索引基线版本", revisions.base.label());
            CodeIndexer baseIndexer = new CodeIndexer(properties.getMaxFiles());
            CodeIndex baseIndex = baseIndexer.index(revisions.base, revisions.base.label(), revisions.baseCommit,
                    (file, done, total) ->
                            progress.file("INDEX_BASE", 55, 72, "索引基线版本", file, done, total));
            logIndex(analysis.id, "base", baseIndex);
            progress.complete("INDEX_BASE", "DETECT", 72, "检测语义变更", "比较符号、接口与消息契约");

            long detectStart = System.currentTimeMillis();
            ChangeDetector detector = new ChangeDetector(baseIndex, headIndex);
            List<ChangeRecord> changes = detector.detect(fileChanges);
            log.info(LogEvents.of("analysis.detect",
                    "analysisId", analysis.id,
                    "changes", changes.size(),
                    "durationMs", LogEvents.msSince(detectStart)));
            long seq = 1;
            for (ChangeRecord change : changes) {
                change.id = seq++;
            }
            progress.complete("DETECT", "IMPACT", 78, "准备对外契约",
                    changes.size() + " 项语义变更");

            boolean hasPeers = request.peerSourceIds != null && !request.peerSourceIds.isEmpty();
            progress.complete("IMPACT", hasPeers ? "PEERS" : "REPORT", 88,
                    "扫描其他程序",
                    "检查选中的程序是否使用本次变更的契约");
            if (!hasPeers) {
                progress.skip("PEERS", "REPORT", 88, "生成报告", "未选择对比程序");
            }

            // Only the peer side is in scope. Local impacts are deliberately not propagated:
            // the change records and the own index remain available as the source of the contracts.
            List<PeerImpact> peers = collectPeerImpacts(analysis.id, changes, headIndex, request, source);
            ImpactReport report = reportBuilder.build(revisions, headIndex, baseIndex, changes,
                    List.of(), diffs, peers);
            report.analysisId = analysis.id;
            report.sourceName = source.name;
            report.sourceType = source.type;
            report.peers = peers;
            report.peersNote = peersNote(request, changes);
            if (hasPeers) {
                progress.complete("PEERS", "REPORT", 94, "生成报告", "汇总跨程序契约、路径与证据");
            }
            if (wantsAiReview(request)) {
                if (aiExplainer.ready()) {
                    progress.update("REPORT", 96, "生成报告", "正在生成 AI 评审说明");
                    // The AI section must never be able to fail the run: the facts are already
                    // computed and stored by the time this is called, and explain() returns a
                    // generated:false object rather than throwing. The catch is for the
                    // belt-and-braces case (an OOM in prompt building) so a broken extra cannot
                    // turn a good analysis into a FAILED row.
                    long aiStart = System.currentTimeMillis();
                    try {
                        report.ai = aiExplainer.explain(report, properties.getAi().getMaxImpacts());
                        log.info(LogEvents.of("analysis.ai",
                                "analysisId", analysis.id,
                                "generated", generatedOf(report.ai),
                                "durationMs", LogEvents.msSince(aiStart)));
                    } catch (RuntimeException e) {
                        log.warn(LogEvents.of("analysis.ai",
                                "analysisId", analysis.id,
                                "generated", false,
                                "durationMs", LogEvents.msSince(aiStart),
                                "error", e.toString()));
                    }
                } else {
                    // 跳过也是一次决定，字段比一句话更容易按 reason 聚合。
                    log.info(LogEvents.of("analysis.ai",
                            "analysisId", analysis.id,
                            "skipped", true,
                            "reason", aiExplainer.notReadyReason()));
                }
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("changedModules", report.summary.changedModules);
            summary.put("changedSymbols", report.summary.changedSymbols);
            summary.put("impactedModules", report.summary.impactedModules);
            summary.put("impactedSymbols", report.summary.impactedSymbols);
            summary.put("comparedPrograms", report.summary.comparedPrograms);
            summary.put("matchedContracts", report.summary.matchedContracts);
            summary.put("unavailablePrograms", report.summary.unavailablePrograms);
            summary.put("highRisk", report.summary.highRisk);
            summary.put("mediumRisk", report.summary.mediumRisk);
            summary.put("lowRisk", report.summary.lowRisk);
            summary.put("unresolvedCalls", report.summary.unresolvedCalls);
            summary.put("parsedFiles", report.summary.parsedFiles);
            // Listed separately from the report payload so the history rows can say "12 个提交"
            // without loading a full report per row.
            summary.put("commitCount", report.summary.commitCount);

            long durationMs = LogEvents.msSince(start);
            store.finishAnalysis(analysis.id, Analysis.DONE, null, durationMs,
                    summary, report, analysis.baseCommit, analysis.headCommit);
            // 一行汇总整次分析：事后回答"改了多少文件/命中多少契约/哪些调用没解析"的入口。
            log.info(LogEvents.of("analysis.done",
                    "analysisId", analysis.id,
                    "source", source.name,
                    "status", Analysis.DONE,
                    "durationMs", durationMs,
                    "files", fileChanges.size(),
                    "changes", changes.size(),
                    "peers", peers.size(),
                    "high", report.summary.highRisk,
                    "medium", report.summary.mediumRisk,
                    "low", report.summary.lowRisk,
                    "matchedContracts", report.summary.matchedContracts,
                    "unresolvedCalls", report.summary.unresolvedCalls));
            // Only the sync outcome is written back, never the whole row: {@code source} is the
            // snapshot taken when the run was submitted, and a run lasts minutes. Saving it would
            // revert any edit the user made in the meantime - a new tag, a different default
            // branch - with no warning and no way to notice.
            String analysedAt = RepoSource.now();
            store.updateSourceSyncState(source.id, "OK", "analysed at " + analysedAt, analysedAt);
        } catch (Exception e) {
            // 失败分支原先只 updateProgress + finishAnalysis、一条日志都不打，于是一次失败的分析
            // 在日志里完全不存在。先记 ERROR（带堆栈）再落库：即使随后的写库也失败，死因仍然可查。
            long durationMs = LogEvents.msSince(start);
            log.error(LogEvents.of("analysis.failed",
                    "analysisId", analysis.id,
                    "source", source.name,
                    "durationMs", durationMs,
                    "error", messageOf(e)), e);
            store.updateProgress(analysis.id, "FAILED", 0, "分析失败", messageOf(e),
                    List.of(), List.of());
            store.finishAnalysis(analysis.id, Analysis.FAILED, messageOf(e),
                    durationMs, null, null);
            // 失败分支会把 report_json 清空（null），缓存若不随之失效，就会比数据库里的行活得更久。
            reportCache.remove(analysis.id);
        }
    }

    /**
     * Tracks completed stages and writes real progress to SQLite. File callbacks update the detail
     * line, but the percentage only advances when a stage actually finishes.
     */
    private final class Progress {
        private final long analysisId;
        private final List<String> completed = new ArrayList<>();
        private final List<String> skipped = new ArrayList<>();
        private String lastDetail;
        private long lastWriteAt;
        /** Files seen in the current scanning stage, and how many there are in total. */
        private int lastSeen;
        private int lastTotal;

        Progress(long analysisId) {
            this.analysisId = analysisId;
        }

        void start(String stage, int percent, String label, String detail) {
            update(stage, percent, label, detail);
        }

        /**
         * Reports progress through a file-scanning stage.
         *
         * The percentage is interpolated inside [from, to) by the files seen so far. Leaving it at
         * `from` until the stage ends is what made the panel look frozen: INDEX_HEAD covers most of
         * the wall-clock time on a large repository, and it displayed one unchanging number the
         * whole way through, which reads the same as a hung run.
         *
         * `to` is exclusive so the stage still ends exactly on its boundary when
         * {@link #complete} fires.
         */
        void file(String stage, int from, int to, String label, String file, int done, int total) {
            lastSeen = (done > 0 && total > 0) ? done : 0;
            lastTotal = (done > 0 && total > 0) ? total : 0;
            // The detail line is what moves on screen between stage boundaries, but the file name
            // alone says nothing about how far along the run is. Pair it with the counts.
            String shown = lastTotal > 0 ? file + " (" + lastSeen + "/" + lastTotal + ")" : file;
            if (shown.equals(lastDetail)) {
                return;
            }
            lastDetail = shown;
            int percent = from;
            if (lastTotal > 0) {
                int span = Math.max(0, to - from);
                percent = from + (int) Math.round(span * (lastSeen / (double) lastTotal));
                // Never reach `to` early: that value belongs to the completed-stage boundary.
                percent = Math.min(percent, Math.max(from, to - 1));
            }
            update(stage, percent, label, shown);
        }

        /**
         * How far the current file-scanning stage is, for the completion line.
         *
         * Returns "" when no per-file progress was reported (an empty tree, or a stage that does
         * not scan files), so the caller can fall back to its own detail text.
         */
        String scannedSummary() {
            return lastTotal > 0 ? lastSeen + "/" + lastTotal + " 个文件" : "";
        }

        void complete(String stage, String next, int percent, String label, String detail) {
            if (!completed.contains(stage)) {
                completed.add(stage);
            }
            // 阶段推进本身只写数据库；DEBUG 一行足够回答"这次分析的时间花在哪个阶段"，
            // 每个文件回调都打会淹掉真正的里程碑。
            log.debug(LogEvents.of("analysis.stage",
                    "analysisId", analysisId,
                    "stage", stage,
                    "next", next,
                    "percent", percent));
            update(next, percent, label, detail);
        }

        void skip(String stage, String next, int percent, String label, String detail) {
            if (!skipped.contains(stage)) {
                skipped.add(stage);
            }
            log.debug(LogEvents.of("analysis.stage",
                    "analysisId", analysisId,
                    "stage", stage,
                    "next", next,
                    "percent", percent,
                    "skipped", true));
            update(next, percent, label, detail);
        }

        void update(String stage, int percent, String label, String detail) {
            long now = System.currentTimeMillis();
            // File callbacks can be thousands per second. Persist at most every 150 ms unless this
            // is a stage boundary (percent changes), which keeps the live UI useful without turning
            // progress into the dominant SQLite write cost.
            boolean stageBoundary = percent != lastPercent;
            if (!stageBoundary && now - lastWriteAt < 150) {
                return;
            }
            lastWriteAt = now;
            lastPercent = percent;
            store.updateProgress(analysisId, stage, percent, label, detail, completed, skipped);
        }
        private int lastPercent = -1;
    }

    /**
     * Cross-program impact: for every selected peer, index it and check whether this delta
     * crosses a contract into it.
     *
     * A peer that cannot be indexed must not fail the whole analysis - the other peers are still
     * valid - so failures are recorded on the peer entry and shown as a note.
     */
    private List<PeerImpact> collectPeerImpacts(long analysisId, List<ChangeRecord> changes,
                                                CodeIndex headIndex, RunRequest request,
                                                RepoSource ownSource) {
        List<PeerImpact> out = new ArrayList<>();
        if (request.peerSourceIds == null || request.peerSourceIds.isEmpty()) {
            return out;
        }
        if (changes.isEmpty()) {
            return out;
        }
        for (Long peerId : request.peerSourceIds) {
            if (peerId == null || peerId == ownSource.id) {
                continue;
            }
            RepoSource peerSource = store.source(peerId);
            if (peerSource == null) {
                continue;
            }
            PeerImpact peerInfo = new PeerImpact();
            peerInfo.peerSourceId = peerSource.id;
            peerInfo.peerSourceName = peerSource.name;
            peerInfo.peerType = peerSource.type;
            peerInfo.peerLocation = peerSource.location;
            long peerStart = System.currentTimeMillis();
            try {
                RepositoryPreparer.Request peerRequest = new RepositoryPreparer.Request();
                peerRequest.source = peerSource;
                peerRequest.mode = "WORKTREE";
                RevisionPair peerRevisions = preparer.prepare(peerRequest);
                CodeIndex peerIndex = new CodeIndexer(properties.getMaxFiles())
                        .index(peerRevisions.head, peerRevisions.head.label(), peerRevisions.headCommit);
                peerInfo.skippedFiles = peerRevisions.head.skippedFiles();
                long firstImpactId = out.stream().mapToLong(p -> p.impacts.size()).sum() + 1;
                PeerImpact analyzed = new PeerImpactEngine(properties)
                        .analyze(changes, headIndex, peerIndex, peerInfo, firstImpactId);
                // 每个对比程序一行：跨程序这一段最耗时，也最容易"看起来没结果其实是没读进来"。
                log.info(LogEvents.of("analysis.peer",
                        "analysisId", analysisId,
                        "peerId", peerSource.id,
                        "peer", peerSource.name,
                        "contracts", analyzed.matches.size(),
                        "impacts", analyzed.impacts.size(),
                        "skippedFiles", analyzed.skippedFiles.size(),
                        "durationMs", LogEvents.msSince(peerStart)));
                out.add(analyzed);
            } catch (Exception e) {
                peerInfo.note = "无法读取该程序：" + messageOf(e);
                // The report only carries one sentence; the log keeps the transport cause chain so
                // "why could this program not be read" is answerable after the fact.
                log.warn(LogEvents.of("analysis.peer",
                        "analysisId", analysisId,
                        "peerId", peerSource.id,
                        "peer", peerSource.name,
                        "failed", true,
                        "error", GitSupport.describe(e),
                        "durationMs", LogEvents.msSince(peerStart)));
                out.add(peerInfo);
            }
        }
        return out;
    }

    /**
     * Resolves the run dialog's optional checkbox into a decision.
     *
     * The three cases are deliberately distinct: an explicit {@code true} on an unconfigured
     * instance still "wants" it (the caller logs why it was skipped), while a missing value falls
     * back to the operator's default. Folding "not ready" into "not wanted" here would make the two
     * indistinguishable in the log.
     */
    private boolean wantsAiReview(RunRequest request) {
        if (request.aiReview != null) {
            return request.aiReview;
        }
        return properties.getAi().isDefaultForRuns() && aiExplainer.ready();
    }

    private String peersNote(RunRequest request, List<ChangeRecord> changes) {
        if (request.peerSourceIds == null || request.peerSourceIds.isEmpty()) {
            return "本次分析没有选择对比程序";
        }
        if (changes.isEmpty()) {
            return "本次没有检测到变更，无需比较其他程序";
        }
        return null;
    }

    /** Short SHA for log lines: 8 characters is what git and the UI already show. */
    private static String shortSha(String sha) {
        return sha == null || sha.length() <= 8 ? sha : sha.substring(0, 8);
    }

    /**
     * One DEBUG line per indexed side.
     *
     * The counts are the only way to tell "this run found nothing" from "this run never parsed the
     * file that mattered"; they are read off the finished index, so nothing is recomputed here.
     */
    private void logIndex(long analysisId, String side, CodeIndex index) {
        log.debug(LogEvents.of("analysis.index",
                "analysisId", analysisId,
                "side", side,
                "files", index.parsedFiles,
                "symbols", index.symbols.size(),
                "modules", index.modules.size(),
                "unresolved", index.unresolved.size(),
                "durationMs", index.buildMillis));
    }

    /** Whether the stored AI section says it was generated; a null section (not configured) is false. */
    private static boolean generatedOf(Map<String, Object> ai) {
        return ai != null && Boolean.TRUE.equals(ai.get("generated"));
    }

    private String messageOf(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return message.length() > 900 ? message.substring(0, 900) : message;
    }

    /**
     * 已解析报告的缓存。
     *
     * 一份报告在 DONE 之后就不再变化：finishAnalysis 只在完成时写一次 report_json，
     * 重跑一定新建 analysis id，旧行永远不再被改写。所以按 id 缓存是安全的。
     *
     * 上界 4 条：实测单份报告解析后常驻约 1.4 MB，4 条约 5–10 MB，足够覆盖
     * 「打开分析 → 返回队列 → 再打开同一份」这个真实重复模式，又不会让长时间运行的进程
     * 把历史上所有报告都留在堆里。
     */
    private static final int REPORT_CACHE_SIZE = 4;
    private final Map<Long, ImpactReport> reportCache =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, ImpactReport> eldest) {
                    return size() > REPORT_CACHE_SIZE;
                }
            });

    /**
     * Generates (or regenerates) the AI section of an already finished report.
     *
     * Backs the "生成 AI 评审" button on a historical report, which is the reason this exists outside
     * {@link #run}: an analysis is expensive and a missing optional paragraph must not force a
     * re-run of the whole thing.
     *
     * Returns {@code null} when the analysis has no report yet, so the controller can answer 409
     * without knowing how reports are stored. Throws {@link IllegalArgumentException} when the
     * feature is not configured, because that is a request the user can fix in the settings page
     * and the 400 body says exactly what is missing.
     *
     * The write goes through {@link Store#updateReportJson} and the parsed report is replaced in
     * {@link #reportCache} in the same step: without the cache update the page would keep serving
     * the copy parsed before the generation, and the button would look broken until a restart.
     */
    public Map<String, Object> saveAiReview(long analysisId) {
        if (!aiExplainer.ready()) {
            throw new IllegalArgumentException("AI 审核未启用或未配置完成：" + aiExplainer.notReadyReason());
        }
        ImpactReport current = report(analysisId);
        if (current == null) {
            return null;
        }
        Map<String, Object> ai = aiExplainer.explain(current, properties.getAi().getMaxImpacts());
        if (ai == null) {
            // explain() only returns null when it is not ready, which was checked above; treating
            // it as "nothing to save" keeps the endpoint from writing a null section.
            throw new IllegalArgumentException("AI 审核未启用或未配置完成：" + aiExplainer.notReadyReason());
        }
        current.ai = ai;
        // A failed call still updates the stored section (generated:false + error): the page has to
        // show why the retry failed instead of falling back to "本次没有生成".
        store.updateReportJson(analysisId, current);
        reportCache.put(analysisId, current);
        // 补生成是独立于 run() 的一次写入，单独记一行，否则"这段说明是哪次生成的"无从判断。
        log.info(LogEvents.of("analysis.ai.update",
                "analysisId", analysisId,
                "generated", ai.get("generated")));
        return ai;
    }

    public ImpactReport report(long analysisId) {
        // 先查缓存再读库：命中时连 SQLite 都不碰，这是「队列里反复点开同一条」的主要路径。
        ImpactReport cached = reportCache.get(analysisId);
        if (cached != null) {
            return cached;
        }
        Analysis analysis = store.analysisWithReport(analysisId);
        if (analysis == null || analysis.reportJson == null) {
            return null;
        }
        ImpactReport parsed = Json.read(analysis.reportJson, ImpactReport.class);
        if (parsed != null) {
            reportCache.put(analysisId, parsed);
        }
        return parsed;
    }
}
