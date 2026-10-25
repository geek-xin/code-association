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
import com.geek.association.source.RepositoryPreparer;
import com.geek.association.source.RevisionPair;
import com.geek.association.store.Store;
import com.geek.association.util.Json;
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
        /** GIT_DIFF | WORKTREE_DIFF | PULL_REQUEST */
        public String mode;
        public String baseRef;
        public String targetRef;
        public Integer pullRequest;
        public boolean includeTests = false;
        public boolean includeUncommitted = true;
        /** Other programs to scan for cross-program impact, by source id. */
        public List<Long> peerSourceIds = new ArrayList<>();
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
        executor.submit(() -> run(analysis, source, request));
        return analysis;
    }

    private void run(Analysis analysis, RepoSource source, RunRequest request) {
        long start = System.currentTimeMillis();
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
            RevisionPair revisions = preparer.prepare(prep);
            progress.complete("PREPARE", "DIFF", 10, "读取代码差异", "比较基线与目标版本");

            analysis.baseCommit = revisions.baseCommit;
            analysis.headCommit = revisions.headCommit;

            FileDiffCollector collector = new FileDiffCollector(revisions.base, revisions.head);
            List<FileChange> fileChanges = collector.collect();
            Map<String, String> diffs = collector.unifiedDiffs(fileChanges, 80, 200);
            progress.complete("DIFF", "INDEX_HEAD", 20, "索引目标版本",
                    fileChanges.size() + " 个文件发生变化");

            CodeIndexer indexer = new CodeIndexer(properties.getMaxFiles());
            CodeIndex headIndex = indexer.index(revisions.head, revisions.head.label(), revisions.headCommit,
                    file -> progress.file("INDEX_HEAD", 20, 55, "索引目标版本", file));
            progress.complete("INDEX_HEAD", "INDEX_BASE", 55, "索引基线版本", revisions.base.label());
            CodeIndexer baseIndexer = new CodeIndexer(properties.getMaxFiles());
            CodeIndex baseIndex = baseIndexer.index(revisions.base, revisions.base.label(), revisions.baseCommit,
                    file -> progress.file("INDEX_BASE", 55, 72, "索引基线版本", file));
            progress.complete("INDEX_BASE", "DETECT", 72, "检测语义变更", "比较符号、接口与消息契约");

            ChangeDetector detector = new ChangeDetector(baseIndex, headIndex);
            List<ChangeRecord> changes = detector.detect(fileChanges);
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
            List<PeerImpact> peers = collectPeerImpacts(changes, headIndex, request, source);
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
            if (aiExplainer.enabled()) {
                progress.update("REPORT", 96, "生成报告", "正在生成 AI 评审说明");
                report.ai = aiExplainer.explain(report, 12);
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

            store.finishAnalysis(analysis.id, Analysis.DONE, null, System.currentTimeMillis() - start,
                    summary, report, analysis.baseCommit, analysis.headCommit);
            source.lastSyncedAt = RepoSource.now();
            source.status = "OK";
            source.message = "analysed at " + RepoSource.now();
            store.saveSource(source);
        } catch (Exception e) {
            store.updateProgress(analysis.id, "FAILED", 0, "分析失败", messageOf(e),
                    List.of(), List.of());
            store.finishAnalysis(analysis.id, Analysis.FAILED, messageOf(e),
                    System.currentTimeMillis() - start, null, null);
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

        Progress(long analysisId) {
            this.analysisId = analysisId;
        }

        void start(String stage, int percent, String label, String detail) {
            update(stage, percent, label, detail);
        }

        void file(String stage, int from, int to, String label, String file) {
            if (file.equals(lastDetail)) {
                return;
            }
            lastDetail = file;
            // Keep the percentage stable within a stage: file callbacks show what is being parsed,
            // while the percentage advances only when a whole stage completes.
            update(stage, from, label, file);
        }

        void complete(String stage, String next, int percent, String label, String detail) {
            if (!completed.contains(stage)) {
                completed.add(stage);
            }
            update(next, percent, label, detail);
        }

        void skip(String stage, String next, int percent, String label, String detail) {
            if (!skipped.contains(stage)) {
                skipped.add(stage);
            }
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
    private List<PeerImpact> collectPeerImpacts(List<ChangeRecord> changes, CodeIndex headIndex,
                                                RunRequest request, RepoSource ownSource) {
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
            try {
                RepositoryPreparer.Request peerRequest = new RepositoryPreparer.Request();
                peerRequest.source = peerSource;
                peerRequest.mode = "WORKTREE";
                RevisionPair peerRevisions = preparer.prepare(peerRequest);
                CodeIndex peerIndex = new CodeIndexer(properties.getMaxFiles())
                        .index(peerRevisions.head, peerRevisions.head.label(), peerRevisions.headCommit);
                long firstImpactId = out.stream().mapToLong(p -> p.impacts.size()).sum() + 1;
                out.add(new PeerImpactEngine(properties)
                        .analyze(changes, headIndex, peerIndex, peerInfo, firstImpactId));
            } catch (Exception e) {
                peerInfo.note = "无法读取该程序：" + messageOf(e);
                out.add(peerInfo);
            }
        }
        return out;
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

    private String messageOf(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return message.length() > 900 ? message.substring(0, 900) : message;
    }

    public ImpactReport report(long analysisId) {
        Analysis analysis = store.analysis(analysisId);
        if (analysis == null || analysis.reportJson == null) {
            return null;
        }
        return Json.read(analysis.reportJson, ImpactReport.class);
    }
}
