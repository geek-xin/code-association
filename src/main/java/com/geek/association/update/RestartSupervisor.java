package com.geek.association.update;

import com.geek.association.config.AppProperties;
import com.geek.association.util.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

/**
 * Brings the process back after {@link UpdateService#apply()} replaced the jar on disk.
 *
 * The hard part is not starting java; it is that the process doing the work is the one that has to
 * die. Everything here follows from that single constraint:
 *
 * <ul>
 *   <li><b>The restart is run by a script, not by this JVM.</b> A thread cannot wait for its own
 *       process to exit, and a JVM that kept running would hold the port the new build needs. The
 *       supervisor therefore writes a small POSIX shell script to {@code {dataDir}/restart/} and
 *       starts it detached; the script outlives this process, waits for the old PID to disappear,
 *       starts the new jar and polls the health endpoint;</li>
 *   <li><b>The exit is delayed and delegated.</b> Calling {@code System.exit} inline would cut the
 *       HTTP response that told the browser the update succeeded - the user would see a failed
 *       request for an update that actually worked. The exit goes through an injected action, runs
 *       on a daemon thread after a short delay, and is the last thing this process does;</li>
 *   <li><b>Failure ends in the previous jar, not in a dead installation.</b> A build that never
 *       answers its health check is replaced by the backup {@link UpdateService} kept beside the
 *       jar and started again, so the worst case is one restart cycle of downtime;</li>
 *   <li><b>The state is readable from two processes.</b> What this JVM knows lives in memory, what
 *       the script knows is written to {@code {dataDir}/restart-status.json}; {@link #state()}
 *       merges them, so a freshly started instance can still report that the attempt before it
 *       rolled back instead of claiming nothing ever happened.</li>
 * </ul>
 *
 * Nothing here throws at a caller: it runs behind a request that has already succeeded, and the
 * only useful thing it can report is a state, which {@link #state()} serves.
 */
@Component
public class RestartSupervisor {

    private static final Logger log = LoggerFactory.getLogger(RestartSupervisor.class);

    /** Subdirectory of the data directory that holds the generated script. */
    static final String SCRIPT_DIRECTORY = "restart";
    /** Name of the generated script; rewritten on every restart, so it never drifts from this code. */
    static final String SCRIPT_NAME = "restart.sh";
    /** Where the generated script and the new process write their output. */
    static final String LOG_NAME = "restart.log";
    /**
     * How long the exit waits after the restart is accepted.
     *
     * The HTTP response has to reach the browser first: it carries the "applied" payload the page
     * needs to show progress, and a reset connection instead of that payload looks like a failed
     * update. One and a half seconds is well past a localhost write and short enough to be
     * invisible.
     */
    static final long EXIT_DELAY_MILLIS = 1500L;
    /** How long the in-process fallback waits for a health check before giving up on it. */
    private static final long POLL_INTERVAL_MILLIS = 500L;
    /** The health endpoint {@code start.sh} already uses, so "healthy" means the same thing here. */
    private static final String HEALTH_PATH = "/api/health";

    private final AppProperties properties;
    private final UpdateHistory history;
    private final int port;
    private final ProcessLauncher launcher;
    private final HealthProbe probe;
    private final IntConsumer exitAction;

    /** True from the moment a restart is accepted until its outcome is known. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Serialises the read-modify-write of {@link #status}; never held during I/O. */
    private final Object statusLock = new Object();

    /** What this process knows about the current (or last) attempt. */
    private RestartStatus status = RestartStatus.idle();

    /**
     * Why hot restart is impossible here, or null when it is possible.
     *
     * Computed on first use rather than in the constructor: the jar lookup goes through
     * {@link #runningJar()}, which a test overrides, and a field read during construction would see
     * the subclass's field before it is assigned.
     */
    private volatile String unsupportedReason;
    private volatile boolean unsupportedComputed;

    /** Exit delay, overridable so a test does not have to wait for the real one. */
    private volatile long exitDelayMillis = EXIT_DELAY_MILLIS;

    /**
     * The Spring form.
     *
     * The port comes from {@code server.port} rather than from a constant: {@code --server.port}
     * and {@code SERVER_PORT} both land there, and a supervisor that probed 9996 on an installation
     * running on 8080 would see a healthy service as dead and roll back every single update.
     *
     * The ledger is created here instead of being injected because {@link UpdateService} already
     * owns it: two owners writing one file would turn a bookkeeping convenience into a corruption
     * risk, and this class only ever appends to it.
     */
    @Autowired
    public RestartSupervisor(AppProperties properties, @Value("${server.port:9996}") int port) {
        this(properties, new UpdateHistory(properties), port);
    }

    /** The production form: the real process launcher, an HTTP health probe and {@code System.exit}. */
    public RestartSupervisor(AppProperties properties, UpdateHistory history, int port) {
        this(properties, history, port,
                new ProcessLauncher.DefaultProcessLauncher(),
                RestartSupervisor::httpHealth,
                System::exit);
    }

    /**
     * The injectable form, which is the only reason a restart can be tested at all.
     *
     * A test that really started a JVM would occupy a port, outlive the test run and could not
     * assert on the outcome; a test that really called {@code System.exit} would kill the build.
     * Both ends are therefore seams.
     */
    RestartSupervisor(AppProperties properties, UpdateHistory history, int port,
                      ProcessLauncher launcher, HealthProbe probe, IntConsumer exitAction) {
        this.properties = properties;
        this.history = history;
        this.port = port;
        this.launcher = launcher;
        this.probe = probe;
        this.exitAction = exitAction;
    }

    // ------------------------------------------------------------------- api

    /** Whether this installation can restart itself: it needs a jar to start again and a POSIX shell. */
    public boolean isSupported() {
        return unsupportedReason() == null;
    }

    /**
     * Why a hot restart is impossible here, in a form the settings page can show as-is.
     *
     * Null when it is possible. Saying it up front is friendlier than offering a button that can
     * only fail, and it is the same condition {@link #scheduleRestart(String)} refuses on.
     */
    public String unsupportedReason() {
        if (!unsupportedComputed) {
            synchronized (this) {
                if (!unsupportedComputed) {
                    unsupportedReason = detectUnsupportedReason();
                    unsupportedComputed = true;
                }
            }
        }
        return unsupportedReason;
    }

    private String detectUnsupportedReason() {
        if (isWindows()) {
            // Windows holds an exclusive lock on a running executable, so the jar cannot be
            // replaced and a shell script is not the way to start a service there either.
            return "Windows 上无法替换正在运行的 jar，请停止服务后手动更新";
        }
        if (runningJar() == null) {
            // Named by what the process is, not by the one example that used to be assumed: the
            // usual case here is mvn spring-boot:run, which is neither an IDE nor a test run.
            LaunchMode mode = LaunchMode.detect();
            if (mode == LaunchMode.DEV) {
                return "当前是开发模式启动（mvn spring-boot:run、IDE 或测试直接运行 target/classes），"
                        + "没有可替换的 jar，无法自动重启；打包后用 ./start.sh 启动即可";
            }
            return "没有定位到正在运行的 jar，无法自动重启；用 java -jar 启动打包后的 jar 即可";
        }
        if (!Files.isExecutable(Path.of("/bin/sh"))) {
            return "未找到 /bin/sh，无法启动重启守护脚本";
        }
        return null;
    }

    /**
     * Accepts one restart and returns immediately.
     *
     * The work belongs to the script: this method only prepares what it needs, starts it and
     * schedules this JVM's exit. Everything slow - waiting for the old process, booting the new
     * one, polling the health endpoint, rolling back - happens after this process is gone.
     *
     * @param appliedVersion the version just written to disk, for the log and the ledger only
     * @return true when the restart was accepted, false when it is unsupported or one is already
     *         under way (a second one would race the first for the same port)
     */
    public boolean scheduleRestart(String appliedVersion) {
        String reason = unsupportedReason();
        if (reason != null) {
            log.warn("hot restart refused: {}", reason);
            return false;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("hot restart refused: another restart is already running");
            return false;
        }
        long now = System.currentTimeMillis();
        try {
            Path jar = runningJar();
            if (jar == null) {
                // Only reachable when the jar disappeared between the check and here; the answer is
                // the same, and the flag must be released so a later attempt can try again.
                failEarly("找不到正在运行的 jar，无法热重启", now, null);
                return false;
            }
            Path dataDir = properties.dataPath();
            Path directory = dataDir.resolve(SCRIPT_DIRECTORY);
            Files.createDirectories(directory);
            Path script = directory.resolve(SCRIPT_NAME);
            Path statusFile = statusFile();
            Path backup = latestBackup(jar);
            Path logFile = logFile();
            Files.writeString(script, daemonScript(), StandardCharsets.UTF_8);
            makeExecutable(script);

            // A stale status file would make the page report the previous attempt's outcome as if
            // it were this one, so every attempt starts from a clean slate.
            RestartStatus fresh = new RestartStatus();
            fresh.setPhase(RestartStatus.PHASE_SPAWNING);
            fresh.setStartedAt(now);
            fresh.setAttemptedVersion(appliedVersion);
            fresh.setMessage("正在准备重启");
            synchronized (statusLock) {
                status = fresh;
            }
            RestartStatus.write(statusFile, fresh);

            launcher.start(daemonCommand(script, statusFile, jar, backup, dataDir, appliedVersion), logFile);
            // Deliberately memory-only: the file keeps saying "spawning" until the daemon script
            // overwrites it with "waiting". That difference is the handover signal the in-process
            // watcher uses below to decide whether the script is really in charge.
            updateStatusInMemory(RestartStatus.PHASE_WAITING, "已交给守护脚本，等待旧进程退出", false);
            log.info("restart to {} accepted; daemon {} will start {}", appliedVersion, script, jar);

            startFallbackWorker(appliedVersion, jar, backup, statusFile);
            scheduleExit();
            return true;
        } catch (IOException | RuntimeException e) {
            // The jar has already been replaced, so the update itself succeeded; only the automatic
            // restart did not. Report that honestly instead of pretending a restart is running.
            log.warn("could not start the restart daemon: {}", e.toString());
            failEarly("无法启动重启守护脚本：" + describe(e), now, appliedVersion);
            return false;
        }
    }

    /**
     * The restart state as {@code GET /api/update} reports it.
     *
     * The in-memory attempt wins while it is running - it is the newest fact - and the status file
     * fills in what this process cannot know: an attempt that finished after the previous process
     * exited, or one this JVM inherited from its predecessor. That merge is why the page can show
     * "rolled back" on a freshly started instance instead of "idle".
     */
    public Map<String, Object> state() {
        RestartStatus current = snapshotStatus();
        RestartStatus fromDisk = RestartStatus.read(statusFile());
        boolean inFlight = running.get();
        String phase = current.getPhase();
        Long startedAt = current.getStartedAt();
        Long finishedAt = current.getFinishedAt();
        String message = current.getMessage();
        String attemptedVersion = current.getAttemptedVersion();
        boolean rolledBack = current.isRolledBack();
        if (fromDisk != null) {
            if (Texts.isBlank(attemptedVersion)) {
                attemptedVersion = fromDisk.getAttemptedVersion();
            }
            if (startedAt == null) {
                startedAt = fromDisk.getStartedAt();
            }
            // While this JVM is running an attempt it is the authority on its own phase; otherwise
            // the script's verdict is the one that actually happened, because the script is the
            // process that observed the health check and any rollback.
            if (!inFlight) {
                phase = fromDisk.getPhase();
                finishedAt = fromDisk.getFinishedAt();
                message = fromDisk.getMessage();
                rolledBack = fromDisk.isRolledBack();
            }
        }
        if (Texts.isBlank(phase)) {
            phase = RestartStatus.PHASE_IDLE;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("supported", isSupported());
        out.put("unsupportedReason", unsupportedReason());
        // "running" is reported right next to "phase", and neither is read under a lock: an attempt
        // that finished between the two reads used to come back as "finished and still running" at
        // the same time. A terminal phase therefore decides - every path that sets one has already
        // stopped working on the attempt, while the flag is cleared after the status write.
        out.put("running", isInFlight(phase));
        out.put("phase", phase);
        out.put("startedAt", startedAt);
        out.put("finishedAt", finishedAt);
        out.put("attemptedVersion", attemptedVersion);
        out.put("message", message);
        out.put("rolledBack", rolledBack);
        out.put("healthUrl", healthUrl());
        out.put("logFile", logFile().toAbsolutePath().toString());
        return out;
    }

    // --------------------------------------------------------------- internals

    /** Seam for tests: where this process was started from. */
    Path runningJar() {
        return JarLocation.currentJar();
    }

    /** Seam for tests: keeps the real exit delay out of the test run time. */
    RestartSupervisor exitDelay(long millis) {
        this.exitDelayMillis = millis;
        return this;
    }

    Path statusFile() {
        return properties.dataPath().resolve(RestartStatus.FILE_NAME);
    }

    Path logFile() {
        return properties.dataPath().resolve(LOG_NAME);
    }

    String healthUrl() {
        return "http://127.0.0.1:" + port + HEALTH_PATH;
    }

    private static boolean isInFlight(String phase) {
        return RestartStatus.PHASE_SPAWNING.equals(phase) || RestartStatus.PHASE_WAITING.equals(phase);
    }

    private RestartStatus snapshotStatus() {
        synchronized (statusLock) {
            return status.copy();
        }
    }

    private void updateStatus(Path statusFile, String phase, String message, boolean rolledBack) {
        RestartStatus next;
        synchronized (statusLock) {
            next = status.copy();
            next.setPhase(phase);
            next.setMessage(message);
            next.setRolledBack(rolledBack);
            if (!isInFlight(phase)) {
                next.setFinishedAt(System.currentTimeMillis());
            }
            status = next;
        }
        RestartStatus.write(statusFile, next);
    }

    /**
     * Advances the in-memory attempt without touching the status file.
     *
     * Used for the one transition the daemon script must be able to contradict: "the script was
     * started" is this process's claim, while "the script is waiting for the old PID" is the
     * script's own. Keeping them distinguishable is what lets the in-process watcher below notice
     * that the script never came up at all.
     */
    private void updateStatusInMemory(String phase, String message, boolean rolledBack) {
        synchronized (statusLock) {
            RestartStatus next = status.copy();
            next.setPhase(phase);
            next.setMessage(message);
            next.setRolledBack(rolledBack);
            status = next;
        }
    }

    private void failEarly(String message, long startedAt, String version) {
        RestartStatus failed = new RestartStatus();
        failed.setPhase(RestartStatus.PHASE_FAILED);
        failed.setStartedAt(startedAt);
        failed.setFinishedAt(System.currentTimeMillis());
        failed.setAttemptedVersion(version);
        failed.setMessage(message);
        synchronized (statusLock) {
            status = failed;
        }
        running.set(false);
        RestartStatus.write(statusFile(), failed);
    }

    /**
     * Exits shortly after the response is on its way, on a daemon thread.
     *
     * Daemon because a thread that outlived the process would be a leak; delayed because the caller
     * is an HTTP handler that still has to flush its body. The action is injected so a test can
     * assert the exit happened exactly once without ending the test JVM.
     */
    private void scheduleExit() {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(exitDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            log.info("exiting so the restart daemon can start the new build");
            try {
                exitAction.accept(0);
            } catch (RuntimeException e) {
                log.warn("exit action failed: {}", e.toString());
            }
        }, "update-restart-exit");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Watches an attempt whose daemon script never took over.
     *
     * In production the exit above ends this process, the script owns the restart, and this watcher
     * sees the script's own status file within a second and stands down. It exists for the case
     * where that handover does not happen - a replaced exit action, a shutdown hook that blocks, a
     * script that could not be executed - because then nothing at all would observe the outcome and
     * the settings page would poll a phase that never moves.
     *
     * Standing down is the important half: the script starts the new JVM, so a watcher that also
     * started one would race it for the port and turn a healthy update into a rollback. The watcher
     * therefore never launches a process while the script is alive; it only reports what it sees.
     */
    private void startFallbackWorker(String appliedVersion, Path jar, Path backup, Path statusFile) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(exitDelayMillis + POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                if (daemonTookOver(statusFile)) {
                    log.debug("the restart daemon took over {}; nothing left to watch here", appliedVersion);
                    return;
                }
                long timeoutMillis = Math.max(1, properties.getUpdate().getRestartTimeoutSeconds()) * 1000L;
                if (awaitHealthy(System.currentTimeMillis() + timeoutMillis)) {
                    // Clear the flag before publishing the terminal phase: the status file write is
                    // slow enough (ms) for a poller to read the phase and the flag in between.
                    running.set(false);
                    updateStatus(statusFile, RestartStatus.PHASE_SUCCEEDED,
                            "新版本已启动并通过健康检查", false);
                    return;
                }
                log.warn("the new build did not answer {}; rolling back to {}", healthUrl(), backup);
                if (backup == null || !Files.isRegularFile(backup)) {
                    running.set(false);
                    updateStatus(statusFile, RestartStatus.PHASE_FAILED,
                            "新版本未通过健康检查，且没有可用的备份 jar", false);
                    return;
                }
                updateStatus(statusFile, RestartStatus.PHASE_WAITING, "新版本未通过健康检查，正在回滚", true);
                Files.copy(backup, jar, StandardCopyOption.REPLACE_EXISTING);
                history.markRolledBack(backupVersion(jar, backup), Instant.now());
                launcher.start(javaCommand(jar), logFile());
                boolean recovered = awaitHealthy(System.currentTimeMillis() + timeoutMillis);
                running.set(false);
                if (recovered) {
                    updateStatus(statusFile, RestartStatus.PHASE_ROLLED_BACK,
                            "新版本未通过健康检查，已回滚并重新启动旧版本", true);
                } else {
                    updateStatus(statusFile, RestartStatus.PHASE_FAILED,
                            "回滚后旧版本仍未通过健康检查，请查看日志", true);
                }
            } catch (IOException | RuntimeException e) {
                log.warn("in-process restart supervision failed: {}", e.toString());
                running.set(false);
                updateStatus(statusFile, RestartStatus.PHASE_FAILED, "重启失败：" + describe(e), true);
            }
        }, "update-restart-watch-" + appliedVersion);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Whether the daemon script has written its own phase, i.e. it is really running.
     *
     * This process left the file at {@code spawning}; the first thing the script does is write
     * {@code waiting}. Any other phase means the script spoke, which is all this needs to know.
     */
    private boolean daemonTookOver(Path statusFile) {
        RestartStatus fromDisk = RestartStatus.read(statusFile);
        if (fromDisk == null) {
            return false;
        }
        String phase = fromDisk.getPhase();
        return !RestartStatus.PHASE_SPAWNING.equals(phase) && !RestartStatus.PHASE_IDLE.equals(phase);
    }

    /** True once the health endpoint answered, retrying until the deadline. */
    private boolean awaitHealthy(long deadlineMillis) {
        while (System.currentTimeMillis() < deadlineMillis) {
            boolean up;
            try {
                up = probe.healthy(healthUrl());
            } catch (RuntimeException e) {
                // A probe that throws is a probe that failed; the retry loop is the recovery.
                up = false;
            }
            if (up) {
                return true;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private List<String> daemonCommand(Path script, Path statusFile, Path jar, Path backup,
                                       Path dataDir, String version) {
        List<String> command = new ArrayList<>();
        command.add(Files.isExecutable(Path.of("/bin/sh")) ? "/bin/sh" : "sh");
        command.add(script.toAbsolutePath().toString());
        command.add(Long.toString(ProcessHandle.current().pid()));
        command.add(healthUrl());
        command.add(Integer.toString(Math.max(1, properties.getUpdate().getRestartTimeoutSeconds())));
        command.add(statusFile.toAbsolutePath().toString());
        command.add(backup == null ? "" : backup.toAbsolutePath().toString());
        command.add(jar.toAbsolutePath().toString());
        command.add(Integer.toString(port));
        command.add(dataDir.toAbsolutePath().toString());
        command.add(version == null ? "" : version);
        command.add("--");
        command.addAll(javaCommand(jar));
        return command;
    }

    /** {@code java [-Xmx...] -jar <jar> --server.port=<port> --code-association.data-dir=<dir>}. */
    private List<String> javaCommand(Path jar) {
        List<String> command = ProcessLauncher.javaCommand();
        command.add("-jar");
        command.add(jar.toAbsolutePath().toString());
        command.add("--server.port=" + port);
        command.add("--code-association.data-dir=" + properties.dataPath());
        return command;
    }

    /**
     * The health check used in production.
     *
     * Loopback, two second timeout, any failure means "not up yet": during a restart the connection
     * is refused for a while by design, and that is an ordinary answer rather than an error.
     */
    static boolean httpHealth(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(2))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException | RuntimeException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    private static void makeExecutable(Path script) {
        try {
            Files.setPosixFilePermissions(script, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (IOException | UnsupportedOperationException e) {
            // The script is started through /bin/sh, so the execute bit is a courtesy, not a
            // requirement; a filesystem without POSIX permissions must not fail the restart.
            log.debug("could not mark {} executable: {}", script, e.toString());
        }
    }

    private static String backupVersion(Path jar, Path backup) {
        String prefix = jar.getFileName() + ".bak-";
        String name = backup.getFileName().toString();
        String version = name.startsWith(prefix) ? name.substring(prefix.length()) : "";
        return version.isBlank() ? "unknown" : version;
    }

    /** The newest {@code <jar>.bak-*} beside the running jar, or null when there is none. */
    private static Path latestBackup(Path jar) {
        Path directory = jar.getParent();
        if (directory == null) {
            return null;
        }
        String prefix = jar.getFileName() + ".bak-";
        List<Path> backups = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .forEach(backups::add);
        } catch (IOException e) {
            log.warn("could not list backups in {}: {}", directory, e.toString());
            return null;
        }
        if (backups.isEmpty()) {
            return null;
        }
        backups.sort(Comparator.comparingLong(RestartSupervisor::lastModified).reversed());
        return backups.get(0);
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return Texts.isBlank(message) ? e.getClass().getSimpleName() : message;
    }

    // ---------------------------------------------------------- daemon script

    /**
     * The POSIX shell script that owns the restart once this process is gone.
     *
     * Generated here rather than shipped as a resource so the script can never drift from the code
     * that calls it, and so the arguments it needs (old PID, jar, port, data directory, health URL,
     * timeout, backup jar) are visible in one place. It is plain POSIX {@code sh} on purpose: the
     * service already assumes a shell to be started at all, and adding a python3 dependency would
     * make the feature fail on the machines that need it most.
     */
    static String daemonScript() {
        return DAEMON_SCRIPT;
    }

    /** The script itself; a text block so it can be read as a script rather than as a string. */
    private static final String DAEMON_SCRIPT = """
        #!/bin/sh
        # Restart daemon for code-association. Generated by RestartSupervisor on every restart; do not edit.
        #
        # Why a separate script instead of a thread inside the JVM: the process that asked for the restart
        # has to die before the new one can bind the port, so the work must outlive it. This script is the
        # survivor. It waits for the old PID to disappear, starts the new jar, polls the health endpoint and,
        # when the new build never answers, copies the backup jar back and starts the old build again.
        #
        # usage: restart.sh OLD_PID HEALTH_URL TIMEOUT_SECONDS STATUS_FILE BACKUP_JAR JAR PORT DATA_DIR VERSION -- JAVA_COMMAND...
        set -u

        OLD_PID="$1"
        HEALTH_URL="$2"
        TIMEOUT="$3"
        STATUS_FILE="$4"
        BACKUP_JAR="$5"
        JAR="$6"
        PORT="$7"
        DATA_DIR="$8"
        VERSION="$9"
        shift 9
        if [ "$#" -gt 0 ] && [ "$1" = "--" ]; then
          shift
        fi

        LOG_FILE="$DATA_DIR/restart.log"
        STARTED_AT="$(date +%s)000"
        NEW_PID=""

        # The status file is the only channel to the process that replaces this one, so it is written to a
        # temporary file and renamed: the reader is a JVM booting at an arbitrary moment and must never see
        # half a document.
        write_status() {
          phase="$1"
          finished="$2"
          message="$3"
          rolled="$4"
          cat > "$STATUS_FILE.tmp" <<EOF
        {"phase":"$phase","startedAt":$STARTED_AT,"finishedAt":$finished,"attemptedVersion":"$VERSION","message":"$message","rolledBack":$rolled}
        EOF
          mv -f "$STATUS_FILE.tmp" "$STATUS_FILE" 2>/dev/null || true
        }

        http_ok() {
          if command -v curl >/dev/null 2>&1; then
            curl -fsS --max-time 2 "$HEALTH_URL" >/dev/null 2>&1
            return $?
          fi
          if command -v python3 >/dev/null 2>&1; then
            python3 - "$HEALTH_URL" <<PY
        import sys, urllib.request
        try:
            with urllib.request.urlopen(sys.argv[1], timeout=2) as response:
                sys.exit(0 if response.status == 200 else 1)
        except Exception:
            sys.exit(1)
        PY
            return $?
          fi
          # No HTTP client at all: a listening port is the best signal this machine can offer.
          if command -v nc >/dev/null 2>&1; then
            nc -z 127.0.0.1 "$PORT" >/dev/null 2>&1
            return $?
          fi
          return 0
        }

        start_java() {
          if command -v setsid >/dev/null 2>&1; then
            setsid "$@" >>"$LOG_FILE" 2>&1 </dev/null &
          else
            "$@" >>"$LOG_FILE" 2>&1 </dev/null &
          fi
          NEW_PID=$!
        }

        # Bounded on purpose: a process that ignores SIGTERM must not keep the restart stuck forever.
        wait_old_pid_gone() {
          waited=0
          while kill -0 "$OLD_PID" 2>/dev/null; do
            if [ "$waited" -ge 300 ]; then
              echo "old process $OLD_PID did not exit within 60s, sending SIGKILL" >>"$LOG_FILE"
              kill -9 "$OLD_PID" 2>/dev/null || true
              sleep 1
              return 0
            fi
            sleep 0.2
            waited=$((waited + 1))
          done
          return 0
        }

        wait_healthy() {
          waited=0
          limit=$((TIMEOUT * 2))
          while [ "$waited" -lt "$limit" ]; do
            if http_ok; then
              return 0
            fi
            # A process that is already gone will never become healthy; stop waiting for it.
            if [ -n "$NEW_PID" ] && ! kill -0 "$NEW_PID" 2>/dev/null; then
              return 1
            fi
            sleep 0.5
            waited=$((waited + 1))
          done
          return 1
        }

        write_status waiting null "等待旧进程退出" false
        wait_old_pid_gone

        write_status waiting null "正在启动新版本" false
        start_java "$@"

        if wait_healthy; then
          write_status succeeded "$(date +%s)000" "新版本已启动并通过健康检查" false
          exit 0
        fi

        echo "new build did not answer $HEALTH_URL within ${TIMEOUT}s" >>"$LOG_FILE"
        if [ -n "$NEW_PID" ]; then
          kill -9 "$NEW_PID" 2>/dev/null || true
        fi

        if [ -n "$BACKUP_JAR" ] && [ -f "$BACKUP_JAR" ]; then
          write_status waiting null "新版本未通过健康检查，正在回滚" true
          cp -f "$BACKUP_JAR" "$JAR" >>"$LOG_FILE" 2>&1
          start_java "$@"
          if wait_healthy; then
            write_status rolled-back "$(date +%s)000" "新版本未通过健康检查，已回滚并重新启动旧版本" true
            exit 0
          fi
          write_status failed "$(date +%s)000" "回滚后旧版本仍未通过健康检查，请查看日志" true
          exit 1
        fi

        write_status failed "$(date +%s)000" "新版本未通过健康检查，且没有可用的备份 jar" false
        exit 1
        """;

    /**
     * How the supervisor decides that a freshly started process is up.
     *
     * A functional interface so a test can answer from a scripted sequence instead of opening a
     * socket: the real implementation is {@link #httpHealth(String)}, and the tests never touch the
     * network.
     */
    @FunctionalInterface
    public interface HealthProbe {
        /** True when the endpoint answered 200; any failure (refused, timeout) is simply false. */
        boolean healthy(String url);
    }
}
