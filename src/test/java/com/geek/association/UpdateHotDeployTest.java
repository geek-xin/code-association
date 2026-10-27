package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.update.ReleaseInfo;
import com.geek.association.update.RestartSupervisor;
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
 * The parts of the update lifecycle that only exist once a restart and a mirror are in play: the
 * download URL rewrite, the runtime toggles, and the decision to hand a completed apply to the
 * supervisor.
 *
 * The supervisor is a recording stub rather than the real one: this test is about what
 * {@link UpdateService} decides and reports, and starting a real process would occupy a port and
 * outlive the run. The download goes to a loopback server for the same reason.
 */
class UpdateHotDeployTest {

    // ---------------------------------------------------------------- mirror

    @Test
    void aConfiguredMirrorIsUsedForTheDownload(@TempDir Path root) throws Exception {
        try (FakeCdn origin = FakeCdn.start(); FakeCdn mirror = FakeCdn.start()) {
            AppProperties properties = props(root);
            properties.getUpdate().setDownloadMirror(mirror.baseUrl());
            UpdateService service = service(properties, new StubSource("0.9.0", origin.assetUrl()));

            service.download("0.9.0");

            assertEquals(1, mirror.paths.size(), "the request goes to the mirror");
            assertEquals(0, origin.paths.size(), "and never to the blocked origin");
            // The convention every ghproxy-style mirror shares is "prefix the absolute URL", so the
            // path the mirror receives still carries the origin's scheme.
            assertTrue(mirror.paths.get(0).startsWith("/" + origin.baseUrl()),
                    mirror.paths.get(0));
            assertTrue(Files.isRegularFile(root.resolve("data").resolve("updates")
                    .resolve("code-association-0.9.0.jar")));
        }
    }

    @Test
    void withoutAMirrorTheAssetIsFetchedFromItsOwnHost(@TempDir Path root) throws Exception {
        try (FakeCdn cdn = FakeCdn.start()) {
            UpdateService service = service(props(root), new StubSource("0.9.0", cdn.assetUrl()));

            service.download("0.9.0");

            assertEquals(List.of("/code-association-0.9.0.jar"), cdn.paths,
                    "without a mirror the asset is fetched from the host the release names");
        }
    }

    @Test
    void aTruncatedTransferIsRetriedBeforeItIsReported(@TempDir Path root) throws Exception {
        try (FakeCdn cdn = FakeCdn.startTruncatingOnce()) {
            AppProperties properties = props(root);
            properties.getUpdate().setRetries(2);
            properties.getUpdate().setRetryBaseDelayMs(1);
            UpdateService service = service(properties, new StubSource("0.9.0", cdn.assetUrl()));

            Map<String, Object> result = service.download("0.9.0");

            assertEquals(true, result.get("downloaded"));
            assertEquals(2, cdn.paths.size(), "the short read is retried, not surfaced");
        }
    }

    @Test
    void aMissingAssetIsNotRetried(@TempDir Path root) throws Exception {
        try (FakeCdn cdn = FakeCdn.startMissing()) {
            AppProperties properties = props(root);
            properties.getUpdate().setRetries(3);
            properties.getUpdate().setRetryBaseDelayMs(1);
            UpdateService service = service(properties, new StubSource("0.9.0", cdn.assetUrl()));

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.download("0.9.0"));

            assertTrue(error.getMessage().contains("404"), error.getMessage());
            assertEquals(1, cdn.paths.size(), "a 404 cannot become a 200 on the next attempt");
        }
    }

    // --------------------------------------------------------------- restart

    @Test
    void applyHandsTheRestartToTheSupervisor(@TempDir Path root) throws Exception {
        try (FakeCdn cdn = FakeCdn.start()) {
            AppProperties properties = props(root);
            RecordingSupervisor supervisor = new RecordingSupervisor(properties, true);
            TestUpdateService service = service(properties, new StubSource("0.9.0", cdn.assetUrl()),
                    supervisor, root);
            stage(service, root);

            Map<String, Object> result = service.apply();

            assertEquals(true, result.get("applied"));
            assertEquals(true, result.get("restarting"));
            assertEquals(false, result.get("restartRequired"),
                    "the operator has nothing left to do when a restart is already scheduled");
            assertEquals(List.of("0.9.0"), supervisor.scheduled);
        }
    }

    @Test
    void applyFallsBackToAManualRestartWhenAutoRestartIsOff(@TempDir Path root) throws Exception {
        try (FakeCdn cdn = FakeCdn.start()) {
            AppProperties properties = props(root);
            properties.getUpdate().setAutoRestart(false);
            RecordingSupervisor supervisor = new RecordingSupervisor(properties, true);
            TestUpdateService service = service(properties, new StubSource("0.9.0", cdn.assetUrl()),
                    supervisor, root);
            stage(service, root);

            Map<String, Object> result = service.apply();

            assertEquals(true, result.get("restartRequired"));
            assertEquals(false, result.get("restarting"));
            assertEquals(List.of(), supervisor.scheduled);
        }
    }

    @Test
    void applyFallsBackToAManualRestartWhenTheSupervisorCannotRun(@TempDir Path root) throws Exception {
        try (FakeCdn cdn = FakeCdn.start()) {
            AppProperties properties = props(root);
            RecordingSupervisor supervisor = new RecordingSupervisor(properties, false);
            TestUpdateService service = service(properties, new StubSource("0.9.0", cdn.assetUrl()),
                    supervisor, root);
            stage(service, root);

            Map<String, Object> result = service.apply();

            assertEquals(true, result.get("restartRequired"));
            assertEquals(false, result.get("restarting"));
            assertEquals(List.of(), supervisor.scheduled, "an unsupported supervisor is never asked");
        }
    }

    @Test
    void rollbackAlsoRestarts(@TempDir Path root) throws Exception {
        try (FakeCdn cdn = FakeCdn.start()) {
            AppProperties properties = props(root);
            RecordingSupervisor supervisor = new RecordingSupervisor(properties, true);
            TestUpdateService service = service(properties, new StubSource("0.9.0", cdn.assetUrl()),
                    supervisor, root);
            stage(service, root);
            service.apply();

            Map<String, Object> result = service.rollback();

            assertEquals(true, result.get("rolledBack"));
            assertEquals(true, result.get("restarting"));
            assertEquals(2, supervisor.scheduled.size(), "the apply and the rollback each asked once");
        }
    }

    @Test
    void withoutASupervisorTheStatusStillRenders(@TempDir Path root) {
        UpdateService service = new UpdateService(props(root), new StubSource("0.9.0", null),
                HttpClient.newHttpClient(), Version.parse("0.3.0"));

        Map<String, Object> status = service.status();

        @SuppressWarnings("unchecked")
        Map<String, Object> restart = (Map<String, Object>) status.get("restart");
        assertNotNull(restart, "the card reads this block unconditionally");
        assertEquals(false, restart.get("supported"));
        assertNotNull(restart.get("unsupportedReason"));
        assertEquals("idle", restart.get("phase"));
    }

    // -------------------------------------------------------------- settings

    @Test
    void settingsToggleTheAutomaticBehaviours(@TempDir Path root) {
        AppProperties properties = props(root);
        UpdateService service = service(properties, new StubSource("0.9.0", null));

        Map<String, Object> result = service.saveSettings(Map.of("autoDownload", true));

        assertEquals(true, result.get("autoDownload"));
        assertEquals(true, properties.getUpdate().isAutoDownload());
        // Absent keys are left alone, so the UI can send only the switch that moved.
        assertEquals(properties.getUpdate().isAutoRestart(), result.get("autoRestart"));
    }

    @Test
    void autoApplyWithoutAutoRestartIsRefused(@TempDir Path root) {
        AppProperties properties = props(root);
        properties.getUpdate().setAutoRestart(false);
        UpdateService service = service(properties, new StubSource("0.9.0", null));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.saveSettings(Map.of("autoApply", true)));

        assertTrue(error.getMessage().contains("自动重启"), error.getMessage());
        assertFalse(properties.getUpdate().isAutoApply(),
                "the rejected value must not be left switched on");
    }

    @Test
    void aStringBooleanIsAcceptedFromAHandWrittenRequest(@TempDir Path root) {
        AppProperties properties = props(root);
        UpdateService service = service(properties, new StubSource("0.9.0", null));

        service.saveSettings(Map.of("autoDownload", "true"));

        assertEquals(true, properties.getUpdate().isAutoDownload());
    }

    @Test
    void theNetworkBlockExplainsHowTheNextCheckWillConnect(@TempDir Path root) {
        AppProperties properties = props(root);
        properties.getUpdate().setDownloadMirror("https://ghfast.top");
        properties.getUpdate().setProxy("http://127.0.0.1:7890");
        properties.getUpdate().setRetries(4);
        UpdateService service = service(properties, new StubSource("0.9.0", null));

        @SuppressWarnings("unchecked")
        Map<String, Object> network = (Map<String, Object>) service.status().get("network");

        assertEquals("https://ghfast.top", network.get("downloadMirror"));
        assertEquals(true, network.get("proxyConfigured"));
        assertEquals(4, network.get("retries"));
        assertNull(network.get("rateLimitedUntil"), "nothing has been rate limited yet");
        assertEquals(false, network.get("downloadBlocked"),
                "assets come from a CDN that does not consume the API quota");
    }

    // ---------------------------------------------------------------- fixture

    private static AppProperties props(Path root) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(root.resolve("data").toString());
        properties.getUpdate().setRepo("o/r");
        properties.getUpdate().setCheckIntervalMinutes(0);
        properties.getUpdate().setMaxReleases(20);
        properties.getUpdate().setAutoRestart(true);
        return properties;
    }

    private static UpdateService service(AppProperties properties, VersionSource source) {
        return new UpdateService(properties, source, HttpClient.newHttpClient(), Version.parse("0.3.0"));
    }

    private static TestUpdateService service(AppProperties properties, VersionSource source,
                                             RestartSupervisor supervisor, Path root) {
        return new TestUpdateService(properties, source, supervisor, root);
    }

    /**
     * An UpdateService whose "running jar" is a file the test owns.
     *
     * The production lookup goes through the class loader, which in a test run resolves to a
     * directory and makes {@code apply()} correctly refuse. Overriding the one seam is what lets
     * the swap itself be exercised without installing anything.
     */
    private static final class TestUpdateService extends UpdateService {
        private final Path jar;

        TestUpdateService(AppProperties properties, VersionSource source,
                          RestartSupervisor supervisor, Path root) {
            super(properties, source, HttpClient.newHttpClient(), Version.parse("0.3.0"), supervisor);
            this.jar = root.resolve("app").resolve("code-association.jar");
        }

        @Override
        public Path runningJar() {
            return jar;
        }
    }

    /**
     * Puts the installation in the state apply() expects: a jar on disk and a download staged.
     *
     * The staging goes through the real {@link UpdateService#download(String)} rather than through
     * reflection, so the test exercises the same "download, verify, remember as pending" sequence a
     * user does. Only the running jar is faked, through the seam {@link #runningJar()} already
     * provides - in a test there is no jar, and apply() correctly refuses without one.
     */
    private static void stage(TestUpdateService service, Path root) throws IOException {
        Path jar = root.resolve("app").resolve("code-association.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "old build", StandardCharsets.UTF_8);
        service.download("0.9.0");
    }

    /** A minimal zip header, which is all verifyJar() and apply() look at. */
    private static byte[] jarBytes() {
        return new byte[] {0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0};
    }

    /** Records what it was asked to do instead of starting a process. */
    private static final class RecordingSupervisor extends RestartSupervisor {
        private final boolean supported;
        final List<String> scheduled = new ArrayList<>();

        RecordingSupervisor(AppProperties properties, boolean supported) {
            // The public constructor is enough: this stub overrides every method the service calls,
            // so the launcher and probe it would build are never reached.
            super(properties, new UpdateHistory(properties), 9996);
            this.supported = supported;
        }

        @Override
        public boolean isSupported() {
            return supported;
        }

        @Override
        public String unsupportedReason() {
            return supported ? null : "test supervisor is not supported";
        }

        @Override
        public boolean scheduleRestart(String appliedVersion) {
            if (!supported) {
                return false;
            }
            scheduled.add(appliedVersion);
            return true;
        }
    }

    /** A release list with one version, optionally carrying a jar asset. */
    private static final class StubSource implements VersionSource {
        private final String version;
        private final String downloadUrl;

        StubSource(String version, String downloadUrl) {
            this.version = version;
            this.downloadUrl = downloadUrl;
        }

        @Override
        public List<ReleaseInfo> fetch() {
            ReleaseInfo release = new ReleaseInfo();
            release.version = version;
            release.tag = "v" + version;
            release.name = "code-association " + version;
            release.url = "https://example.test/releases/" + version;
            if (downloadUrl != null) {
                release.assets.add(new ReleaseInfo.Asset(
                        "code-association-" + version + ".jar", 0L, downloadUrl));
            }
            return List.of(release);
        }
    }

    /** A loopback CDN that records the path of every request, including a mirror prefix. */
    private static final class FakeCdn implements AutoCloseable {
        private final HttpServer server;
        final List<String> paths = new ArrayList<>();
        private final int status;
        private final int truncateFirst;

        private FakeCdn(HttpServer server, int status, int truncateFirst) {
            this.server = server;
            this.status = status;
            this.truncateFirst = truncateFirst;
        }

        static FakeCdn start() throws IOException {
            return create(200, 0);
        }

        static FakeCdn startTruncatingOnce() throws IOException {
            return create(200, 1);
        }

        static FakeCdn startMissing() throws IOException {
            return create(404, 0);
        }

        private static FakeCdn create(int status, int truncateFirst) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeCdn cdn = new FakeCdn(server, status, truncateFirst);
            server.createContext("/", exchange -> {
                cdn.paths.add(exchange.getRequestURI().toString());
                if (cdn.status != 200) {
                    byte[] body = "missing".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(cdn.status, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                    return;
                }
                byte[] body = jarBytes();
                // The first answer claims a longer body than it sends, which is what a dropped
                // connection looks like to the client; the retry gets the full one.
                boolean truncate = cdn.truncateFirst > 0 && cdn.paths.size() == 1;
                exchange.sendResponseHeaders(200, body.length + (truncate ? 100 : 0));
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return cdn;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        /** The asset URL the release would advertise: this server, so the test never needs DNS. */
        String assetUrl() {
            return baseUrl() + "/code-association-0.9.0.jar";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
