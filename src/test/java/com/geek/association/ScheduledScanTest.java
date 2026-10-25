package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.model.Analysis;
import com.geek.association.model.RepoSource;
import com.geek.association.schedule.ScheduledScanService;
import com.geek.association.service.AnalysisService;
import com.geek.association.source.RepositoryPreparer;
import com.geek.association.store.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The daily scan must be selective: only programs whose commits moved get re-analysed.
 *
 * Re-running everything every day would bury the one report that matters, so the decision logic is
 * the core of this feature and is tested directly against a real SQLite store.
 */
class ScheduledScanTest {

    private static AppProperties props(Path dataDir) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(dataDir.toString());
        return properties;
    }

    /** Records submissions instead of running them, so the decision logic is what gets tested. */
    private static final class RecordingService extends AnalysisService {
        final List<RunRequest> submitted = new ArrayList<>();

        RecordingService(Store store, AppProperties properties, RepositoryPreparer preparer) {
            super(store, properties, preparer, null);
        }

        @Override
        public Analysis submit(RunRequest request) {
            submitted.add(request);
            Analysis analysis = new Analysis();
            analysis.id = submitted.size();
            analysis.sourceId = request.sourceId;
            return analysis;
        }
    }

    private static RepoSource gitRepo(Store store, Path repo, String name) throws Exception {
        Files.createDirectories(repo);
        exec(repo, "init", "-q");
        exec(repo, "config", "user.email", "t@example.com");
        exec(repo, "config", "user.name", "t");
        Files.writeString(repo.resolve("a.txt"), "a");
        exec(repo, "add", ".");
        exec(repo, "commit", "-qm", "init");
        RepoSource source = new RepoSource();
        source.name = name;
        source.type = "LOCAL_GIT";
        source.location = repo.toString();
        return store.saveSource(source);
    }

    private static void exec(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(dir.toString());
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), "git " + String.join(" ", args));
    }

    @Test
    void scansAProgramThatNeverRanAndRequiresAPeer(@TempDir Path tmp) throws Exception {
        AppProperties properties = props(tmp.resolve("data"));
        Store store = new Store(properties);
        RepoSource own = gitRepo(store, tmp.resolve("own"), "own");
        RepoSource peer = gitRepo(store, tmp.resolve("peer"), "peer");
        RecordingService service = new RecordingService(store, properties, new RepositoryPreparer(properties));

        ScheduledScanService scan = new ScheduledScanService(store, properties, service,
                new RepositoryPreparer(properties));
        ScheduledScanService.ScanResult result = scan.runOnce("test");

        assertEquals("DONE", result.status);
        assertTrue(result.scanned >= 1, "a program that never ran must be scanned");
        assertFalse(service.submitted.isEmpty());
        for (AnalysisService.RunRequest request : service.submitted) {
            assertFalse(request.peerSourceIds.isEmpty(),
                    "a scheduled run must always compare against other programs");
            assertFalse(request.peerSourceIds.contains(request.sourceId),
                    "a program is never its own peer");
        }
        assertTrue(service.submitted.stream().anyMatch(r -> r.sourceId == own.id),
                "the program with no history must be scanned");
        assertNotNull(peer);
    }

    @Test
    void skipsAProgramWhoseCommitDidNotMove(@TempDir Path tmp) throws Exception {
        AppProperties properties = props(tmp.resolve("data"));
        Store store = new Store(properties);
        RepoSource own = gitRepo(store, tmp.resolve("own"), "own");
        gitRepo(store, tmp.resolve("peer"), "peer");
        RepositoryPreparer preparer = new RepositoryPreparer(properties);
        RecordingService service = new RecordingService(store, properties, preparer);
        ScheduledScanService scan = new ScheduledScanService(store, properties, service, preparer);

        // First run scans, then we pretend it finished on the current commit.
        scan.runOnce("first");
        long ownRunsAfterFirst = service.submitted.stream().filter(r -> r.sourceId == own.id).count();
        assertTrue(ownRunsAfterFirst >= 1, "the program with no history must be scanned first");

        String head = preparer.currentCommit(own);
        Analysis done = new Analysis();
        done.sourceId = own.id;
        done.sourceName = own.name;
        done.sourceType = own.type;
        done.mode = "GIT_DIFF";
        store.createAnalysis(done);
        store.finishAnalysis(done.id, Analysis.DONE, null, 1, null, null, null, head);

        ScheduledScanService.ScanResult second = scan.runOnce("second");
        assertEquals("DONE", second.status);
        ScheduledScanService.ScanResult.Detail ownDetail = second.details.stream()
                .filter(d -> d.sourceId == own.id).findFirst().orElseThrow();
        assertEquals("SKIPPED", ownDetail.outcome, "no new commit means no new analysis");
        assertTrue(ownDetail.reason.contains("没有新提交"));
        assertEquals(ownRunsAfterFirst, service.submitted.stream().filter(r -> r.sourceId == own.id).count(),
                "the unchanged program must not be resubmitted");
    }

    @Test
    void skipsAProgramWhoseLocationDisappeared(@TempDir Path tmp) throws Exception {
        AppProperties properties = props(tmp.resolve("data"));
        Store store = new Store(properties);
        Path gone = tmp.resolve("gone");
        gitRepo(store, gone, "gone");
        gitRepo(store, tmp.resolve("peer"), "peer");
        RepositoryPreparer preparer = new RepositoryPreparer(properties);
        RecordingService service = new RecordingService(store, properties, preparer);
        ScheduledScanService scan = new ScheduledScanService(store, properties, service, preparer);

        deleteRecursively(gone);

        ScheduledScanService.ScanResult result = scan.runOnce("test");
        assertEquals("DONE", result.status);
        assertTrue(result.details.stream()
                        .anyMatch(d -> "SKIPPED".equals(d.outcome) && d.reason.startsWith("位置不可访问")),
                "a missing location is reported once, not retried as a failing run");
        assertTrue(service.submitted.stream().noneMatch(r -> r.sourceId == result.details.stream()
                .filter(d -> d.reason.startsWith("位置不可访问")).findFirst().orElseThrow().sourceId));
    }

    @Test
    void recoversRunsLeftRunningByAPreviousProcess(@TempDir Path tmp) {
        AppProperties properties = props(tmp.resolve("data"));
        Store store = new Store(properties);
        Analysis orphan = new Analysis();
        orphan.sourceId = 1;
        orphan.sourceName = "orphan";
        orphan.sourceType = "LOCAL_GIT";
        orphan.mode = "GIT_DIFF";
        store.createAnalysis(orphan);

        int recovered = store.recoverStaleAnalyses(0);

        assertEquals(1, recovered);
        assertEquals(Analysis.FAILED, store.analysis(orphan.id).status);
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }
}
