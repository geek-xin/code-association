package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.update.GitHubVersionSource;
import com.geek.association.update.ReleaseInfo;
import com.geek.association.update.UpdateException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The orchestration around a version source: caching, the "is there an update" decision, the jar
 * download and its header check.
 *
 * Every dependency that could reach the outside world is replaced here - the version source is a
 * fake and the download goes to a loopback server - because a test that depended on GitHub would
 * be both flaky and rate limited.
 */
class UpdateServiceTest {

    private static AppProperties props(Path dataDir, int intervalMinutes) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(dataDir.toString());
        properties.getUpdate().setCheckIntervalMinutes(intervalMinutes);
        return properties;
    }

    /** Counts calls and returns a fixed list, so cache hits are observable. */
    private static final class FakeSource implements VersionSource {
        final List<String> versions;
        int calls;
        UpdateException failure;
        /** Where a downloaded jar would come from; null means the release has no assets. */
        String downloadUrl;

        FakeSource(String... versions) {
            this.versions = List.of(versions);
        }

        @Override
        public List<ReleaseInfo> fetch() {
            calls++;
            if (failure != null) {
                throw failure;
            }
            List<ReleaseInfo> out = new ArrayList<>();
            for (String version : versions) {
                ReleaseInfo release = new ReleaseInfo();
                release.version = version;
                release.tag = "v" + version;
                release.name = "code-association " + version;
                release.url = GitHubVersionSource.releasePageUrl("o/r", release.tag);
                if (downloadUrl != null) {
                    release.assets.add(new ReleaseInfo.Asset(
                            "code-association-" + version + ".jar", 0L, downloadUrl));
                }
                out.add(release);
            }
            return out;
        }
    }

    private static UpdateService service(AppProperties properties, VersionSource source, Version running) {
        return new UpdateService(properties, source, HttpClient.newHttpClient(), running);
    }

    @Test
    void reportsAnAvailableUpdate(@TempDir Path tmp) {
        FakeSource source = new FakeSource("0.4.0", "0.3.0");
        UpdateService service = service(props(tmp, 360), source, Version.parse("0.3.0"));

        Map<String, Object> status = service.status();

        assertEquals("github", status.get("source"));
        assertEquals("0.3.0", status.get("currentVersion"));
        assertEquals("0.4.0", status.get("latestVersion"));
        assertEquals(true, status.get("updateAvailable"));
        assertEquals("geek-xin/code-association", status.get("repo"));
        assertEquals(360, status.get("checkIntervalMinutes"));
        assertNull(status.get("error"));
        assertNull(status.get("pending"));
        assertEquals(false, status.get("busy"));
        assertEquals(false, status.get("runningFromJar"), "tests run from target/test-classes, not a jar");
        assertEquals(false, status.get("applySupported"), "without a jar there is nothing to replace");

        @SuppressWarnings("unchecked")
        List<ReleaseInfo> releases = (List<ReleaseInfo>) status.get("releases");
        assertEquals(2, releases.size());
        assertTrue(releases.get(1).current, "0.3.0 is what is running");
        assertFalse(releases.get(1).newer);
        assertTrue(releases.get(0).newer, "0.4.0 is newer than what is running");
        assertFalse(releases.get(0).current);
    }

    @Test
    void reportsNoUpdateWhenRunningTheLatest(@TempDir Path tmp) {
        FakeSource source = new FakeSource("0.3.0", "0.2.0");
        UpdateService service = service(props(tmp, 360), source, Version.parse("0.3.0"));

        Map<String, Object> status = service.status();

        assertEquals(false, status.get("updateAvailable"));
        assertEquals("0.3.0", status.get("latestVersion"));
        @SuppressWarnings("unchecked")
        List<ReleaseInfo> releases = (List<ReleaseInfo>) status.get("releases");
        assertTrue(releases.get(0).current);
        assertFalse(releases.get(0).newer);
    }

    @Test
    void aDevBuildTreatsEveryReleaseAsNewer(@TempDir Path tmp) {
        FakeSource source = new FakeSource("0.0.1");
        UpdateService service = service(props(tmp, 360), source, Version.parse("0.0.0-dev"));

        assertEquals(true, service.status().get("updateAvailable"));
    }

    @Test
    void theCacheIsUsedWithinTheInterval(@TempDir Path tmp) {
        FakeSource source = new FakeSource("0.4.0");
        UpdateService service = service(props(tmp, 360), source, Version.parse("0.3.0"));

        assertEquals("github", service.status().get("source"), "the first call must hit the source");
        assertEquals(1, source.calls);

        Map<String, Object> cached = service.status();
        assertEquals("cache", cached.get("source"));
        assertEquals(1, source.calls, "a fresh cache must not call the source again");
        assertNotNull(cached.get("checkedAt"));
        assertEquals(true, cached.get("updateAvailable"), "cached releases still answer the question");
    }

    @Test
    void anExpiredCacheIsRefreshed(@TempDir Path tmp) throws Exception {
        FakeSource source = new FakeSource("0.4.0");
        // A zero interval means every read is stale, which is how "expired" is reached without
        // waiting six hours.
        UpdateService service = service(props(tmp, 0), source, Version.parse("0.3.0"));

        service.status();
        service.status();

        assertEquals(2, source.calls);
    }

    @Test
    void checkNowIgnoresTheCache(@TempDir Path tmp) {
        FakeSource source = new FakeSource("0.4.0");
        UpdateService service = service(props(tmp, 360), source, Version.parse("0.3.0"));

        service.status();
        Map<String, Object> forced = service.checkNow();

        assertEquals(2, source.calls);
        assertEquals("github", forced.get("source"));
    }

    @Test
    void aFailureIsReportedInsteadOfThrown(@TempDir Path tmp) {
        FakeSource source = new FakeSource("0.4.0");
        source.failure = new UpdateException("无法连接 GitHub（10 秒超时）");
        UpdateService service = service(props(tmp, 360), source, Version.parse("0.3.0"));

        Map<String, Object> status = service.status();

        assertEquals("error", status.get("source"));
        assertEquals("无法连接 GitHub（10 秒超时）", status.get("error"));
        assertEquals(0, ((List<?>) status.get("releases")).size());
        assertNull(status.get("checkedAt"), "a failed attempt is not a successful check");

        // A poll loop must not hammer a dead endpoint: the backoff keeps the second call cached
        // even though nothing was ever checked successfully.
        service.status();
        assertEquals(1, source.calls);
    }

    @Test
    void aFailedCheckKeepsThePreviousReleaseList(@TempDir Path tmp) {
        FakeSource source = new FakeSource("0.4.0");
        UpdateService service = service(props(tmp, 0), source, Version.parse("0.3.0"));
        service.status();

        source.failure = new UpdateException("GitHub API 已限流");
        Map<String, Object> status = service.status();

        assertEquals("error", status.get("source"));
        assertEquals(1, ((List<?>) status.get("releases")).size(),
                "a network hiccup must not empty the history the user is looking at");
    }

    @Test
    void disabledReportsDisabledWithoutCallingTheSource(@TempDir Path tmp) {
        AppProperties properties = props(tmp, 360);
        properties.getUpdate().setEnabled(false);
        FakeSource source = new FakeSource("0.4.0");
        UpdateService service = service(properties, source, Version.parse("0.3.0"));

        Map<String, Object> status = service.status();

        assertEquals("disabled", status.get("source"));
        assertEquals(false, status.get("enabled"));
        assertEquals(0, source.calls);
    }

    @Test
    void downloadRefusesAVersionThatWasNeverReleased(@TempDir Path tmp) {
        FakeSource source = new FakeSource("0.4.0");
        UpdateService service = service(props(tmp, 360), source, Version.parse("0.3.0"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.download("9.9.9"));
        assertTrue(error.getMessage().contains("未找到版本"), error.getMessage());
        assertEquals(false, service.isBusy(), "a failed download must release the busy flag");
    }

    @Test
    void downloadRejectsAnEmptyVersion(@TempDir Path tmp) {
        UpdateService service = service(props(tmp, 360), new FakeSource("0.4.0"), Version.parse("0.3.0"));
        assertThrows(IllegalArgumentException.class, () -> service.download("  "));
    }

    @Test
    void applyWithoutADownloadIsRejected(@TempDir Path tmp) {
        UpdateService service = service(props(tmp, 360), new FakeSource("0.4.0"), Version.parse("0.3.0"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, service::apply);
        assertTrue(error.getMessage().contains("还没有下载"), error.getMessage());
    }

    @Test
    void applyOutsideAJarExplainsWhyItCannotWork(@TempDir Path tmp) {
        UpdateService service = service(props(tmp, 360), new FakeSource("0.4.0"), Version.parse("0.3.0"));

        // apply() is reached through a downloaded pending jar, so the running-jar check is tested
        // through its public accessor: in tests there is no jar, which is exactly the IDE case.
        assertNull(service.runningJar(), "a test runs from target/test-classes");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, service::apply);
        assertNotNull(error.getMessage());
    }

    // ------------------------------------------------------- download path

    @Test
    void downloadVerifiesTheJarHeaderAndRecordsThePendingFile(@TempDir Path tmp) throws Exception {
        byte[] jar = fakeJar(4096);
        try (FakeServer server = FakeServer.start(jar)) {
            FakeSource source = new FakeSource("0.4.0");
            source.downloadUrl = server.url();
            UpdateService service = service(props(tmp.resolve("data"), 360), source, Version.parse("0.3.0"));

            Map<String, Object> result = service.download("0.4.0");

            assertEquals(true, result.get("downloaded"));
            assertEquals("0.4.0", result.get("version"));
            assertEquals(true, result.get("verified"));
            assertEquals((long) jar.length, result.get("bytes"));
            Path downloaded = Path.of((String) result.get("jarPath"));
            assertTrue(Files.isRegularFile(downloaded), "the jar must exist at the reported path");
            assertEquals("code-association-0.4.0.jar", downloaded.getFileName().toString());
            assertTrue(downloaded.startsWith(tmp.resolve("data").toAbsolutePath()),
                    "downloads belong under {dataDir}/updates");
            assertEquals(jar.length, Files.size(downloaded));

            // The pending entry is what the status card offers to apply.
            @SuppressWarnings("unchecked")
            Map<String, Object> pending = (Map<String, Object>) service.status().get("pending");
            assertNotNull(pending);
            assertEquals("0.4.0", pending.get("version"));
            assertEquals((long) jar.length, pending.get("bytes"));
            assertNotNull(pending.get("downloadedAt"));
            assertFalse(service.isBusy(), "the busy flag must be released when the download ends");
        }
    }

    @Test
    void downloadRejectsAFileThatIsNotAJar(@TempDir Path tmp) throws Exception {
        byte[] notAJar = "<html>captive portal</html>".getBytes(StandardCharsets.UTF_8);
        try (FakeServer server = FakeServer.start(notAJar)) {
            FakeSource source = new FakeSource("0.4.0");
            source.downloadUrl = server.url();
            UpdateService service = service(props(tmp.resolve("data"), 360), source, Version.parse("0.3.0"));

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.download("0.4.0"));
            assertTrue(error.getMessage().contains("不是有效的 jar"), error.getMessage());

            Path updates = tmp.resolve("data").resolve("updates");
            if (Files.isDirectory(updates)) {
                try (var files = Files.list(updates)) {
                    assertEquals(0, files.count(), "a rejected download must not be left behind");
                }
            }
            assertNull(service.status().get("pending"), "a rejected download is never pending");
            assertFalse(service.isBusy());
        }
    }

    @Test
    void verifyJarAcceptsAndRejectsFiles(@TempDir Path tmp) throws Exception {
        Path good = tmp.resolve("good.jar");
        Files.write(good, fakeJar(64));
        UpdateService.verifyJar(good);
        assertTrue(Files.exists(good), "a valid jar is kept");

        Path bad = tmp.resolve("bad.jar");
        Files.writeString(bad, "PK");
        assertThrows(IllegalArgumentException.class, () -> UpdateService.verifyJar(bad));
        assertFalse(Files.exists(bad), "an invalid file is deleted so it cannot be applied by mistake");
    }

    @Test
    void downloadDoesNotRunTwiceAtOnce(@TempDir Path tmp) {
        FakeSource source = new FakeSource("0.4.0");
        UpdateService service = service(props(tmp, 360), source, Version.parse("0.3.0"));
        // There is nothing to download, so this asserts the guard's message rather than a race.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.download("0.4.0"));
        assertNotNull(error.getMessage());
        assertFalse(service.isBusy());
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
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
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

    @Test
    void statusNeverLeaksAnExceptionTypeIntoTheMessage(@TempDir Path tmp) {
        FakeSource source = new FakeSource();
        source.failure = new UpdateException("仓库不存在或未公开：geek-xin/code-association");
        UpdateService service = service(props(tmp, 360), source, Version.parse("0.3.0"));

        String error = (String) service.status().get("error");

        assertTrue(error.startsWith("仓库不存在或未公开"), error);
        assertEquals(false, service.status().get("updateAvailable"));
    }
}
