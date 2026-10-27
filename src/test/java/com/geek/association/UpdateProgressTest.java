package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.update.ReleaseInfo;
import com.geek.association.update.UpdateProgress;
import com.geek.association.update.UpdateService;
import com.geek.association.update.Version;
import com.geek.association.update.VersionSource;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The progress block behind the update card.
 *
 * The feature it covers is about visibility rather than capability: a background download that
 * could not be seen was indistinguishable from one that had hung, and a background failure was
 * only ever a log line. So the assertions are about the states being reachable and honest -
 * a byte count that advances, a failure that keeps its reason, and an "idle" that does not claim
 * something is happening.
 */
class UpdateProgressTest {

    // ------------------------------------------------------------- the value

    @Test
    void anIdleBlockSaysNothingIsHappening() {
        UpdateProgress progress = new UpdateProgress();

        Map<String, Object> view = progress.toMap();

        assertEquals(false, view.get("active"));
        assertEquals("idle", view.get("phase"));
        assertEquals(-1, view.get("percent"), "an unknown size must not read as 0% done");
        assertNull(view.get("version"));
        assertNull(view.get("error"));
    }

    @Test
    void percentFollowsTheByteCountAndStopsShortOfOneHundredWhileActive() {
        UpdateProgress progress = new UpdateProgress();
        progress.start(UpdateProgress.PHASE_DOWNLOADING, "0.6.0", true, 1000, "后台正在下载");

        progress.downloaded(250, 1000);
        assertEquals(25, progress.getPercent());
        assertEquals(true, progress.toMap().get("active"));

        // The last percent belongs to the phase that actually finishes: a bar sitting at 100%
        // while bytes are still arriving is the "is it stuck?" moment this exists to avoid.
        progress.downloaded(1000, 1000);
        assertEquals(99, progress.getPercent());
    }

    @Test
    void aTransferWithNoKnownSizeReportsNoPercentage() {
        UpdateProgress progress = new UpdateProgress();
        progress.start(UpdateProgress.PHASE_DOWNLOADING, "0.6.0", true, 0, "后台正在下载");
        progress.downloaded(4096, 0);

        assertEquals(-1, progress.getPercent(), "no total means no percentage, not a divide by zero");
        assertEquals(4096L, progress.toMap().get("bytes"));
    }

    @Test
    void aRetryResetsTheBytesButNotTheStartTime() {
        UpdateProgress progress = new UpdateProgress();
        progress.start(UpdateProgress.PHASE_DOWNLOADING, "0.6.0", false, 1000, "正在下载");
        progress.downloaded(700, 1000);
        Long startedAt = progress.getStartedAt();

        // A retried transfer discards the partial file, so the counter has to go back to zero -
        // but the run did not restart, and moving startedAt would make a two-minute download look
        // like it just began.
        progress.attemptStarted(1000, "正在下载（重试）");

        assertEquals(0L, progress.getBytes());
        assertEquals(startedAt, progress.getStartedAt());
        assertEquals(0, progress.getPercent());
    }

    @Test
    void aTerminalPhaseIsNotActiveAndKeepsTheByteCount() {
        UpdateProgress progress = new UpdateProgress();
        progress.start(UpdateProgress.PHASE_DOWNLOADING, "0.6.0", true, 1000, "后台正在下载");
        progress.downloaded(1000, 1000);

        progress.succeeded("已下载 0.6.0");

        assertEquals(false, progress.toMap().get("active"));
        assertEquals("succeeded", progress.getPhase());
        assertEquals(1000L, progress.toMap().get("bytes"), "the size stays for a 'downloaded N MB' line");
        assertEquals(100, progress.getPercent(), "a finished transfer may read 100%");
    }

    @Test
    void aFailureKeepsItsReasonAndStopsBeingActive() {
        UpdateProgress progress = new UpdateProgress();
        progress.start(UpdateProgress.PHASE_DOWNLOADING, "0.6.0", true, 1000, "后台正在下载");

        progress.failed("后台自动更新失败", "下载失败：无法连接 GitHub（10 秒超时）");

        Map<String, Object> view = progress.toMap();
        assertEquals(false, view.get("active"));
        assertEquals("failed", view.get("phase"));
        assertEquals("下载失败：无法连接 GitHub（10 秒超时）", view.get("error"));
        assertEquals("后台自动更新失败", view.get("message"));
    }

    @Test
    void aBlankErrorFallsBackToTheMessageSoTheCardNeverRendersAnEmptyReason() {
        UpdateProgress progress = new UpdateProgress();
        progress.failed("应用更新失败", null);

        assertEquals("应用更新失败", progress.toMap().get("error"));
    }

    @Test
    void startingAgainClearsThePreviousOutcome() {
        UpdateProgress progress = new UpdateProgress();
        progress.start(UpdateProgress.PHASE_DOWNLOADING, "0.6.0", true, 1000, "后台正在下载");
        progress.failed("下载失败", "连接超时");

        progress.start(UpdateProgress.PHASE_DOWNLOADING, "0.7.0", true, 2000, "后台正在下载");

        Map<String, Object> view = progress.toMap();
        assertNull(view.get("error"), "a stale failure must not decorate a run that is going fine");
        assertEquals("0.7.0", view.get("version"));
        assertEquals(true, view.get("active"));
    }

    // ----------------------------------------------------------- the wiring

    @Test
    void statusCarriesTheProgressBlockEvenWhenNothingRan(@TempDir Path tmp) {
        UpdateService service = service(props(tmp), new FakeSource("0.9.0", 64));

        @SuppressWarnings("unchecked")
        Map<String, Object> progress = (Map<String, Object>) service.status().get("progress");

        assertNotNull(progress, "the card renders this unconditionally");
        assertEquals("idle", progress.get("phase"));
        assertEquals(false, progress.get("active"));
    }

    @Test
    void aCompletedDownloadLeavesSucceededWithTheVersionAndSize(@TempDir Path tmp) throws Exception {
        byte[] payload = jarBytes(2048);
        try (Cdn cdn = Cdn.start(payload)) {
            UpdateService service = service(props(tmp), new FakeSource("0.9.0", payload.length, cdn.url()));
            service.status(); // populates the release list the download resolves against

            service.download("0.9.0");

            Map<String, Object> progress = service.progressState();
            assertEquals("succeeded", progress.get("phase"));
            assertEquals(false, progress.get("active"));
            assertEquals("0.9.0", progress.get("version"));
            assertEquals((long) payload.length, progress.get("bytes"));
            assertEquals(100, progress.get("percent"));
        }
    }

    @Test
    void aFailedDownloadIsRecordedInsteadOfOnlyLogged(@TempDir Path tmp) {
        // Nothing listens on this port, so the transfer fails at connect time - the same shape as
        // the real failure (offline, blocked CDN) and the one that used to leave no trace at all.
        UpdateService service = service(props(tmp), new FakeSource("0.9.0", 64, "http://127.0.0.1:1/x.jar"));
        service.status();

        assertThrows(IllegalArgumentException.class, () -> service.download("0.9.0"));

        Map<String, Object> progress = service.progressState();
        assertEquals("failed", progress.get("phase"));
        assertEquals(false, progress.get("active"));
        assertNotNull(progress.get("error"), "a failure the user cannot see is a failure they cannot act on");
        String error = (String) progress.get("error");
        assertFalse(error.isBlank());
        // The card shows this verbatim, so it has to name the situation rather than a Java class.
        // "下载失败：ConnectException" was the first version of this and told the user nothing.
        assertFalse(error.contains("Exception"), error);
        assertTrue(error.contains("无法连接") || error.contains("超时") || error.contains("域名"), error);
    }

    @Test
    void theBlockIsActiveWhileTheTransferIsInFlight(@TempDir Path tmp) throws Exception {
        // A server that answers headers and then stalls mid-body: this is the only state the UI
        // actually polls for, and it cannot be observed after a fast local download completes.
        byte[] payload = jarBytes(64 * 1024);
        CountDownLatch release = new CountDownLatch(1);
        try (Cdn cdn = Cdn.stalling(payload, release)) {
            UpdateService service = service(props(tmp), new FakeSource("0.9.0", payload.length, cdn.url()));
            service.status();

            Thread download = new Thread(() -> service.download("0.9.0"));
            download.setDaemon(true);
            download.start();

            Map<String, Object> progress = awaitActive(service, download);
            assertEquals("downloading", progress.get("phase"));
            assertEquals(true, progress.get("active"));
            assertEquals("0.9.0", progress.get("version"));
            assertEquals((long) payload.length, progress.get("totalBytes"),
                    "the size comes from the release asset before a single byte is read");

            release.countDown();
            download.join(10_000);
        }
    }

    private static Map<String, Object> awaitActive(UpdateService service, Thread download) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> progress = service.progressState();
            if (Boolean.TRUE.equals(progress.get("active"))) {
                return progress;
            }
            if (!download.isAlive()) {
                break;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the pipeline never reported itself as active");
    }

    // ------------------------------------------------------------- helpers

    private static AppProperties props(Path dataDir) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(dataDir.toString());
        properties.getUpdate().setCheckIntervalMinutes(360);
        properties.getUpdate().setRetries(0);
        return properties;
    }

    private static UpdateService service(AppProperties properties, VersionSource source) {
        return new UpdateService(properties, source, HttpClient.newHttpClient(), Version.parse("0.5.0"));
    }

    /** A file that starts like a jar, because the download path checks the magic before moving it. */
    private static byte[] jarBytes(int size) {
        byte[] bytes = new byte[size];
        bytes[0] = 'P';
        bytes[1] = 'K';
        bytes[2] = 3;
        bytes[3] = 4;
        for (int i = 4; i < size; i++) {
            bytes[i] = (byte) (i % 251);
        }
        return bytes;
    }

    private static final class FakeSource implements VersionSource {
        private final String version;
        private final long size;
        private final String downloadUrl;

        FakeSource(String version, long size) {
            this(version, size, null);
        }

        FakeSource(String version, long size, String downloadUrl) {
            this.version = version;
            this.size = size;
            this.downloadUrl = downloadUrl;
        }

        @Override
        public List<ReleaseInfo> fetch() {
            ReleaseInfo release = new ReleaseInfo();
            release.version = version;
            release.tag = "v" + version;
            if (downloadUrl != null) {
                release.assets.add(new ReleaseInfo.Asset("code-association-" + version + ".jar",
                        size, downloadUrl));
            }
            List<ReleaseInfo> out = new ArrayList<>();
            out.add(release);
            return out;
        }
    }

    /** A loopback asset host: fast by default, or deliberately slow for the in-flight case. */
    private static final class Cdn implements AutoCloseable {
        private final HttpServer server;
        private final int port;

        private Cdn(HttpServer server) {
            this.server = server;
            this.port = server.getAddress().getPort();
        }

        static Cdn start(byte[] payload) throws IOException {
            return serve(payload, null);
        }

        static Cdn stalling(byte[] payload, CountDownLatch release) throws IOException {
            return serve(payload, release);
        }

        private static Cdn serve(byte[] payload, CountDownLatch release) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    if (release == null) {
                        out.write(payload);
                        return;
                    }
                    // Write a little, then hold the connection open: enough for the progress block
                    // to be observably active, short enough that the test does not wait on a stall.
                    out.write(payload, 0, 1024);
                    out.flush();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    out.write(payload, 1024, payload.length - 1024);
                }
            });
            server.setExecutor(null);
            server.start();
            return new Cdn(server);
        }

        String url() {
            return "http://127.0.0.1:" + port + "/code-association.jar";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
