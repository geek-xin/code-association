package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.update.ReleaseInfo;
import com.geek.association.update.UpdateService;
import com.geek.association.update.Version;
import com.geek.association.update.VersionSource;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dangerous half of the feature: replacing the jar the process is running from.
 *
 * Everything here works on a temporary directory that stands in for the installation, so the real
 * jar is never touched, and the whole flow - download, apply, roll back - is exercised without a
 * network or a second process. The running jar is injected by overriding {@link UpdateService#runningJar()},
 * which is the single seam through which the service learns where it lives.
 */
class UpdateApplyRollbackTest {

    private static AppProperties props(Path dataDir) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(dataDir.toString());
        return properties;
    }

    /** A service whose "running jar" is a file in the test's temporary directory. */
    private static final class Installed extends UpdateService {
        private final Path jar;

        Installed(AppProperties properties, VersionSource source, Path jar) {
            super(properties, source, HttpClient.newHttpClient(), Version.parse("0.3.0"));
            this.jar = jar;
        }

        @Override
        public Path runningJar() {
            return jar;
        }
    }

    private static final class FakeSource implements VersionSource {
        final String downloadUrl;

        FakeSource(String downloadUrl) {
            this.downloadUrl = downloadUrl;
        }

        @Override
        public List<ReleaseInfo> fetch() {
            ReleaseInfo release = new ReleaseInfo();
            release.version = "0.4.0";
            release.tag = "v0.4.0";
            release.name = "code-association 0.4.0";
            release.assets.add(new ReleaseInfo.Asset("code-association-0.4.0.jar", 0L, downloadUrl));
            return List.of(release);
        }
    }

    private static final class FakeServer implements AutoCloseable {
        private final HttpServer server;

        private FakeServer(HttpServer server) {
            this.server = server;
        }

        static FakeServer start(byte[] body) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/new.jar", exchange -> {
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return new FakeServer(server);
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/new.jar";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static byte[] bytes(String content, int size) {
        byte[] body = new byte[size];
        byte[] head = content.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(head, 0, body, 0, Math.min(head.length, size));
        return body;
    }

    /** Windows locks a running jar, so apply refuses there; the test only makes sense elsewhere. */
    private static void skipOnWindows() {
        Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"),
                "Windows cannot replace a running jar");
    }

    @Test
    void applyReplacesTheJarAndKeepsABackup(@TempDir Path tmp) throws Exception {
        skipOnWindows();
        byte[] currentJar = bytes("PK\u0003\u0004 old build", 256);
        byte[] newJar = bytes("PK\u0003\u0004 new build", 512);
        Path installed = tmp.resolve("app").resolve("code-association.jar");
        Files.createDirectories(installed.getParent());
        Files.write(installed, currentJar);

        try (FakeServer server = FakeServer.start(newJar)) {
            UpdateService service = new Installed(props(tmp.resolve("data")), new FakeSource(server.url()), installed);
            service.download("0.4.0");

            Map<String, Object> result = service.apply();

            assertEquals(true, result.get("applied"));
            assertEquals("0.4.0", result.get("version"));
            assertEquals(true, result.get("restartRequired"), "the process never restarts itself");
            assertArrayEquals(newJar, Files.readAllBytes(installed), "the jar must be the downloaded one");

            Path backup = Path.of((String) result.get("backupPath"));
            assertEquals(installed.getParent(), backup.getParent(), "the backup lives beside the jar");
            assertEquals("code-association.jar.bak-0.3.0", backup.getFileName().toString());
            assertArrayEquals(currentJar, Files.readAllBytes(backup), "the backup is the previous jar");

            assertEquals(installed.toAbsolutePath().toString(), result.get("jarPath"));
            assertFalse(service.isBusy(), "the busy flag must be released when apply ends");
        }
    }

    @Test
    void rollbackRestoresTheMostRecentBackup(@TempDir Path tmp) throws Exception {
        skipOnWindows();
        byte[] original = bytes("PK\u0003\u0004 original", 256);
        byte[] updated = bytes("PK\u0003\u0004 updated", 512);
        Path installed = tmp.resolve("code-association.jar");
        Files.write(installed, original);

        try (FakeServer server = FakeServer.start(updated)) {
            UpdateService service = new Installed(props(tmp.resolve("data")), new FakeSource(server.url()), installed);
            service.download("0.4.0");
            service.apply();
            assertArrayEquals(updated, Files.readAllBytes(installed));

            Map<String, Object> rolledBack = service.rollback();

            assertEquals(true, rolledBack.get("rolledBack"));
            assertEquals("0.3.0", rolledBack.get("version"), "the backup name carries the old version");
            assertEquals(true, rolledBack.get("restartRequired"));
            assertArrayEquals(original, Files.readAllBytes(installed), "rollback must restore the old jar");
        }
    }

    @Test
    void rollbackWithoutABackupSaysSoInsteadOfFailingSilently(@TempDir Path tmp) throws Exception {
        skipOnWindows();
        Path installed = tmp.resolve("code-association.jar");
        Files.write(installed, bytes("PK\u0003\u0004 only build", 64));
        UpdateService service = new Installed(props(tmp.resolve("data")), new FakeSource("http://127.0.0.1:1/x"), installed);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, service::rollback);

        assertTrue(error.getMessage().contains("没有找到可回滚的备份"), error.getMessage());
        assertArrayEquals(bytes("PK\u0003\u0004 only build", 64), Files.readAllBytes(installed),
                "a failed rollback must leave the jar untouched");
    }

    @Test
    void applyRefusesWhenTheDownloadedFileIsGone(@TempDir Path tmp) throws Exception {
        skipOnWindows();
        Path installed = tmp.resolve("code-association.jar");
        byte[] original = bytes("PK\u0003\u0004 current", 128);
        Files.write(installed, original);

        try (FakeServer server = FakeServer.start(bytes("PK\u0003\u0004 next", 256))) {
            UpdateService service = new Installed(props(tmp.resolve("data")), new FakeSource(server.url()), installed);
            Map<String, Object> download = service.download("0.4.0");
            // Someone (or a cleanup job) removed the file between download and apply.
            Files.delete(Path.of((String) download.get("jarPath")));

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, service::apply);

            assertTrue(error.getMessage().contains("已不存在"), error.getMessage());
            assertArrayEquals(original, Files.readAllBytes(installed), "the running jar must be untouched");
        }
    }

    @Test
    void applyWithoutADownloadIsRejectedAndLeavesTheJarAlone(@TempDir Path tmp) throws Exception {
        skipOnWindows();
        Path installed = tmp.resolve("code-association.jar");
        byte[] original = bytes("PK\u0003\u0004 current", 128);
        Files.write(installed, original);
        UpdateService service = new Installed(props(tmp.resolve("data")), new FakeSource("http://127.0.0.1:1/x"), installed);

        assertThrows(IllegalArgumentException.class, service::apply);

        assertArrayEquals(original, Files.readAllBytes(installed));
    }

    @Test
    void statusReportsApplySupportOnlyWhenAJarIsPresent(@TempDir Path tmp) throws Exception {
        skipOnWindows();
        Path installed = tmp.resolve("code-association.jar");
        Files.write(installed, bytes("PK\u0003\u0004 current", 64));
        UpdateService withJar = new Installed(props(tmp.resolve("data")), new FakeSource("http://127.0.0.1:1/x"), installed);

        assertEquals(true, withJar.status().get("runningFromJar"));
        assertEquals(true, withJar.status().get("applySupported"));

        // The IDE case: no jar, so the UI must not offer a button that can only fail.
        UpdateService withoutJar = new UpdateService(props(tmp.resolve("data2")),
                new FakeSource("http://127.0.0.1:1/x"), HttpClient.newHttpClient(), Version.parse("0.3.0"));
        assertEquals(false, withoutJar.status().get("runningFromJar"));
        assertEquals(false, withoutJar.status().get("applySupported"));
    }
}
