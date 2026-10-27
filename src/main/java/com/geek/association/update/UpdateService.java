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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li><b>The restart is handed off, never done inline.</b> Applying copies the new jar over the
 *       running one and then asks {@link RestartSupervisor} to take over: a detached supervisor
 *       waits for this JVM to exit, starts the new one, health-checks it and restores the backup
 *       when it does not come up. Killing the JVM from inside the request that asked for it would
 *       drop the HTTP response and leave the UI showing a failed update that actually succeeded,
 *       which is why the exit is delayed and why the supervisor - not this class - owns it;</li>
 *   <li><b>Transient state stays in memory.</b> "Is there an update right now" depends on the
 *       running process and is re-derived on every view, so caching it in a table could only make
 *       it stale. The one thing worth persisting is the opposite kind of fact - which versions this
 *       installation has actually been through - and that lives in {@link UpdateHistory}, which
 *       degrades to a no-op rather than ever failing a check.</li>
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
    /** Downloaded jars are named after the version so they can be matched without opening them. */
    private static final String JAR_PREFIX = "code-association-";
    private static final String JAR_SUFFIX = ".jar";
    /** A failed check is retried after this delay, so a poll loop cannot hammer a dead endpoint. */
    private static final long FAILURE_RETRY_SECONDS = 60;
    /** How often the streaming loop publishes its byte count; see {@link #fetchAssetOnce}. */
    private static final long PROGRESS_INTERVAL_MILLIS = 100;

    private final AppProperties properties;
    private final VersionSource versionSource;
    private final HttpClient downloadClient;
    private final Version runningVersion;

    /**
     * Brings the process back after a jar swap. Never null: the no-op form is used when the
     * service is constructed without one (tests), so every call site can stay unconditional.
     */
    private final RestartSupervisor restarts;

    /** Serialises the read-modify-write of the runtime toggles in {@link #saveSettings}. */
    private final Object settingsLock = new Object();

    /** What this installation has seen and done; never throws, so it cannot break a check. */
    private final UpdateHistory history;

    /**
     * Versions this process already tried to auto-download.
     *
     * Without it a failing download (offline, rate limited, a release whose jar is missing) would be
     * retried on every status poll, which is exactly the kind of background traffic that gets an
     * installation blocked. One attempt per version per process is enough: the operator can still
     * press the download button by hand.
     */
    private final Set<String> autoDownloadAttempted = ConcurrentHashMap.newKeySet();

    /**
     * Versions this process already tried to apply unattended.
     *
     * Separate from {@link #autoDownloadAttempted} because the two can fail independently: a
     * download that succeeded but an apply that threw would otherwise be retried on every check,
     * which for an apply means a restart attempt per poll.
     */
    private final Set<String> autoApplyAttempted = ConcurrentHashMap.newKeySet();

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

    /**
     * How far the current download/apply has got, for the UI to render as it happens.
     *
     * The automatic path runs on a daemon thread nobody is watching, and until this existed the
     * only outward sign of a 40 MB background download was {@code busy: true} - no version, no
     * byte count, and no trace at all when it failed, because those failures are logged rather
     * than thrown. The object is memory-only and process-lifetime: see {@link UpdateProgress}.
     */
    private final UpdateProgress progress = new UpdateProgress();

    /** Serialises the read-modify-write of {@link #snapshot}; short, never held during I/O. */
    private final Object snapshotLock = new Object();

    private volatile Snapshot snapshot;
    private volatile Pending pending;

    /**
     * Check-only form: no restart supervisor, so applying a jar reports {@code restartRequired}
     * and leaves the restart to the operator.
     *
     * Not annotated for injection: the container would then have two candidates and refuse to
     * start. It exists for the tests, which is also why the client is built here rather than
     * required from the caller.
     */
    public UpdateService(AppProperties properties, VersionSource versionSource) {
        this(properties, versionSource, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), Version.running(), null);
    }

    /**
     * Full form, used by the tests and by any caller that needs a fixed client or running version.
     *
     * A pinned running version is what makes "there is no update" testable without installing
     * anything, and a local client is what lets the download path be exercised offline.
     */
    public UpdateService(AppProperties properties, VersionSource versionSource, HttpClient downloadClient,
                         Version runningVersion) {
        this(properties, versionSource, downloadClient, runningVersion, null);
    }

    /**
     * The form the container uses: same as above plus the supervisor that owns the restart.
     *
     * The supervisor is passed in rather than built here so the port (which only the web layer
     * knows) comes from the one place that can read it, and so a test can hand in a supervisor
     * whose launcher and health probe are fakes.
     */
    @Autowired
    public UpdateService(AppProperties properties, VersionSource versionSource, RestartSupervisor restarts) {
        this(properties, versionSource, GitHubNetwork.client(properties.getUpdate()), Version.running(), restarts);
    }

    /** The full form: every collaborator is explicit, which is what makes the tests offline. */
    public UpdateService(AppProperties properties, VersionSource versionSource, HttpClient downloadClient,
                         Version runningVersion, RestartSupervisor restarts) {
        this.properties = properties;
        this.versionSource = versionSource;
        this.downloadClient = downloadClient;
        this.runningVersion = runningVersion;
        this.history = new UpdateHistory(properties);
        this.restarts = restarts;
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
        // The downloads directory is listed once per view and never cached: the file on disk is
        // the fact, and a jar deleted by hand (or fetched by a previous run) must be reported as
        // it is rather than as it was when the download finished.
        Map<String, String> localJars = localJars();
        Version latest = null;
        // The newest version that can actually be installed. It differs from "latest" only when the
        // newest tag has no release/jar behind it, and that difference is exactly what stops the
        // banner from advertising an update nobody can install.
        Version installable = null;
        for (ReleaseInfo release : list) {
            Version version = Version.tryParse(release.version);
            // Derived flags are recomputed on every view instead of being stored: they depend on
            // the running version, and a cached release must not carry flags from another build.
            // Writing them onto the shared cached object is safe because the value depends only on
            // the running version, which cannot change while the process lives: every writer
            // stores the same boolean.
            release.current = version != null && version.compareTo(runningVersion) == 0;
            release.newer = version != null && version.isNewerThan(runningVersion);
            String key = version == null ? null : version.toString();
            release.downloaded = key != null && localJars.containsKey(key);
            release.downloadedAt = key == null ? null : localJars.get(key);
            // A tags-only entry has no assets, so offering it for download would be a button that
            // can only fail. Saying "not installable" up front is the honest version of that.
            release.downloadable = hasInstallableJar(release);
            if (latest == null && version != null) {
                latest = version;
            }
            if (installable == null && version != null && release.downloadable) {
                installable = version;
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
        // Kept separate from latestVersion on purpose: "a newer version exists" and "a newer version
        // can be installed from here" are different claims, and only the second one justifies
        // offering a download.
        out.put("latestInstallableVersion", installable == null ? null : installable.toString());
        out.put("updateAvailable", latest != null && latest.isNewerThan(runningVersion));
        out.put("installableUpdateAvailable",
                installable != null && installable.isNewerThan(runningVersion));
        out.put("checkedAt", checkedAt == null ? null : checkedAt.toString());
        out.put("checkIntervalMinutes", update.getCheckIntervalMinutes());
        out.put("source", source);
        out.put("error", error);
        // How this process was started, and whether that leaves a jar to overwrite. Both are
        // reported rather than reduced to a boolean: "no jar" has three different causes with three
        // different fixes, and the card has to be able to name the one that applies here.
        LaunchMode launchMode = LaunchMode.of(jar, JarLocation.codeSourceLocation());
        ApplySupport applySupport = ApplySupport.of(launchMode, isWindows());
        out.put("runningFromJar", jar != null);
        out.put("launchMode", launchMode.wireName());
        out.put("launchModeLabel", launchMode.label());
        out.put("applySupported", applySupport.supported());
        out.put("applyUnsupportedReason", applySupport.reason());
        out.put("applyHint", applySupport.hint());
        out.put("busy", working.get());
        out.put("pending", pendingView());
        // Non-fatal source problems (e.g. the tags endpoint failed while releases worked). An
        // empty list rather than null, so the frontend can iterate without a guard.
        out.put("warnings", warnings());
        out.put("includeTags", update.isIncludeTags());
        out.put("autoDownload", update.isAutoDownload());
        // The three automatic behaviours are reported separately from the release list because the
        // settings card edits them in place, and it has to render their current values from the
        // same payload it already fetches.
        out.put("autoApply", update.isAutoApply());
        out.put("autoRestart", update.isAutoRestart());
        out.put("restartTimeoutSeconds", update.getRestartTimeoutSeconds());
        // Whether a hot restart is even possible here, plus what the supervisor is doing right now.
        // Null-safe on purpose: a deployment without a supervisor must still render the card.
        out.put("restart", restarts == null ? unsupportedRestartView() : restarts.state());
        out.put("network", networkView(update));
        out.put("historyTracked", history.size());
        out.put("releases", list);
        // Before the payload leaves, reconcile a "restarting" pipeline with what the supervisor
        // actually reported. The restart outlives the request that started it, so the phase can
        // only be settled from the outside - and a progress block stuck on "正在重启" forever is
        // exactly the "did it work?" ambiguity this whole block exists to remove.
        settleProgressAfterRestart();
        out.put("progress", progress.toMap());
        return out;
    }

    /**
     * Turns a {@code restarting} pipeline into its outcome once the supervisor has one.
     *
     * Deliberately tolerant: an unrecognised phase, a missing supervisor or an older payload all
     * leave the block untouched. The cost of being wrong here is a stale sentence, and the cost of
     * being strict is a card that reports a failure for a restart that is merely slow.
     */
    private void settleProgressAfterRestart() {
        if (!UpdateProgress.PHASE_RESTARTING.equals(progress.getPhase())) {
            return;
        }
        Map<String, Object> restart = restarts == null ? null : restarts.state();
        if (restart == null) {
            return;
        }
        Object phase = restart.get("phase");
        if (RestartStatus.PHASE_SUCCEEDED.equals(phase)) {
            progress.succeeded("已更新到 " + progress.getVersion() + " 并完成重启");
        } else if (RestartStatus.PHASE_ROLLED_BACK.equals(phase) || RestartStatus.PHASE_FAILED.equals(phase)) {
            Object message = restart.get("message");
            progress.failed("升级失败，已回滚到原版本",
                    message == null ? null : message.toString());
        }
    }

    /**
     * The restart block for an installation that has no supervisor at all.
     *
     * Same keys as the real one rather than {@code null}: the settings card reads them
     * unconditionally, and a missing object would force every caller to guard.
     */
    private static Map<String, Object> unsupportedRestartView() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("supported", false);
        out.put("unsupportedReason", "当前启动方式没有启用热重启监督器");
        out.put("running", false);
        out.put("phase", "idle");
        out.put("startedAt", null);
        out.put("finishedAt", null);
        out.put("attemptedVersion", null);
        out.put("message", null);
        out.put("rolledBack", false);
        out.put("healthUrl", null);
        out.put("logFile", null);
        return out;
    }

    /**
     * How the next check will try to reach GitHub.
     *
     * Every field answers a question an operator asks when a check fails - "which host", "is a
     * mirror set", "is a proxy in play", "how many times does it retry", "when is the quota back" -
     * and none of them can be answered from the release list itself.
     */
    private Map<String, Object> networkView(AppProperties.Update update) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> bases = update.getApiBaseUrls() == null || update.getApiBaseUrls().isEmpty()
                ? List.of("https://api.github.com") : List.copyOf(update.getApiBaseUrls());
        out.put("apiBaseUrls", bases);
        // Which host actually answered last. A fallback that works is worth showing: it explains
        // why a check succeeds on a network where api.github.com is blocked.
        out.put("activeApiBase", activeApiBase(bases));
        out.put("downloadMirror", Texts.isBlank(update.getDownloadMirror())
                ? "" : update.getDownloadMirror().strip());
        out.put("proxyConfigured", !Texts.isBlank(update.getProxy()));
        out.put("retries", Math.max(0, update.getRetries()));
        out.put("respectRateLimit", update.isRespectRateLimit());
        // The shared quota deadline, so the UI can say "wait until 14:05" instead of "try again".
        Instant until = rateLimitGate() == null ? null : rateLimitGate().blockedUntil();
        out.put("rateLimitedUntil", until == null ? null : until.toString());
        // Which host failed last, when one did. Kept beside rateLimitedUntil so the card can show
        // "api.github.com 限流，已改用 mirror" without re-parsing the error message.
        out.put("lastFailureHost", versionSource instanceof GitHubVersionSource source
                ? source.lastFailureHost() : null);
        // Release assets come from a CDN that does not consume the API quota, so a rate-limited
        // API never blocks a download. Said explicitly because the two are easy to conflate when
        // the card shows a quota deadline right above a download button.
        out.put("downloadBlocked", false);
        return out;
    }

    private String activeApiBase(List<String> configured) {
        if (versionSource instanceof GitHubVersionSource source) {
            String active = source.activeApiBase();
            if (!Texts.isBlank(active)) {
                return active;
            }
        }
        return configured.get(0);
    }

    /**
     * The quota latch the source uses, when it is the GitHub one.
     *
     * Optional rather than required: a {@link VersionSource} is an interface precisely so a test
     * (or a future source) can exist without a GitHub quota, and asking a non-GitHub source for one
     * would be a downcast in disguise.
     */
    private RateLimitGate rateLimitGate() {
        return versionSource instanceof GitHubVersionSource source ? source.rateLimitGate() : null;
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
        Snapshot fresh;
        synchronized (snapshotLock) {
            Snapshot previous = snapshot;
            Instant attemptedAt = Instant.now();
            try {
                List<ReleaseInfo> releases = versionSource.fetch();
                fresh = new Snapshot(List.copyOf(releases), attemptedAt, attemptedAt, null);
                snapshot = fresh;
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
        // Both of these run outside the lock on purpose: it only protects the in-memory snapshot
        // and is meant to be short, while recording the ledger and starting a download are I/O.
        // The try/catch is the belt to UpdateHistory's braces: "checking never throws at the
        // caller" is the contract of this class, and a bookkeeping side effect must never be the
        // thing that breaks it.
        try {
            history.record(fresh.releases, fresh.checkedAt);
            autoDownload(fresh);
        } catch (RuntimeException e) {
            log.warn("could not record the update history: {}", e.toString());
        }
        return fresh;
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
        return download(versionText, false);
    }

    /**
     * The same download, tagged with who asked for it.
     *
     * {@code automatic} only changes how the progress block reads ("后台自动更新" versus a button
     * press); the work is identical, and keeping one implementation is what stops the two paths
     * from drifting into different failure behaviour.
     */
    Map<String, Object> download(String versionText, boolean automatic) {
        AppProperties.Update update = properties.getUpdate();
        if (!update.isEnabled()) {
            throw new IllegalArgumentException("自动更新已禁用，无法下载");
        }
        Version target = parseRequestedVersion(versionText);
        if (!working.compareAndSet(false, true)) {
            throw new IllegalArgumentException("已有下载或应用正在进行中，请稍后重试");
        }
        progress.start(UpdateProgress.PHASE_DOWNLOADING, target.toString(), automatic, 0,
                automatic ? "后台正在下载新版本" : "正在下载");
        try {
            ReleaseInfo release = releaseOf(target);
            ReleaseInfo.Asset asset = jarAsset(release);
            Path directory = updatesDirectory();
            Path file = directory.resolve("code-association-" + target + ".jar");
            Path part = directory.resolve(file.getFileName() + ".part");
            DownloadResult result = fetchAsset(asset, part, target);
            progress.moveTo(UpdateProgress.PHASE_VERIFYING, "校验文件");
            // Move only after the header check, so a bad file never appears under the real name.
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
            Instant downloadedAt = Instant.now();
            Pending downloaded = new Pending();
            downloaded.version = target.toString();
            downloaded.jarPath = file.toAbsolutePath().toString();
            downloaded.bytes = result.bytes;
            downloaded.downloadedAt = downloadedAt.toString();
            pending = downloaded;
            // The ledger outlives the process, so after a restart the UI still knows which
            // versions are already fetched; `pending` alone forgets them when the service stops.
            history.markDownloaded(downloaded.version, downloadedAt);
            log.info("downloaded {} to {} ({} bytes, sha256={})",
                    target, file, result.bytes, result.sha256);
            progress.downloaded(result.bytes, result.bytes);
            progress.succeeded("已下载 " + downloaded.version);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("downloaded", true);
            out.put("version", downloaded.version);
            out.put("jarPath", downloaded.jarPath);
            out.put("bytes", downloaded.bytes);
            out.put("verified", true);
            return out;
        } catch (IOException e) {
            progress.failed("下载失败", describeDownload(e));
            throw new IllegalArgumentException("下载失败：" + describeDownload(e), e);
        } catch (RuntimeException e) {
            // Covers the header check, a rejected URL and the retry budget running out. Those
            // messages are already sentences, so they are shown as the reason - with the leading
            // "下载失败：" stripped, because the card already prints that as the title and
            // "下载失败 / 下载失败：连接被拒绝" reads like two different problems.
            progress.failed("下载失败", stripPrefix(e.getMessage(), "下载失败："));
            throw e;
        } finally {
            working.set(false);
        }
    }

    /**
     * Streams the asset to {@code part}, retrying a transfer that failed for a network reason.
     *
     * A 40 MB download over a blocked or throttled link fails part-way often enough that a single
     * attempt would make the feature look broken; a rejected URL, on the other hand, fails the same
     * way every time, so only transport errors, 5xx and a short read are retried. The partial file
     * is deleted before each attempt: resuming would need range requests, and a stale {@code .part}
     * appended to would produce a corrupt jar that passes the header check.
     */
    private DownloadResult fetchAsset(ReleaseInfo.Asset asset, Path part, Version target) throws IOException {
        if (Texts.isBlank(asset.downloadUrl)) {
            throw new IllegalArgumentException("版本 " + target + " 的附件没有下载地址");
        }
        AppProperties.Update update = properties.getUpdate();
        int attempts = Math.max(1, 1 + Math.max(0, update.getRetries()));
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return fetchAssetOnce(asset, part, target);
            } catch (IOException e) {
                // Covers both a broken stream and the explicit {@link IncompleteTransfer}: a short
                // read is "the transfer broke", never "the file is bad".
                Files.deleteIfExists(part);
                // describeDownload, not describe: this message is what the card shows when the
                // retry budget runs out, and "ConnectException" is not a sentence for a human.
                last = new IllegalArgumentException("下载失败：" + describeDownload(e), e);
                if (attempt >= attempts) {
                    break;
                }
                log.debug("retrying download of {} (attempt {}/{}): {}", target, attempt, attempts, e.toString());
                if (!pause(attempt, update)) {
                    break;
                }
            } catch (AssetRejected e) {
                // A 4xx is the server refusing this URL; another attempt would be refused too.
                Files.deleteIfExists(part);
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }
        throw last == null ? new IllegalArgumentException("下载失败") : last;
    }

    /**
     * One transfer attempt.
     *
     * The two checked failure modes are carried as dedicated exceptions so {@link #fetchAsset} can
     * tell "the network broke, try again" from "the server said no, stop" without parsing messages.
     */
    private DownloadResult fetchAssetOnce(ReleaseInfo.Asset asset, Path part, Version target) throws IOException {
        // The asset lives on a CDN that some networks block while the API host itself is
        // reachable, so the mirror is applied here rather than at the call site: every download
        // path - button, auto-download, retry - has to go through the same rewrite.
        String url = GitHubNetwork.mirrorDownloadUrl(asset.downloadUrl, properties.getUpdate().getDownloadMirror());
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(DOWNLOAD_TIMEOUT)
                .header("User-Agent", "code-association/" + runningVersion)
                .header("Accept", "application/octet-stream")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = downloadClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new IOException("下载超时", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("下载被中断", e);
        }
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status >= 300) {
                if (GitHubNetwork.isRetryableStatus(status)) {
                    throw new IOException("GitHub 返回 HTTP " + status);
                }
                throw new AssetRejected("下载失败：GitHub 返回 HTTP " + status
                        + (status == 404 ? "（该版本的附件可能已被删除）" : ""));
            }
            MessageDigest digest = sha256();
            // The release asset records its own size; Content-Length is the fallback for a source
            // that does not, and a mirror may rewrite either. Whichever is larger wins, because
            // under-reporting the total would make the bar read over 100% at the end.
            long total = Math.max(asset.size, contentLength(response));
            progress.attemptStarted(total, progress.getMessage());
            long bytes = 0;
            long reportedAt = 0;
            try (DigestInputStream in = new DigestInputStream(body, digest);
                 OutputStream out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    bytes += read;
                    // Publishing every buffer would be ~600 volatile writes per 40 MB; a 100 ms
                    // cadence is smoother than the UI can render and keeps the loop cheap.
                    long now = System.currentTimeMillis();
                    if (now - reportedAt >= PROGRESS_INTERVAL_MILLIS) {
                        reportedAt = now;
                        progress.downloaded(bytes, total);
                    }
                }
            }
            progress.downloaded(bytes, total);
            if (asset.size > 0 && bytes != asset.size) {
                // A truncated transfer is the single most common failure on a throttled link, and
                // it is retryable: the bytes that arrived are simply discarded.
                throw new IncompleteTransfer("下载不完整：期望 " + asset.size + " 字节，实际 " + bytes + " 字节");
            }
            verifyJar(part);
            DownloadResult result = new DownloadResult();
            result.bytes = bytes;
            result.sha256 = HexFormat.of().formatHex(digest.digest());
            return result;
        }
    }

    /**
     * {@code Content-Length} of the response, or 0 when the header is absent or unusable.
     *
     * Only ever used as a fallback for a progress bar: a wrong value here must never fail the
     * download, which is why a malformed header degrades to "size unknown" instead of throwing.
     */
    private static long contentLength(HttpResponse<?> response) {
        return response.headers().firstValueAsLong("Content-Length").orElse(0L);
    }

    /** Waits out the backoff; returns false when the thread was interrupted and the retry must stop. */
    private static boolean pause(int attempt, AppProperties.Update update) {
        long delay = GitHubNetwork.backoffMillis(attempt, update.getRetryBaseDelayMs());
        if (delay <= 0) {
            return true;
        }
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** A transfer that broke part-way and is worth another attempt. */
    private static final class IncompleteTransfer extends IOException {
        IncompleteTransfer(String message) {
            super(message);
        }
    }

    /** The server refused this URL; retrying cannot change the answer. */
    private static final class AssetRejected extends RuntimeException {
        AssetRejected(String message) {
            super(message);
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
    /**
     * Whether {@link #jarAsset(ReleaseInfo)} would succeed, without throwing to find out.
     *
     * The status view runs on every poll, so it must not use exceptions as a control-flow probe:
     * a tags-only release is an ordinary state of the world, not an error.
     */
    static boolean hasInstallableJar(ReleaseInfo release) {
        for (ReleaseInfo.Asset asset : release.assets) {
            if (asset.isJar() && !isSideArtefact(asset.name)) {
                return true;
            }
        }
        return false;
    }

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

    /**
     * The jars already present in {@code {dataDir}/updates}, keyed by version.
     *
     * Read from the directory rather than remembered, because the file is the fact: a jar removed
     * by hand, or downloaded by an earlier run, must be reported the way it is. The listing is done
     * once per status view (not once per release) and the last-modified time stands in for the
     * download time - a jar from a previous process has no other record of when it arrived.
     *
     * An unreadable or absent directory means "nothing downloaded", never an error: the status
     * endpoint must keep answering on a fresh installation.
     */
    private Map<String, String> localJars() {
        Map<String, String> out = new LinkedHashMap<>();
        Path directory = properties.dataPath().resolve("updates");
        if (!Files.isDirectory(directory)) {
            return out;
        }
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith(JAR_PREFIX) || !name.endsWith(JAR_SUFFIX)) {
                    // .part files and anything else in there are not installable jars.
                    continue;
                }
                String raw = name.substring(JAR_PREFIX.length(), name.length() - JAR_SUFFIX.length());
                Version version = Version.tryParse(raw);
                if (version == null) {
                    continue;
                }
                out.putIfAbsent(version.toString(), lastModifiedIso(file));
            }
        } catch (IOException e) {
            log.warn("could not list downloaded jars in {}: {}", directory, describe(e));
        }
        return out;
    }

    private static String lastModifiedIso(Path file) {
        try {
            return Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()).toString();
        } catch (IOException e) {
            log.debug("could not read the modification time of {}", file, e);
            return null;
        }
    }

    /**
     * Fetches a newly discovered release in the background, when the operator asked for that.
     *
     * Three deliberate limits:
     *
     * <ul>
     *   <li><b>Daemon thread, no queue.</b> The check that found the release must not wait for a
     *       40 MB download, and a thread that would keep the JVM alive after stop.sh is not
     *       acceptable either;</li>
     *   <li><b>The {@code working} flag is the mutex.</b> If a download or an apply is already
     *       running the attempt is dropped, not queued: the user is clearly doing something with
     *       that slot, and a queue would surface as a surprise download minutes later;</li>
     *   <li><b>One attempt per version per process.</b> A failure (offline, quota, a release whose
     *       jar is missing) must not be retried on every status poll.</li>
     * </ul>
     *
     * Failures are logged and swallowed: this is an unsolicited background action, and the caller
     * of status()/checkNow() has no way to react to it. The reason still reaches the UI through the
     * next download attempt, which does report it.
     */
    private void autoDownload(Snapshot fresh) {
        AppProperties.Update update = properties.getUpdate();
        // autoApply implies the download: asking for "install it by yourself" without fetching the
        // jar would make the setting do nothing at all.
        if (!(update.isAutoDownload() || update.isAutoApply()) || fresh.releases.isEmpty()) {
            return;
        }
        ReleaseInfo newest = null;
        Version newestVersion = null;
        for (ReleaseInfo release : fresh.releases) {
            Version version = Version.tryParse(release.version);
            if (version == null || !version.isNewerThan(runningVersion)) {
                continue;
            }
            // Only a version that actually ships a jar can be downloaded; a tags-only entry would
            // otherwise be picked as the target and fail on every retry.
            if (!hasInstallableJar(release)) {
                continue;
            }
            if (newestVersion == null || version.isNewerThan(newestVersion)) {
                newest = release;
                newestVersion = version;
            }
        }
        if (newest == null) {
            return;
        }
        String target = newestVersion.toString();
        // Already on disk (perhaps downloaded before this process started): nothing to fetch, but
        // an unattended installation still has to finish the job with the file that is there.
        if (localJars().containsKey(target)) {
            autoApply(target);
            return;
        }
        if (!autoDownloadAttempted.add(target)) {
            return;
        }
        Thread thread = new Thread(() -> {
            // The flag itself is taken by download(); asking here only avoids starting a thread
            // that is certain to be turned away, and keeps the "skip, never queue" rule visible.
            if (working.get()) {
                log.debug("skipping auto-download of {}: another download or apply is running", target);
                return;
            }
            try {
                // Marked automatic so the progress block can say this was not the user's doing:
                // an unexplained 40 MB download appearing out of nowhere is the same class of
                // problem as one that is invisible.
                download(target, true);
                // The download only stages the jar; an unattended installation asked for the whole
                // sequence, so the apply follows in the same background thread.
                autoApply(target);
            } catch (RuntimeException e) {
                // Including the lost race for the flag: whoever holds it is already doing the work.
                // The reason also lands in the progress block, which is the only place a user can
                // see it: this thread has no caller to return an error to. The leading label is
                // stripped because the card prints its own title above this line, and
                // "后台自动更新失败：下载失败：连接被拒绝" reads as three separate problems.
                progress.failed("后台自动更新失败", stripPrefix(e.getMessage(), "下载失败："));
                log.warn("auto-download of {} failed: {}", target, e.getMessage());
            }
        }, "update-auto-download-" + target);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Applies a downloaded jar without being asked, when the operator turned that on.
     *
     * Three guards, each of which is a way this could destroy an installation:
     *
     * <ul>
     *   <li><b>Never when a jar swap is impossible.</b> An IDE run or Windows has no replaceable
     *       jar, and {@code apply()} would throw into a background thread nobody reads;</li>
     *   <li><b>Never without a way back.</b> A hot restart without a supervisor is a service that
     *       stays down until somebody notices, so auto-apply requires both {@code autoRestart}
     *       and a supported supervisor - the same condition under which the UI stops telling the
     *       operator to restart by hand;</li>
     *   <li><b>One version per process.</b> Re-applying the version already running is a no-op that
     *       still costs a restart, and a loop of them would be an installation that never settles.</li>
     * </ul>
     *
     * Failures are logged, not thrown: this runs on a background thread whose only caller is the
     * scheduler, and the reason reaches the UI through the status payload on the next poll.
     */
    private void autoApply(String version) {
        AppProperties.Update update = properties.getUpdate();
        if (!update.isAutoApply() || !update.isAutoRestart()) {
            return;
        }
        if (restarts == null || !restarts.isSupported()) {
            log.debug("not auto-applying {}: no supported restart supervisor", version);
            return;
        }
        if (!autoApplyAttempted.add(version)) {
            return;
        }
        try {
            Map<String, Object> result = apply(true);
            log.info("auto-applied {}: {}", version, result.get("restarting"));
        } catch (RuntimeException e) {
            // apply() already recorded the reason on the progress block; repeating it here would
            // only overwrite "应用更新失败" with a less specific wording.
            log.warn("auto-apply of {} failed: {}", version, e.getMessage());
        }
    }

    // ----------------------------------------------------------------- apply

    /**
     * Copies the downloaded jar over the running one and keeps a backup beside it.
     *
     * The restart is intentionally left to the operator's script: see the class comment.
     */
    public Map<String, Object> apply() {
        return apply(false);
    }

    /**
     * The same swap, tagged with who asked for it.
     *
     * The flag only reaches the progress block, but it has to be carried through: an unattended
     * install runs download and apply on one background thread, and without this the second half
     * would report itself as a button press the user never made.
     */
    Map<String, Object> apply(boolean automatic) {
        Pending target = pending;
        if (target == null) {
            throw new IllegalArgumentException("还没有下载好的新版本，请先下载");
        }
        Path jar = runningJar();
        if (jar == null) {
            // The same sentence the card shows before the button is pressed, so a caller that
            // ignores applySupported and posts anyway gets the actionable version, not a dead end.
            throw new IllegalArgumentException(ApplySupport.detect().reason());
        }
        if (isWindows()) {
            // Windows holds an exclusive lock on a running executable; the copy would fail with a
            // confusing "file in use" halfway through. Refuse up front with an actionable message.
            throw new IllegalArgumentException("Windows 上无法替换正在运行的 jar，请停止服务后手动替换");
        }
        if (!working.compareAndSet(false, true)) {
            throw new IllegalArgumentException("已有下载或应用正在进行中，请稍后重试");
        }
        progress.start(UpdateProgress.PHASE_APPLYING, target.version, automatic, 0,
                automatic ? "后台正在替换 jar" : "正在替换 jar");
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
            progress.moveTo(UpdateProgress.PHASE_APPLYING, "正在写入新 jar");
            try {
                Files.copy(downloaded, jar, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                restore(jar, backup);
                throw new IllegalArgumentException("替换 jar 失败，已尝试恢复原文件：" + describe(e), e);
            }
            pending = null;
            history.markApplied(target.version, Instant.now());
            log.info("applied {} over {} (backup {})", target.version, jar, backup);

            // The jar is on disk and the swap succeeded; the restart is the remaining half of the
            // update. Handing it to the supervisor after the copy means a failed copy can never
            // leave a restart running against a jar that was never replaced.
            boolean restarting = false;
            if (properties.getUpdate().isAutoRestart() && restarts != null && restarts.isSupported()) {
                restarting = restarts.scheduleRestart(target.version);
                if (restarting) {
                    log.info("scheduled a supervised restart into {}", target.version);
                }
            }
            if (restarting) {
                progress.moveTo(UpdateProgress.PHASE_RESTARTING, "已替换 jar，等待服务重启");
            } else {
                // Nobody is going to bring the process back, so the pipeline ends here: the file is
                // in place and the next step is a human one. Calling this "succeeded" without the
                // restart would be the same lie the old restart banner was fixed for.
                progress.succeeded("jar 已替换，需要手动重启服务");
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("applied", true);
            out.put("version", target.version);
            out.put("backupPath", backup.toAbsolutePath().toString());
            out.put("jarPath", jar.toAbsolutePath().toString());
            // True only when nobody is going to bring the process back for the operator: the UI
            // uses it to choose between "wait a moment, it is restarting" and the manual commands.
            out.put("restartRequired", !restarting);
            out.put("restarting", restarting);
            out.put("restart", restarts == null ? unsupportedRestartView() : restarts.state());
            // Epoch millis, not an ISO string: the client compares it against status().startedAt
            // to decide whether the restart already happened, and two clocks formatted differently
            // (fractional digits, time zones) must not decide that.
            out.put("swappedAt", System.currentTimeMillis());
            return out;
        } catch (RuntimeException e) {
            progress.failed("应用更新失败", e.getMessage());
            throw e;
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
            // Recorded against the version that is running again, which is what the history list
            // should show: the mark belongs to the build the installation went back to.
            String restored = backupVersion(jar, backup);
            history.markRolledBack(restored, Instant.now());
            log.info("rolled {} back from {}", jar, backup);
            // A rollback has the same problem an apply has: the file on disk is old news to the
            // running JVM. The supervisor is reused rather than reimplemented, because "start the
            // jar again and check that it comes up" is the same job in both directions.
            boolean restarting = false;
            if (properties.getUpdate().isAutoRestart() && restarts != null && restarts.isSupported()) {
                restarting = restarts.scheduleRestart(restored);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rolledBack", true);
            out.put("version", restored);
            out.put("jarPath", jar.toAbsolutePath().toString());
            out.put("restartRequired", !restarting);
            out.put("restarting", restarting);
            out.put("restart", restarts == null ? unsupportedRestartView() : restarts.state());
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
     *
     * Off means off: an air-gapped host sets {@code check-on-startup: false} to keep the log free
     * of a failure it already knows about, and a setting that is documented but never read is
     * worse than no setting at all - the operator would believe it and stop looking.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!properties.getUpdate().isEnabled() || !properties.getUpdate().isCheckOnStartup()) {
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

    /** Removes a leading label from a message, so a card title and its body do not repeat it. */
    private static String stripPrefix(String text, String prefix) {
        if (text == null) {
            return null;
        }
        return text.startsWith(prefix) ? text.substring(prefix.length()) : text;
    }

    /**
     * A network failure in words an operator can act on.
     *
     * {@code describe} is right for a log line - "ConnectException" is enough to grep for - but it
     * ends up in the card as "下载失败：ConnectException", which names a Java class instead of the
     * situation. The three cases below are the ones a blocked network actually produces, and each
     * has a different next step, so they are worth telling apart.
     */
    private static String describeDownload(IOException e) {
        String raw = describe(e);
        if (e instanceof java.net.ConnectException || raw.contains("ConnectException")
                || raw.contains("Connection refused")) {
            return "无法连接下载地址（连接被拒绝或网络不可达）。若 GitHub 附件被网络阻断，可配置 download-mirror 后重试";
        }
        if (e instanceof java.net.UnknownHostException || raw.contains("UnknownHost")) {
            return "无法解析下载地址的域名，请检查 DNS 或代理设置";
        }
        if (e instanceof HttpTimeoutException || raw.contains("超时") || raw.contains("timed out")) {
            return "下载超时，网络可能不稳定，可以重试或改用下载镜像";
        }
        if (e instanceof IncompleteTransfer) {
            return raw;
        }
        return raw;
    }

    public boolean isEnabled() {
        return properties.getUpdate().isEnabled();
    }

    /**
     * The persisted version history of this installation, for {@code GET /api/update/history}.
     *
     * Separate from {@link #status()} because the two answer different questions: status is about
     * the outside world right now, history is about this machine over time. History stays readable
     * when the update check is disabled or GitHub is unreachable, which is exactly when somebody
     * looks at it.
     */
    public Map<String, Object> history() {
        return history.snapshot();
    }

    /**
     * Just the restart block, for the poll that runs while the service is coming back.
     *
     * Separate from {@link #status()} because that call reads the release cache and the downloads
     * directory; while a restart is in flight the browser needs one small answer, every few
     * seconds, and it must not pay for the rest.
     */
    public Map<String, Object> restartState() {
        return restarts == null ? unsupportedRestartView() : restarts.state();
    }

    /**
     * Problems the source reported for the last fetch that did not make it unusable.
     *
     * A misbehaving source must not turn a status call into a 500, so an exception from
     * {@code warnings()} is reduced to an empty list.
     */
    private List<String> warnings() {
        try {
            List<String> warnings = versionSource.warnings();
            return warnings == null ? List.of() : List.copyOf(warnings);
        } catch (RuntimeException e) {
            log.debug("version source could not report its warnings", e);
            return List.of();
        }
    }

    public boolean isBusy() {
        return working.get();
    }

    /**
     * The live pipeline state on its own, for the light poll the card runs while a download or a
     * restart is in flight. Same block as {@code status().progress}; the endpoint exists so a
     * two-second poll does not re-list the downloads directory and re-derive every release flag.
     */
    public Map<String, Object> progressState() {
        // Same reconciliation as the full payload: this is the endpoint the card polls while a
        // pipeline is active, and a "restarting" phase it never settles would be polled forever.
        settleProgressAfterRestart();
        return progress.toMap();
    }

    /**
     * Changes the automatic behaviours at runtime, without a restart.
     *
     * These are process-lifetime toggles rather than persisted configuration on purpose: they
     * describe how <i>this</i> instance behaves from now on, and the next start reads
     * application.yml again. Persisting them would create a second source of truth that silently
     * disagrees with the file the operator deployed.
     *
     * Unknown keys are ignored and absent keys are left alone, so the UI can send only the switch
     * that moved. A {@code null} body is a no-op rather than an error: the card may call this with
     * nothing when a toggle is reset.
     */
    public Map<String, Object> saveSettings(Map<String, Object> body) {
        AppProperties.Update update = properties.getUpdate();
        if (body != null) {
            synchronized (settingsLock) {
                if (body.containsKey("autoDownload")) {
                    update.setAutoDownload(flag(body.get("autoDownload"), update.isAutoDownload()));
                }
                if (body.containsKey("autoApply")) {
                    update.setAutoApply(flag(body.get("autoApply"), update.isAutoApply()));
                }
                if (body.containsKey("autoRestart")) {
                    update.setAutoRestart(flag(body.get("autoRestart"), update.isAutoRestart()));
                }
            }
            // An unattended install needs both halves; turning on one without the other is a
            // setting that reads as "on" and never does anything, so it is refused with a reason
            // rather than accepted and silently ignored at apply time.
            if (update.isAutoApply() && !update.isAutoRestart()) {
                synchronized (settingsLock) {
                    update.setAutoApply(false);
                }
                throw new IllegalArgumentException("自动应用需要同时开启自动重启，否则更新完不会有人把服务拉起来");
            }
        }
        log.info("update settings: autoDownload={} autoApply={} autoRestart={}",
                update.isAutoDownload(), update.isAutoApply(), update.isAutoRestart());
        return status();
    }

    /**
     * Reads a JSON boolean leniently.
     *
     * The body arrives as an untyped map, so a value can be a Boolean, the string "true" from a
     * hand-written curl, or a number. Anything unrecognised keeps the current value instead of
     * flipping a safety-relevant switch by accident.
     */
    private static boolean flag(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            String normalized = text.strip().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized) || "1".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized) || "0".equals(normalized)) {
                return false;
            }
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return fallback;
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
