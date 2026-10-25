package com.geek.association.schedule;

import com.geek.association.config.AppProperties;
import com.geek.association.model.Analysis;
import com.geek.association.model.RepoSource;
import com.geek.association.service.AnalysisService;
import com.geek.association.source.RepositoryPreparer;
import com.geek.association.store.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Daily proactive scan: find programs that received new commits and analyse them.
 *
 * The point is that nobody has to press a button to learn that an upstream service moved. Once a
 * day every registered program is checked cheaply (resolve its current commit, compare with the
 * commit of its last finished analysis) and only the ones that actually moved are run.
 *
 * Two deliberate properties:
 *
 * <ul>
 *   <li><b>Only programs with new commits.</b> Re-analysing unchanged code would produce an</br>
 *       identical report every day and bury the real signal;</li>
 *   <li><b>Peer-only scanning.</b> Each run compares the changed program against every other
 *       program, matching the product rule that only impacts on other programs are reported.</li>
 * </ul>
 */
@Service
public class ScheduledScanService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledScanService.class);

    private final Store store;
    private final AppProperties properties;
    private final AnalysisService analysisService;
    private final RepositoryPreparer preparer;

    /** Guards against two ticks overlapping when one run takes longer than the interval. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile ScanResult lastResult = ScanResult.never();

    public ScheduledScanService(Store store, AppProperties properties, AnalysisService analysisService,
                                RepositoryPreparer preparer) {
        this.store = store;
        this.properties = properties;
        this.analysisService = analysisService;
        this.preparer = preparer;
    }

    /**
     * Optional catch-up run after startup.
     *
     * A freshly deployed instance may have missed several ticks; scanning once at boot closes
     * that gap without waiting for the next cron boundary. Off by default because a restart loop
     * would otherwise trigger repeated scans.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onStartup() {
        // Runs that were in flight when the process died stay RUNNING forever, which would make
        // every later scan skip that program as "busy". Recover them before anything else.
        try {
            // Zero grace: this process just started, so no run can still be alive. The threshold
            // exists for callers that cannot know that; here it would only delay recovery.
            int recovered = store.recoverStaleAnalyses(0);
            if (recovered > 0) {
                log.warn("recovered {} analysis run(s) left RUNNING by a previous process", recovered);
            }
        } catch (RuntimeException e) {
            log.warn("could not recover stale analyses", e);
        }
        if (!properties.getSchedule().isEnabled() || !properties.getSchedule().isRunOnStartup()) {
            return;
        }
        // Run off the startup thread so a slow scan never delays the web server becoming ready.
        Thread thread = new Thread(() -> runOnce("startup"), "scheduled-scan-startup");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Runs on the configured cron (default 02:30 every day).
     *
     * A failure here must never take the service down: a scheduled method that throws is logged and
     * retried on the next tick, so everything is caught and recorded.
     */
    @Scheduled(cron = "${code-association.schedule.cron:0 30 2 * * *}")
    public void dailyTick() {
        if (!properties.getSchedule().isEnabled()) {
            return;
        }
        runOnce("cron");
    }

    /**
     * Starts a scan in the background and returns immediately.
     *
     * A full scan fetches remotes and indexes every changed program, which easily exceeds an HTTP
     * timeout. The trigger endpoint therefore returns the accepted state and the caller polls
     * {@code GET /api/schedule} for the outcome.
     */
    public ScanResult runAsync(String trigger) {
        if (!running.compareAndSet(false, true)) {
            return ScanResult.skipped(trigger, "上一次扫描仍在进行中");
        }
        // The flag is already taken; hand it to the worker, which owns it from here on.
        ScanResult accepted = new ScanResult();
        accepted.trigger = trigger;
        accepted.status = "RUNNING";
        accepted.startedAt = Instant.now().toString();
        accepted.message = "扫描已开始，稍后通过 GET /api/schedule 查看结果";
        lastResult = accepted;
        Thread thread = new Thread(() -> execute(trigger), "scheduled-scan-" + trigger);
        thread.setDaemon(true);
        thread.start();
        return accepted;
    }

    /**
     * Scans now and returns what happened.
     *
     * @param trigger how this run was started, recorded for the status endpoint
     */
    public ScanResult runOnce(String trigger) {
        if (!running.compareAndSet(false, true)) {
            ScanResult skipped = ScanResult.skipped(trigger, "上一次扫描仍在进行中");
            lastResult = skipped;
            log.info("scheduled scan skipped: a previous run is still in progress");
            return skipped;
        }
        return execute(trigger);
    }

    /**
     * The scan body, shared by the synchronous and background entry points. The caller already
     * holds the run guard; this method releases it when done.
     */
    private ScanResult execute(String trigger) {
        long startedAt = System.currentTimeMillis();
        ScanResult result = new ScanResult();
        result.trigger = trigger;
        result.startedAt = Instant.now().toString();
        try {
            List<RepoSource> sources = store.sources();
            result.registered = sources.size();
            int budget = Math.max(1, properties.getSchedule().getMaxProgramsPerRun());
            for (RepoSource source : sources) {
                if (result.scanned >= budget) {
                    result.deferred = sources.size() - result.scanned - result.skipped;
                    break;
                }
                scanOne(source, result);
            }
            result.status = "DONE";
        } catch (Exception e) {
            result.status = "FAILED";
            result.message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("scheduled scan failed", e);
        } finally {
            result.finishedAt = Instant.now().toString();
            result.durationMs = System.currentTimeMillis() - startedAt;
            lastResult = result;
            running.set(false);
        }
        log.info("scheduled scan {} by {}: registered={} scanned={} skipped={} failed={} deferred={}",
                result.status, trigger, result.registered, result.scanned, result.skipped,
                result.failed, result.deferred);
        return result;
    }

    /**
     * Decides whether one program needs a run, and submits it when it does.
     *
     * A program is scanned when its current commit differs from the commit of its last finished
     * analysis. A plain directory has no commit, so it falls back to a time-based rule.
     */
    private void scanOne(RepoSource source, ScanResult result) {
        if (source.id <= 0) {
            return;
        }
        try {
            if (hasRunningAnalysis(source.id)) {
                result.skipped++;
                result.details.add(entry(source, "SKIPPED", "已有分析在进行中"));
                return;
            }
            String head = preparer.currentCommit(source);
            boolean versioned = preparer.isVersioned(source);
            Analysis last = lastFinished(source.id);
            if (!versioned) {
                // No commits to compare: rescan on a coarse schedule instead of every tick.
                if (last == null || olderThan(last.finishedAt, properties.getSchedule().getDirectoryRescanMinutes())) {
                    submit(source, result, "目录型程序，按时间间隔重新扫描");
                } else {
                    result.skipped++;
                    result.details.add(entry(source, "SKIPPED", "目录型程序，尚未到重新扫描时间"));
                }
                return;
            }
            if (head == null) {
                result.skipped++;
                result.details.add(entry(source, "SKIPPED", "无法解析当前提交"));
                return;
            }
            if (last != null && head.equals(last.headCommit)) {
                result.skipped++;
                result.details.add(entry(source, "SKIPPED", "没有新提交"));
                return;
            }
            String reason = last == null
                    ? "首次扫描"
                    : "有新提交 " + shortId(last.headCommit) + " → " + shortId(head);
            submit(source, result, reason);
        } catch (Exception e) {
            result.failed++;
            result.details.add(entry(source, "FAILED", messageOf(e)));
            log.warn("scheduled scan could not inspect {}", source.name, e);
        }
    }

    /** Submits the run with every other program as a peer, which is what the report is about. */
    private void submit(RepoSource source, ScanResult result, String reason) {
        List<Long> peers = new ArrayList<>();
        for (RepoSource other : store.sources()) {
            if (other.id != source.id) {
                peers.add(other.id);
            }
        }
        if (peers.isEmpty()) {
            result.skipped++;
            result.details.add(entry(source, "SKIPPED", "没有其他程序可比较"));
            return;
        }
        AnalysisService.RunRequest request = new AnalysisService.RunRequest();
        request.sourceId = source.id;
        request.mode = "GIT_DIFF";
        request.peerSourceIds = peers;
        try {
            Analysis analysis = analysisService.submit(request);
            result.scanned++;
            result.analysisIds.add(analysis.id);
            result.details.add(entry(source, "SCANNED", reason + "，分析 #" + analysis.id));
        } catch (Exception e) {
            result.failed++;
            result.details.add(entry(source, "FAILED", messageOf(e)));
        }
    }

    private boolean hasRunningAnalysis(long sourceId) {
        for (Analysis analysis : store.analysesOfSource(sourceId)) {
            if (Analysis.RUNNING.equals(analysis.status) || Analysis.PENDING.equals(analysis.status)) {
                return true;
            }
        }
        return false;
    }

    private Analysis lastFinished(long sourceId) {
        for (Analysis analysis : store.analysesOfSource(sourceId)) {
            if (Analysis.DONE.equals(analysis.status)) {
                return analysis;
            }
        }
        return null;
    }

    private boolean olderThan(String isoTime, int minutes) {
        if (isoTime == null || isoTime.isBlank()) {
            return true;
        }
        try {
            Instant then = Instant.parse(isoTime);
            return Duration.between(then, Instant.now()).toMinutes() >= minutes;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private ScanResult.Detail entry(RepoSource source, String outcome, String reason) {
        ScanResult.Detail detail = new ScanResult.Detail();
        detail.sourceId = source.id;
        detail.sourceName = source.name;
        detail.outcome = outcome;
        detail.reason = reason;
        return detail;
    }

    private String shortId(String id) {
        return id == null || id.length() < 8 ? String.valueOf(id) : id.substring(0, 8);
    }

    private String messageOf(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** Result of one scan, exposed through the status endpoint and the CLI. */
    public static class ScanResult {
        public String trigger;
        public String status = "RUNNING";
        public String message;
        public String startedAt;
        public String finishedAt;
        public long durationMs;
        public int registered;
        public int scanned;
        public int skipped;
        public int failed;
        public int deferred;
        public List<Long> analysisIds = new ArrayList<>();
        public List<Detail> details = new ArrayList<>();

        static ScanResult never() {
            ScanResult result = new ScanResult();
            result.status = "NEVER";
            result.message = "尚未执行过定时扫描";
            return result;
        }

        static ScanResult skipped(String trigger, String why) {
            ScanResult result = new ScanResult();
            result.trigger = trigger;
            result.status = "SKIPPED";
            result.message = why;
            result.startedAt = Instant.now().toString();
            result.finishedAt = result.startedAt;
            return result;
        }

        public static class Detail {
            public long sourceId;
            public String sourceName;
            public String outcome;
            public String reason;
        }
    }

    /** Status for the UI and for operators checking whether the schedule is alive. */
    public ScanResult lastResult() {
        return lastResult;
    }

    public boolean isRunning() {
        return running.get();
    }
}
