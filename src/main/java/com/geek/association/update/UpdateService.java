package com.geek.association.update;

import com.geek.association.config.AppProperties;
import com.geek.association.util.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Orchestrates the update lifecycle: check, download, replace, roll back.
 *
 * Three properties are deliberate and worth keeping when this class is touched:
 *
 * <ul>
 *   <li><b>Checking never throws at the caller.</b> A failed GitHub request is a normal state of
 *       the world (offline, rate limited), so it is recorded in the status and shown in the UI
 *       instead of bubbling out of a GET handler;</li>
 *   <li><b>The process never restarts itself.</b> Applying copies the new jar over the running
 *       one and reports {@code restartRequired}; the restart belongs to start.sh/stop.sh. Killing
 *       the JVM from inside the request that asked for it would drop the HTTP response and leave
 *       the UI showing a failed update that actually succeeded;</li>
 *   <li><b>State is in memory plus one directory.</b> Update state is transient by nature: after a
 *       restart the running jar is the truth, so a table would only be able to go stale.</li>
 * </ul>
 */
@Service
public class UpdateService {

    private static final Logger log = LoggerFactory.getLogger(UpdateService.class);

    /** A jar (and a zip) starts with the local file header magic "PK\x03\x04". */
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);
    private static final String BACKUP_MARKER = ".bak-";
    /** A failed check is retried after this delay, so a poll loop cannot hammer a dead endpoint. */
    private static final long FAILURE_RETRY_SECONDS = 60;

    private final AppProperties properties;
    private final VersionSource versionSource;
    private final HttpClient downloadClient;
    private final Version runningVersion;

    /**
     * When this process started, in epoch millis.
     *
     * Reported to the UI so it can distinguish "the jar was replaced but you have not restarted
     * yet" from "you already restarted": the running version cannot answer that, because a JVM
     * keeps reporting the version it booted with no matter what is on disk. Epoch millis rather
     * than an ISO string so it is directly comparable with the {@code swappedAt} returned by
     * apply/rollback, without either side reinterpreting a timestamp format.
     */
    private final long startedAt = System.currentTimeMillis();

    /** Guards against two downloads or two applies running at the same time. */
    private final AtomicBoolean working = new AtomicBoolean(false);

    /** Serialises the read-modify-write of {@link #snapshot}; short, never held during I/O. */
    private final Object snapshotLock = new Object();

    private volatile Snapshot snapshot;
    private volatile Pending pending;

    /**
     * The production constructor. {@code @Autowired} is required because the class has a second
     * constructor for tests; without it Spring looks for a no-arg constructor and fails to start.
     */
    @Autowired
    public UpdateService(AppProperties properties, VersionSource versionSource) {
        this(properties, versionSource, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), Version.running());
    }

    /**
     * Full form, used by the tests and by any caller that needs a fixed client or running version.
     *
     * A pinned running version is what makes "there is no update" testable without installing
     * anything, and a local client is what lets the download path be exercised offline.
     */
    public UpdateService(AppProperties properties, VersionSource versionSource, HttpClient downloadClient,
                         Version runningVersion) {
        this.properties = properties;
        this.versionSource = versionSource;
        this.downloadClient = downloadClient;
        this.runningVersion = runningVersion;
    }

    // ---------------------------------------------------------------- status

    /**
     * Current update state, served from the cache while it is still fresh.
     *
     * The cache exists because the UI polls this endpoint and GitHub allows 60 anonymous requests
     * per hour: a poll every few seconds would exhaust the quota within minutes.
     */
    public Map<String, Object> status() {
        AppProperties.Update update = properties.getUpdate();
        if (!update.isEnabled()) {
            return view("disabled", List.of(), null, null);
        }
        Snapshot current = snapshot;
        if (current == null || shouldRefresh(current)) {
            Snapshot fresh = refresh();
            return view(fresh.error == null ? "github" : "error",
                    fresh.releases, fresh.checkedAt, fresh.error);
        }
        return view("cache", current.releases, current.checkedAt, current.error);
    }

    /** Forces a network round trip, ignoring the cache. */
    public Map<String, Object> checkNow() {
        if (!properties.getUpdate().isEnabled()) {
            return view("disabled", List.of(), null, null);
        }
        Snapshot fresh = refresh();
        return view(fresh.error == null ? "github" : "error", fresh.releases, fresh.checkedAt, fresh.error);
    }

    private Map<String, Object> view(String source, List<ReleaseInfo> releases, Instant checkedAt, String error) {
        AppProperties.Update update = properties.getUpdate();
        List<ReleaseInfo> list = releases == null ? List.of() : releases;
        Version latest = null;
        for (ReleaseInfo release : list) {
            Version version = Version.tryParse(release.version);
            // Derived flags are recomputed on every view instead of being stored: they depend on
            // the running version, and a cached release must not carry flags from another build.
            // Writing them onto the shared cached object is safe because the value depends only on
            // the running version, which cannot change while the process lives: every writer
            // stores the same boolean.
            release.current = version != null && version.compareTo(runningVersion) == 0;
            release.newer = version != null && version.isNewerThan(runningVersion);
            if (latest == null && version != null) {
                latest = version;
            }
        }
        Path jar = runningJar();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", update.isEnabled());
        out.put("repo", update.getRepo());
        out.put("currentVersion", runningVersion.toString());
        // When this process started. The UI needs it to tell "the restart already happened" from
        // "the file was swapped but you are still running the old code": comparing versions cannot
        // answer that, because a process keeps reporting the version it booted with, and
        // re-applying the version you already run would look identical to a completed restart.
        out.put("startedAt", startedAt);
        out.put("latestVersion", latest == null ? null : latest.toString());
        out.put("updateAvailable", latest != null && latest.isNewerThan(runningVersion));
        out.put("checkedAt", checkedAt == null ? null : checkedAt.toString());
        out.put("checkIntervalMinutes", update.getCheckIntervalMinutes());
        out.put("source", source);
        out.put("error", error);
        out.put("runningFromJar", jar != null);
        // Without a jar to overwrite there is nothing to apply; saying so up front is friendlier
        // than letting the user click a button that can only fail.
        out.put("applySupported", jar != null && !isWindows());
        out.put("busy", working.get());
        out.put("pending", pendingView());
        out.put("releases", list);
        return out;
    }

    private Map<String, Object> pendingView() {
        Pending current = pending;
        if (current == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", current.version);
        out.put("jarPath", current.jarPath);
        out.put("bytes", current.bytes);
        out.put("downloadedAt", current.downloadedAt);
        return out;
    }

    private boolean shouldRefresh(Snapshot current) {
        Instant now = Instant.now();
        // A non-positive interval means "never serve from the cache". It exists so a caller can
        // force freshness without a code change, and the tests use it instead of waiting hours.
        long interval = properties.getUpdate().getCheckIntervalMinutes();
        Instant base = current.checkedAt != null ? current.checkedAt : current.attemptedAt;
        if (interval > 0 && Duration.between(base, now).toMinutes() < interval) {
            return false;
        }
        // The interval already elapsed, but if the last attempt failed moments ago, wait for the
        // backoff: every UI poll would otherwise trigger another doomed request.
        return current.error == null
                || Duration.between(current.attemptedAt, now).toSeconds() >= FAILURE_RETRY_SECONDS;
    }

    /**
     * Fetches the release list, keeping the last good list when the fetch fails.
     *
     * A network hiccup must not empty the version history the user is looking at, so the previous
     * releases survive and only the error and the attempt time move.
     */
    private Snapshot refresh() {
        synchronized (snapshotLock) {
            Snapshot previous = snapshot;
            Instant attemptedAt = Instant.now();
            try {
                List<ReleaseInfo> releases = versionSource.fetch();
                Snapshot fresh = new Snapshot(List.copyOf(releases), attemptedAt, attemptedAt, null);
                snapshot = fresh;
                return fresh;
            } catch (UpdateException e) {
                log.warn("update check failed: {}", e.getMessage());
                return fail(previous, attemptedAt, e.getMessage());
            } catch (RuntimeException e) {
                log.warn("update check failed", e);
                String message = Texts.isBlank(e.getMessage())
                        ? "检查更新失败：" + e.getClass().getSimpleName()
                        : "检查更新失败：" + e.getMessage();
                return fail(previous, attemptedAt, message);
            }
        }
    }

    private Snapshot fail(Snapshot previous, Instant attemptedAt, String error) {
        Snapshot failed = new Snapshot(
                previous == null ? List.of() : previous.releases,
                previous == null ? null : previous.checkedAt,
                attemptedAt,
                error == null ? "检查更新失败" : error);
        snapshot = failed;
        return failed;
    }

    // -------------------------------------------------------------- download

    /**
     * Downloads the jar of one release into {@code {dataDir}/updates}.
     *
     * The bytes are hashed while they stream past, so the checksum costs no second read of a 40 MB
     * file, and the result is verified to actually be a zip before it is offered for apply: a
     * captive portal or a truncated transfer would otherwise produce a jar that bricks the install.
     *
     * Failures throw {@link IllegalArgumentException} on purpose - this is a user-triggered action
     * and the endpoint turns it into a 400 with the message; silently recording it would leave the
     * UI waiting for a download that will never appear.
     */
    public Map<String, Object> download(String versionText) {
        AppProperties.Update update = properties.getUpdate();
        if (!update.isEnabled()) {
            throw new IllegalArgumentException("自动更新已禁用，无法下载");
        }
        Version target = parseRequestedVersion(versionText);
        if (!working.compareAndSet(false, true)) {
            throw new IllegalArgumentException("已有下载或应用正在进行中，请稍后重试");
        }
        try {
            ReleaseInfo release = releaseOf(target);
            ReleaseInfo.Asset asset = jarAsset(release);
            Path directory = updatesDirectory();
            Path file = directory.resolve("code-association-" + target + ".jar");
            Path part = directory.resolve(file.getFileName() + ".part");
            DownloadResult result = fetchAsset(asset, part, target);
            // Move only after the header check, so a bad file never appears under the real name.
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
            Pending downloaded = new Pending();
            downloaded.version = target.toString();
            downloaded.jarPath = file.toAbsolutePath().toString();
            downloaded.bytes = result.bytes;
            downloaded.downloadedAt = Instant.now().toString();
            pending = downloaded;
            log.info("downloaded {} to {} ({} bytes, sha256={})",
                    target, file, result.bytes, result.sha256);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("downloaded", true);
            out.put("version", downloaded.version);
            out.put("jarPath", downloaded.jarPath);
            out.put("bytes", downloaded.bytes);
            out.put("verified", true);
            return out;
        } catch (IOException e) {
            throw new IllegalArgumentException("下载失败：" + describe(e), e);
        } finally {
            working.set(false);
        }
    }

    private DownloadResult fetchAsset(ReleaseInfo.Asset asset, Path part, Version target) throws IOException {
        if (Texts.isBlank(asset.downloadUrl)) {
            throw new IllegalArgumentException("版本 " + target + " 的附件没有下载地址");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(asset.downloadUrl))
                .timeout(DOWNLOAD_TIMEOUT)
                .header("User-Agent", "code-association/" + runningVersion)
                .header("Accept", "application/octet-stream")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = downloadClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new IllegalArgumentException("下载超时，请检查网络后重试", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("下载被中断", e);
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() >= 300) {
                throw new IllegalArgumentException("下载失败：GitHub 返回 HTTP " + response.statusCode());
            }
            MessageDigest digest = sha256();
            long bytes = 0;
            try (DigestInputStream in = new DigestInputStream(body, digest);
                 OutputStream out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    bytes += read;
                }
            }
            if (asset.size > 0 && bytes != asset.size) {
                Files.deleteIfExists(part);
                throw new IllegalArgumentException("下载不完整：期望 " + asset.size + " 字节，实际 " + bytes + " 字节");
            }
            verifyJar(part);
            DownloadResult result = new DownloadResult();
            result.bytes = bytes;
            result.sha256 = HexFormat.of().formatHex(digest.digest());
            return result;
        }
    }

    /**
     * Rejects a file that is not a zip/jar.
     *
     * Public so the check can be tested against a temporary file: it is the last line of defence
     * between a corrupted download and an installation that no longer starts.
     */
    public static void verifyJar(Path file) throws IOException {
        byte[] head = new byte[ZIP_MAGIC.length];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.readNBytes(head, 0, head.length);
        }
        if (read < ZIP_MAGIC.length || !java.util.Arrays.equals(head, ZIP_MAGIC)) {
            Files.deleteIfExists(file);
            throw new IllegalArgumentException("下载的文件不是有效的 jar（文件头不是 PK\\x03\\x04），已删除");
        }
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JRE is required to ship SHA-256; if it is missing the platform is broken and
            // continuing without a checksum would be worse than failing the download.
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    private ReleaseInfo releaseOf(Version target) {
        ReleaseInfo release = findIn(snapshot == null ? null : snapshot.releases, target);
        if (release == null) {
            // The cached list may predate the release the user asked for (or be empty), so one
            // forced refresh is worth it before declaring the version unknown.
            Snapshot fresh = refresh();
            release = findIn(fresh.releases, target);
        }
        if (release == null) {
            throw new IllegalArgumentException("未找到版本 " + target + " 的发布记录，请先检查更新");
        }
        return release;
    }

    private static ReleaseInfo findIn(List<ReleaseInfo> releases, Version target) {
        if (releases == null) {
            return null;
        }
        for (ReleaseInfo release : releases) {
            Version version = Version.tryParse(release.version);
            if (version != null && version.compareTo(target) == 0) {
                return release;
            }
        }
        return null;
    }

    /**
     * Picks the jar to install.
     *
     * A release also ships tar.gz/zip bundles for humans, and only a jar can be swapped in place.
     * Two rules keep the choice right as releases grow:
     *
     * <ul>
     *   <li><b>Side artefacts are excluded.</b> {@code -sources.jar}, {@code -javadoc.jar} and
     *       {@code -tests.jar} are jars too, but copying one over the running jar would leave an
     *       installation that cannot start - the worst possible outcome for this feature;</li>
     *   <li><b>A name containing the application wins.</b> A release may also carry an unrelated
     *       helper jar; the one named after the app is the one that is actually runnable.</li>
     * </ul>
     */
    public static ReleaseInfo.Asset jarAsset(ReleaseInfo release) {
        ReleaseInfo.Asset fallback = null;
        for (ReleaseInfo.Asset asset : release.assets) {
            if (!asset.isJar() || isSideArtefact(asset.name)) {
                continue;
            }
            String name = asset.name.toLowerCase(Locale.ROOT);
            if (name.contains("code-association")) {
                return asset;
            }
            if (fallback == null) {
                fallback = asset;
            }
        }
        if (fallback == null) {
            // Say why: without the parenthetical the user cannot tell a missing asset from a
            // network failure, and the release page is where they have to go next.
            throw new IllegalArgumentException("版本 " + release.version
                    + " 没有提供 .jar 附件（该版本只发布了 tar.gz/zip，自动更新需要独立的可运行 jar），"
                    + "请从发布页手动下载");
        }
        return fallback;
    }

    /** Build outputs that end in .jar but are not the application. */
    private static boolean isSideArtefact(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("-sources") || lower.contains("-javadoc")
                || lower.contains("-tests") || lower.contains("-test-sources");
    }

    private Path updatesDirectory() throws IOException {
        Path directory = properties.dataPath().resolve("updates");
        Files.createDirectories(directory);
        return directory;
    }

    // ----------------------------------------------------------------- apply

    /**
     * Copies the downloaded jar over the running one and keeps a backup beside it.
     *
     * The restart is intentionally left to the operator's script: see the class comment.
     */
    public Map<String, Object> apply() {
        Pending target = pending;
        if (target == null) {
            throw new IllegalArgumentException("还没有下载好的新版本，请先下载");
        }
        Path jar = runningJar();
        if (jar == null) {
            throw new IllegalArgumentException("当前不是以 java -jar 方式运行，无法自动替换，请手动更新");
        }
        if (isWindows()) {
            // Windows holds an exclusive lock on a running executable; the copy would fail with a
            // confusing "file in use" halfway through. Refuse up front with an actionable message.
            throw new IllegalArgumentException("Windows 上无法替换正在运行的 jar，请停止服务后手动替换");
        }
        if (!working.compareAndSet(false, true)) {
            throw new IllegalArgumentException("已有下载或应用正在进行中，请稍后重试");
        }
        try {
            Path downloaded = Path.of(target.jarPath);
            if (!Files.isRegularFile(downloaded)) {
                throw new IllegalArgumentException("下载的文件已不存在：" + downloaded);
            }
            Path backup = jar.resolveSibling(jar.getFileName() + BACKUP_MARKER + runningVersion);
            try {
                Files.copy(jar, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // Without a backup there is no way back, so stop before touching the live jar.
                throw new IllegalArgumentException("备份当前 jar 失败，已中止更新：" + describe(e), e);
            }
            try {
                Files.copy(downloaded, jar, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                restore(jar, backup);
                throw new IllegalArgumentException("替换 jar 失败，已尝试恢复原文件：" + describe(e), e);
            }
            pending = null;
            log.info("applied {} over {} (backup {})", target.version, jar, backup);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("applied", true);
            out.put("version", target.version);
            out.put("backupPath", backup.toAbsolutePath().toString());
            out.put("jarPath", jar.toAbsolutePath().toString());
            out.put("restartRequired", true);
            // Epoch millis, not an ISO string: the client compares it against status().startedAt
            // to decide whether the restart already happened, and two clocks formatted differently
            // (fractional digits, time zones) must not decide that.
            out.put("swappedAt", System.currentTimeMillis());
            return out;
        } finally {
            working.set(false);
        }
    }

    private void restore(Path jar, Path backup) {
        try {
            Files.copy(backup, jar, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Nothing else can be done here; the backup still exists on disk for a manual copy.
            log.error("could not restore {} from {}", jar, backup, e);
        }
    }

    /** Restores the most recent backup of the running jar. */
    public Map<String, Object> rollback() {
        Path jar = runningJar();
        if (jar == null) {
            throw new IllegalArgumentException("当前不是以 java -jar 方式运行，无法回滚，请手动处理");
        }
        if (isWindows()) {
            throw new IllegalArgumentException("Windows 上无法替换正在运行的 jar，请停止服务后手动替换");
        }
        if (!working.compareAndSet(false, true)) {
            throw new IllegalArgumentException("已有下载或应用正在进行中，请稍后重试");
        }
        try {
            Path backup = latestBackup(jar);
            if (backup == null) {
                throw new IllegalArgumentException("没有找到可回滚的备份文件（" + jar.getFileName() + BACKUP_MARKER + "*）");
            }
            try {
                Files.copy(backup, jar, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalArgumentException("回滚失败：" + describe(e), e);
            }
            log.info("rolled {} back from {}", jar, backup);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rolledBack", true);
            out.put("version", backupVersion(jar, backup));
            out.put("jarPath", jar.toAbsolutePath().toString());
            out.put("restartRequired", true);
            out.put("swappedAt", System.currentTimeMillis());
            return out;
        } finally {
            working.set(false);
        }
    }

    /** The newest {@code <jar>.bak-*} beside the running jar, or null when there is none. */
    private static Path latestBackup(Path jar) {
        Path directory = jar.getParent();
        if (directory == null) {
            return null;
        }
        String prefix = jar.getFileName() + BACKUP_MARKER;
        List<Path> backups = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .forEach(backups::add);
        } catch (IOException e) {
            log.warn("could not list backups in {}", directory, e);
            return null;
        }
        if (backups.isEmpty()) {
            return null;
        }
        backups.sort(Comparator.comparingLong(UpdateService::lastModified).reversed());
        return backups.get(0);
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            log.debug("could not read the modification time of {}", path, e);
            return 0L;
        }
    }

    private static String backupVersion(Path jar, Path backup) {
        String suffix = backup.getFileName().toString()
                .substring((jar.getFileName() + BACKUP_MARKER).length());
        return suffix.isBlank() ? "unknown" : suffix;
    }

    // ------------------------------------------------------------- scheduler

    /**
     * Hourly check, on by default.
     *
     * A scheduled method that throws is logged and retried next tick, but the status card would
     * lose the reason, so the failure is recorded through {@link #refresh()} instead.
     */
    @Scheduled(cron = "${code-association.update.cron:0 0 * * * *}")
    public void tick() {
        if (!properties.getUpdate().isEnabled()) {
            return;
        }
        checkQuietly("cron");
    }

    /**
     * Catch-up check after startup.
     *
     * A freshly started instance has no cache and the UI would otherwise show "never checked"
     * until the first cron boundary. It runs on a daemon thread so a slow or unreachable GitHub
     * never delays the web server becoming ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!properties.getUpdate().isEnabled()) {
            return;
        }
        Snapshot current = snapshot;
        if (current != null && !shouldRefresh(current)) {
            return;
        }
        Thread thread = new Thread(() -> checkQuietly("startup"), "update-check-startup");
        thread.setDaemon(true);
        thread.start();
    }

    private void checkQuietly(String trigger) {
        try {
            Snapshot result = refresh();
            if (result.error != null) {
                log.warn("update check ({}): {}", trigger, result.error);
            } else {
                log.info("update check ({}): {} release(s)", trigger, result.releases.size());
            }
        } catch (RuntimeException e) {
            // Belt and braces: refresh() already absorbs failures, but a scheduler thread must
            // never die on an exception it did not anticipate.
            log.warn("update check ({}) failed", trigger, e);
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The jar this code was loaded from, or null when running from a directory (IDE, tests).
     *
     * The path is what makes apply possible at all: it is the file an operator would replace by
     * hand, so it is also the only correct target.
     */
    public Path runningJar() {
        return JarLocation.currentJar();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    private Version parseRequestedVersion(String text) {
        if (Texts.isBlank(text)) {
            throw new IllegalArgumentException("请提供要下载的版本号");
        }
        try {
            return Version.parse(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("版本号格式不正确：" + text);
        }
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    public boolean isEnabled() {
        return properties.getUpdate().isEnabled();
    }

    public boolean isBusy() {
        return working.get();
    }

    /** Immutable snapshot of the last check; replaced as a whole so readers never see a mix. */
    private static final class Snapshot {
        final List<ReleaseInfo> releases;
        /** Time of the last successful check, or null when every attempt so far failed. */
        final Instant checkedAt;
        final Instant attemptedAt;
        final String error;

        Snapshot(List<ReleaseInfo> releases, Instant checkedAt, Instant attemptedAt, String error) {
            this.releases = releases;
            this.checkedAt = checkedAt;
            this.attemptedAt = attemptedAt;
            this.error = error;
        }
    }

    /** A downloaded jar waiting to be applied. */
    private static final class Pending {
        String version;
        String jarPath;
        long bytes;
        String downloadedAt;
    }

    private static final class DownloadResult {
        long bytes;
        String sha256;
    }
}
