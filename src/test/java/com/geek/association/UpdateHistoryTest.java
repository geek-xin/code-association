package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.update.GitHubVersionSource;
import com.geek.association.update.ReleaseInfo;
import com.geek.association.update.UpdateHistory;
import com.geek.association.update.UpdateService;
import com.geek.association.update.Version;
import com.geek.association.update.VersionSource;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The persisted version ledger and everything {@link UpdateService} does with it.
 *
 * Two invariants are the point of these tests:
 *
 * <ul>
 *   <li><b>A broken ledger never breaks a check.</b> The file is bookkeeping; the update card must
 *       still render when the JSON is garbage or the data directory is unwritable;</li>
 *   <li><b>"downloaded" is read from the disk, not remembered.</b> The UI promises to reflect the
 *       files that are actually there, including ones deleted by hand.</li>
 * </ul>
 */
class UpdateHistoryTest {

    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-02T03:04:05Z");

    private static ReleaseInfo release(String version) {
        ReleaseInfo release = new ReleaseInfo();
        release.version = version;
        release.tag = "v" + version;
        release.name = "code-association " + version;
        release.publishedAt = "2026-01-01T00:00:00Z";
        release.notes = "notes for " + version;
        release.url = GitHubVersionSource.releasePageUrl("o/r", release.tag);
        return release;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> versions(Map<String, Object> snapshot) {
        return (List<Map<String, Object>>) snapshot.get("versions");
    }

    // ------------------------------------------------------------- the ledger

    @Test
    void recordingTheSameVersionTwiceKeepsOneEntryAndTheFirstSight(@TempDir Path tmp) {
        UpdateHistory history = new UpdateHistory(tmp.resolve(UpdateHistory.FILE_NAME));

        history.record(List.of(release("1.2.3")), T1);
        history.record(List.of(release("1.2.3")), T2);

        Map<String, Object> snapshot = history.snapshot();
        assertEquals(1, snapshot.get("count"), "a version seen twice is still one version");
        assertEquals(1, versions(snapshot).size());
        Map<String, Object> entry = versions(snapshot).get(0);
        assertEquals("1.2.3", entry.get("version"));
        assertEquals(T1.toString(), entry.get("firstSeenAt"), "first sight must not move");
        assertEquals(T2.toString(), entry.get("lastSeenAt"), "last sight must advance");
        assertEquals(T2.toString(), snapshot.get("lastUpdatedAt"));
        assertEquals(T1.toString(), snapshot.get("firstSeenAt"));
    }

    @Test
    void theLedgerSurvivesARestart(@TempDir Path tmp) {
        Path file = tmp.resolve(UpdateHistory.FILE_NAME);
        new UpdateHistory(file).record(List.of(release("1.2.3")), T1);

        // A second instance stands in for the next process: nothing is shared but the file.
        UpdateHistory reopened = new UpdateHistory(file);
        reopened.record(List.of(release("1.2.3")), T2);

        Map<String, Object> entry = versions(reopened.snapshot()).get(0);
        assertEquals(T1.toString(), entry.get("firstSeenAt"), "first sight comes from the file");
        assertEquals(T2.toString(), entry.get("lastSeenAt"));
        assertEquals(file.toAbsolutePath().normalize().toString(), reopened.snapshot().get("file"));
    }

    @Test
    void versionsAreOrderedNewestFirst(@TempDir Path tmp) {
        UpdateHistory history = new UpdateHistory(tmp.resolve(UpdateHistory.FILE_NAME));

        // Deliberately out of order, and mixing the cases the comparator has to get right: a fourth
        // segment outranks its three-segment prefix, and a pre-release ranks below its release.
        history.record(List.of(release("1.2.3-rc1"), release("1.2.3"), release("1.2.10"),
                release("1.2.3.4")), T1);

        List<Map<String, Object>> ordered = versions(history.snapshot());
        assertEquals(List.of("1.2.10", "1.2.3.4", "1.2.3", "1.2.3-rc1"),
                ordered.stream().map(row -> row.get("version")).toList());
    }

    @Test
    void theMarksAppearInTheSnapshot(@TempDir Path tmp) {
        UpdateHistory history = new UpdateHistory(tmp.resolve(UpdateHistory.FILE_NAME));
        history.record(List.of(release("1.2.3")), T1);

        history.markDownloaded("1.2.3", T1);
        history.markApplied("1.2.3", T2);
        history.markRolledBack("1.2.3", T2);

        Map<String, Object> entry = versions(history.snapshot()).get(0);
        assertEquals(T1.toString(), entry.get("downloadedAt"));
        assertEquals(T2.toString(), entry.get("appliedAt"));
        assertEquals(T2.toString(), entry.get("rolledBackAt"));
        assertEquals(false, entry.get("prerelease"));
        assertEquals("notes for 1.2.3", entry.get("notes"));
        assertEquals("https://github.com/o/r/releases/tag/v1.2.3", entry.get("url"));
    }

    @Test
    void aMarkForAVersionNeverSeenStillGetsRecorded(@TempDir Path tmp) {
        UpdateHistory history = new UpdateHistory(tmp.resolve(UpdateHistory.FILE_NAME));

        // A jar downloaded before this feature existed: the mark is the only evidence there is.
        history.markDownloaded("0.9.0", T1);

        Map<String, Object> entry = versions(history.snapshot()).get(0);
        assertEquals("0.9.0", entry.get("version"));
        assertEquals(T1.toString(), entry.get("downloadedAt"));
        assertEquals(T1.toString(), entry.get("firstSeenAt"));
    }

    @Test
    void aCorruptLedgerDegradesToEmptyInsteadOfThrowing(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve(UpdateHistory.FILE_NAME);
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        UpdateHistory history = new UpdateHistory(file);

        Map<String, Object> snapshot = history.snapshot();
        assertEquals(0, snapshot.get("count"));
        assertEquals(0, versions(snapshot).size());
        assertNull(snapshot.get("firstSeenAt"));
        assertNull(snapshot.get("lastUpdatedAt"));

        // And it recovers: the next successful check rewrites a usable file.
        history.record(List.of(release("1.2.3")), T1);
        assertEquals(1, new UpdateHistory(file).snapshot().get("count"));
    }

    @Test
    void aLedgerWithTheWrongShapeIsTreatedAsEmpty(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve(UpdateHistory.FILE_NAME);
        Files.writeString(file, "{\"versions\": \"nope\"}", StandardCharsets.UTF_8);

        UpdateHistory history = new UpdateHistory(file);
        assertEquals(0, history.snapshot().get("count"));

        history.record(List.of(release("2.0.0")), T1);
        assertEquals(1, history.snapshot().get("count"));
    }

    @Test
    void theLedgerIsPrunedToTheNewestTwoHundredVersions(@TempDir Path tmp) {
        UpdateHistory history = new UpdateHistory(tmp.resolve(UpdateHistory.FILE_NAME));
        List<ReleaseInfo> releases = new ArrayList<>();
        for (int i = 0; i < 205; i++) {
            releases.add(release("1.0." + i));
        }

        history.record(releases, T1);

        List<Map<String, Object>> kept = versions(history.snapshot());
        assertEquals(UpdateHistory.MAX_ENTRIES, kept.size(), "the file must not grow without bound");
        assertEquals("1.0.204", kept.get(0).get("version"), "the newest version is the first one");
        List<Object> names = kept.stream().map(row -> row.get("version")).toList();
        assertFalse(names.contains("1.0.0"), "the oldest versions are the ones dropped");
        assertFalse(names.contains("1.0.4"));
        assertTrue(names.contains("1.0.5"), "exactly the oldest five are dropped");
    }

    @Test
    void anEmptyCheckIsNotWrittenAsAChange(@TempDir Path tmp) {
        Path file = tmp.resolve(UpdateHistory.FILE_NAME);
        UpdateHistory history = new UpdateHistory(file);

        history.record(List.of(), T1);

        assertEquals(0, history.size());
        assertFalse(Files.exists(file), "nothing seen yet means nothing to persist");
    }

    // -------------------------------------------------------- UpdateService

    private static AppProperties props(Path dataDir, int intervalMinutes) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(dataDir.toString());
        properties.getUpdate().setCheckIntervalMinutes(intervalMinutes);
        return properties;
    }

    /** A source that reports fixed versions and, optionally, a warning and a download URL. */
    private static final class FakeSource implements VersionSource {
        final List<String> versions;
        final List<String> warnings;
        String downloadUrl;

        FakeSource(List<String> warnings, String... versions) {
            this.versions = List.of(versions);
            this.warnings = warnings;
        }

        @Override
        public List<ReleaseInfo> fetch() {
            List<ReleaseInfo> out = new ArrayList<>();
            for (String version : versions) {
                ReleaseInfo release = release(version);
                if (downloadUrl != null) {
                    release.assets.add(new ReleaseInfo.Asset(
                            "code-association-" + version + ".jar", 0L, downloadUrl));
                }
                out.add(release);
            }
            return out;
        }

        @Override
        public List<String> warnings() {
            return warnings;
        }
    }

    private static UpdateService service(AppProperties properties, VersionSource source) {
        return new UpdateService(properties, source, HttpClient.newHttpClient(), Version.parse("0.3.0"));
    }

    @Test
    void statusReportsDownloadStateFromTheFilesOnDisk(@TempDir Path tmp) throws Exception {
        byte[] jar = fakeJar(1024);
        try (FakeServer server = FakeServer.start(jar)) {
            FakeSource source = new FakeSource(List.of(), "0.9.0");
            source.downloadUrl = server.url();
            AppProperties properties = props(tmp.resolve("data"), 360);
            UpdateService service = service(properties, source);

            List<ReleaseInfo> before = releases(service.status());
            assertFalse(before.get(0).downloaded, "nothing has been fetched yet");
            assertNull(before.get(0).downloadedAt);

            Path downloaded = Path.of((String) service.download("0.9.0").get("jarPath"));

            List<ReleaseInfo> after = releases(service.status());
            assertTrue(after.get(0).downloaded, "the jar is in {dataDir}/updates");
            assertNotNull(after.get(0).downloadedAt, "the UI shows when it was fetched");
            assertEquals(1, service.status().get("historyTracked"),
                    "the download also put the version in the ledger");

            // The directory is the truth: removing the file must flip the flag back, because the
            // user may well have cleaned up {dataDir}/updates by hand.
            Files.delete(downloaded);
            assertFalse(releases(service.status()).get(0).downloaded,
                    "a jar deleted by hand is reported as missing");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ReleaseInfo> releases(Map<String, Object> status) {
        return (List<ReleaseInfo>) status.get("releases");
    }

    @Test
    void statusExposesTheLedgerSizeAndTheSourceWarnings(@TempDir Path tmp) {
        FakeSource source = new FakeSource(List.of("读取 tags 失败，仅显示 releases"), "0.4.0", "0.3.0");
        AppProperties properties = props(tmp, 360);
        UpdateService service = service(properties, source);

        Map<String, Object> status = service.status();

        assertEquals(List.of("读取 tags 失败，仅显示 releases"), status.get("warnings"));
        assertEquals(true, status.get("includeTags"));
        assertEquals(false, status.get("autoDownload"));
        assertEquals(2, status.get("historyTracked"), "both releases were recorded");
        assertEquals(2, ((List<?>) service.history().get("versions")).size());
    }

    @Test
    void aBrokenLedgerDoesNotBreakTheUpdateCheck(@TempDir Path tmp) throws Exception {
        Path dataDir = tmp.resolve("data");
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve(UpdateHistory.FILE_NAME), "not json at all",
                StandardCharsets.UTF_8);
        UpdateService service = service(props(dataDir, 360), new FakeSource(List.of(), "0.4.0"));

        Map<String, Object> status = service.status();

        assertEquals("github", status.get("source"), "the check still succeeded");
        assertNull(status.get("error"));
        assertEquals(true, status.get("updateAvailable"));
        // The unreadable file degraded to an empty ledger and the successful check rewrote it, so
        // exactly the release that was just seen is tracked. What matters is that nothing threw.
        assertEquals(1, status.get("historyTracked"));
        assertEquals(1, ((List<?>) service.history().get("versions")).size());
    }

    @Test
    void autoDownloadOffLeavesTheUpdatesDirectoryEmpty(@TempDir Path tmp) throws Exception {
        try (FakeServer server = FakeServer.start(fakeJar(512))) {
            FakeSource source = new FakeSource(List.of(), "0.9.0");
            source.downloadUrl = server.url();
            AppProperties properties = props(tmp.resolve("data"), 360);
            assertFalse(properties.getUpdate().isAutoDownload(), "off by default");

            UpdateService service = service(properties, source);
            service.status();

            Path updates = tmp.resolve("data").resolve("updates");
            assertTrue(!Files.isDirectory(updates) || count(updates) == 0,
                    "nothing may be fetched without being asked");
            assertFalse(releases(service.status()).get(0).downloaded);
        }
    }

    @Test
    void autoDownloadOnFetchesTheNewestVersionInTheBackground(@TempDir Path tmp) throws Exception {
        try (FakeServer server = FakeServer.start(fakeJar(2048))) {
            FakeSource source = new FakeSource(List.of(), "0.9.0", "0.8.0");
            source.downloadUrl = server.url();
            AppProperties properties = props(tmp.resolve("data"), 360);
            properties.getUpdate().setAutoDownload(true);
            UpdateService service = service(properties, source);

            service.status();

            // The download happens on a daemon thread, so the file appears after status() returned.
            Path newest = tmp.resolve("data").resolve("updates").resolve("code-association-0.9.0.jar");
            awaitFile(newest);
            assertTrue(Files.isRegularFile(newest), "auto-download fetches the newest version");
            assertFalse(Files.exists(tmp.resolve("data").resolve("updates")
                    .resolve("code-association-0.8.0.jar")), "only the newest one is fetched");
            // The background download is recorded like a manual one, so the history shows it.
            assertTrue(releases(service.status()).get(0).downloaded);
        }
    }

    private static long count(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.count();
        }
    }

    /** Polls for a file a daemon thread is writing; the timeout only bounds a failure. */
    private static void awaitFile(Path file) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            if (Files.isRegularFile(file)) {
                return;
            }
            Thread.sleep(25);
        }
    }

    /** A byte array that starts like a real jar, padded to a realistic size. */
    private static byte[] fakeJar(int size) {
        byte[] bytes = new byte[Math.max(size, 4)];
        bytes[0] = 0x50;
        bytes[1] = 0x4B;
        bytes[2] = 0x03;
        bytes[3] = 0x04;
        for (int i = 4; i < bytes.length; i++) {
            bytes[i] = (byte) (i % 251);
        }
        return bytes;
    }

    /** Loopback HTTP server: a download needs a real response body, but never the internet. */
    private static final class FakeServer implements AutoCloseable {
        private final HttpServer server;

        private FakeServer(HttpServer server) {
            this.server = server;
        }

        static FakeServer start(byte[] body) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/asset.jar", exchange -> {
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return new FakeServer(server);
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/asset.jar";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
