package com.geek.association.update;

import com.geek.association.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The restart supervisor, exercised without ever starting a JVM or ending the test run.
 *
 * Three seams make that possible and every test here goes through them: {@link ProcessLauncher}
 * records the command instead of running it, {@link RestartSupervisor.HealthProbe} answers from a
 * scripted sequence instead of a socket, and the exit action counts calls instead of calling
 * {@code System.exit}. The generated daemon script is inspected as text - running it would start a
 * real process, occupy a port and outlive the test.
 *
 * What is worth pinning down:
 *
 * <ul>
 *   <li><b>Unsupported means refused, with a reason the UI can show.</b> An installation running
 *       from a directory has nothing to restart into;</li>
 *   <li><b>A healthy new build ends the attempt, an unhealthy one restores the backup.</b> The
 *       rollback is the promise the whole feature rests on;</li>
 *   <li><b>One restart at a time.</b> Two would race for the same port and the same jar;</li>
 *   <li><b>The exit happens exactly once, after the response.</b> Anything else either kills the
 *       HTTP reply or leaves the old process holding the port.</li>
 * </ul>
 */
class RestartSupervisorTest {

    private static final String VERSION = "0.6.0";
    private static final String BACKUP_SUFFIX = ".bak-0.5.0";

    // ---------------------------------------------------------------- fixtures

    /** Records the command lines the supervisor would have run. */
    private static final class RecordingLauncher implements ProcessLauncher {
        final List<List<String>> commands = new ArrayList<>();
        final List<Path> logFiles = new ArrayList<>();

        @Override
        public Process start(List<String> command, Path logFile) {
            commands.add(List.copyOf(command));
            logFiles.add(logFile);
            // The supervisor ignores the return value; a restart is supervised through the probe.
            return null;
        }
    }

    /**
     * A probe whose answer depends on which build is actually on disk.
     *
     * A scripted sequence would make the test depend on how many times the poll loop happens to
     * run, which is wall-clock dependent and therefore flaky. "The build answers its health check"
     * is a property of the jar, so the fake is written as exactly that: the good build is healthy,
     * the bad one never is. That is also what makes the rollback assertion meaningful - the
     * restored jar is what makes the endpoint answer again.
     */
    private static final class BuildAwareProbe implements RestartSupervisor.HealthProbe {
        private final Path jar;
        private final String healthyContent;
        final AtomicInteger calls = new AtomicInteger();

        BuildAwareProbe(Path jar, String healthyContent) {
            this.jar = jar;
            this.healthyContent = healthyContent;
        }

        @Override
        public boolean healthy(String url) {
            calls.incrementAndGet();
            try {
                return healthyContent.equals(Files.readString(jar, StandardCharsets.UTF_8));
            } catch (IOException e) {
                return false;
            }
        }
    }

    /** A supervisor whose "running jar" is a file in the test's temporary directory. */
    private static final class TestSupervisor extends RestartSupervisor {
        private final Path jar;

        TestSupervisor(AppProperties properties, UpdateHistory history, int port, Path jar,
                       ProcessLauncher launcher, HealthProbe probe, java.util.function.IntConsumer exit) {
            super(properties, history, port, launcher, probe, exit);
            this.jar = jar;
            // The real delay exists for the browser; a test only needs the exit to be observable.
            exitDelay(20L);
        }

        @Override
        Path runningJar() {
            return jar;
        }
    }

    private static AppProperties props(Path dataDir, int timeoutSeconds) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(dataDir.toString());
        properties.getUpdate().setRestartTimeoutSeconds(timeoutSeconds);
        return properties;
    }

    /** Lays out an installation: the running jar, its backup, and the data directory beside them. */
    private static Path install(Path root, boolean withBackup) throws IOException {
        Path app = Files.createDirectories(root.resolve("app"));
        Path jar = app.resolve("code-association.jar");
        Files.writeString(jar, "new build", StandardCharsets.UTF_8);
        if (withBackup) {
            Files.writeString(app.resolve(jar.getFileName() + BACKUP_SUFFIX), "old build",
                    StandardCharsets.UTF_8);
        }
        return jar;
    }

    private static void skipOnWindows() {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"),
                "the daemon script is POSIX sh");
    }

    /** Polls until the condition holds, so a test never depends on a fixed sleep. */
    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static Object stateValue(Map<String, Object> state, String key) {
        assertTrue(state.containsKey(key), "state() is missing the key " + key);
        return state.get(key);
    }

    // -------------------------------------------------------------- supported

    @Test
    void refusesToRestartWithoutAJarAndSaysWhy(@TempDir Path root) {
        skipOnWindows();
        RecordingLauncher launcher = new RecordingLauncher();
        AtomicInteger exits = new AtomicInteger();
        TestSupervisor supervisor = new TestSupervisor(props(root, 5),
                new UpdateHistory(root.resolve("update-history.json")), 9996, null,
                launcher, url -> true, code -> exits.incrementAndGet());

        assertFalse(supervisor.isSupported());
        assertNotNull(supervisor.unsupportedReason());
        assertFalse(supervisor.unsupportedReason().isBlank());
        assertFalse(supervisor.scheduleRestart(VERSION));
        assertTrue(launcher.commands.isEmpty(), "an unsupported restart must not start anything");
        assertEquals(0, exits.get());
        assertEquals(false, supervisor.state().get("supported"));
    }

    @Test
    void reportsEveryKeyTheFrontendReads(@TempDir Path root) throws IOException {
        skipOnWindows();
        Path jar = install(root, true);
        TestSupervisor supervisor = new TestSupervisor(props(root, 5),
                new UpdateHistory(root.resolve("update-history.json")), 9996, jar,
                new RecordingLauncher(), url -> false, code -> { });

        Map<String, Object> state = supervisor.state();
        for (String key : List.of("supported", "unsupportedReason", "running", "phase", "startedAt",
                "finishedAt", "attemptedVersion", "message", "rolledBack", "healthUrl", "logFile")) {
            assertTrue(state.containsKey(key), "state() is missing the key " + key);
        }
        assertEquals("idle", stateValue(state, "phase"));
        assertEquals(false, stateValue(state, "running"));
        assertEquals(false, stateValue(state, "rolledBack"));
        assertEquals("http://127.0.0.1:9996/api/health", stateValue(state, "healthUrl"));
        assertTrue(((String) stateValue(state, "logFile")).endsWith("restart.log"));
        assertNull(stateValue(state, "unsupportedReason"));
    }

    @Test
    void writesTheDaemonScriptAndStartsItWithTheCurrentJvmArguments(@TempDir Path root) throws IOException {
        skipOnWindows();
        Path jar = install(root, true);
        RecordingLauncher launcher = new RecordingLauncher();
        TestSupervisor supervisor = new TestSupervisor(props(root, 5),
                new UpdateHistory(root.resolve("update-history.json")), 9996, jar,
                launcher, url -> false, code -> { });
        supervisor.exitDelay(60_000L); // the daemon script is what this test inspects

        assertTrue(supervisor.scheduleRestart(VERSION));

        Path script = root.resolve("restart").resolve("restart.sh");
        assertTrue(Files.isRegularFile(script), "the daemon script must be written under {dataDir}/restart");
        String text = Files.readString(script, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("#!/bin/sh"));
        assertTrue(text.contains("kill -0 \"$OLD_PID\""), "the script must wait for the old PID");
        assertTrue(text.contains("cp -f \"$BACKUP_JAR\" \"$JAR\""), "the script must roll back");

        assertEquals(1, launcher.commands.size());
        List<String> command = launcher.commands.get(0);
        assertEquals("/bin/sh", command.get(0));
        assertEquals(script.toAbsolutePath().toString(), command.get(1));
        assertEquals(Long.toString(ProcessHandle.current().pid()), command.get(2));
        assertEquals("http://127.0.0.1:9996/api/health", command.get(3));
        assertTrue(command.contains(jar.toAbsolutePath().toString()), "the jar must be passed to the daemon");
        assertTrue(command.contains("--server.port=9996"), "the port must be reused");
        assertTrue(command.contains("--code-association.data-dir=" + root.toAbsolutePath()));
        assertTrue(command.stream().anyMatch(part -> part.endsWith("java") || part.endsWith("java.exe")),
                "the restart must reuse the current JVM binary");
        assertEquals(root.resolve("restart.log"), launcher.logFiles.get(0));
    }

    // ------------------------------------------------------------- happy path

    @Test
    void aHealthyNewBuildEndsInSucceeded(@TempDir Path root) throws IOException {
        skipOnWindows();
        Path jar = install(root, true);
        RecordingLauncher launcher = new RecordingLauncher();
        AtomicInteger exits = new AtomicInteger();
        // The new build on disk is the one that answers its health check.
        BuildAwareProbe probe = new BuildAwareProbe(jar, "new build");
        TestSupervisor supervisor = new TestSupervisor(props(root, 5),
                new UpdateHistory(root.resolve("update-history.json")), 9996, jar,
                launcher, probe, code -> exits.incrementAndGet());

        assertTrue(supervisor.scheduleRestart(VERSION));
        await("the restart to succeed", () -> "succeeded".equals(supervisor.state().get("phase")));

        Map<String, Object> state = supervisor.state();
        assertEquals("succeeded", state.get("phase"));
        assertEquals(false, state.get("running"));
        assertEquals(false, state.get("rolledBack"));
        assertEquals(VERSION, state.get("attemptedVersion"));
        assertNotNull(state.get("startedAt"));
        assertNotNull(state.get("finishedAt"));
        assertEquals(1, launcher.commands.size(), "a healthy build is started exactly once");
        // The jar on disk is untouched: nothing was rolled back.
        assertEquals("new build", Files.readString(jar, StandardCharsets.UTF_8));
        // The outcome also reaches the file, so a freshly started JVM can report it.
        RestartStatus fromDisk = RestartStatus.read(root.resolve(RestartStatus.FILE_NAME));
        assertNotNull(fromDisk);
        assertEquals("succeeded", fromDisk.getPhase());
    }

    @Test
    void anUnhealthyNewBuildRollsBackToTheBackupJar(@TempDir Path root) throws IOException {
        skipOnWindows();
        Path jar = install(root, true);
        Path backup = root.resolve("app").resolve(jar.getFileName() + BACKUP_SUFFIX);
        RecordingLauncher launcher = new RecordingLauncher();
        // The new build on disk never answers; the restored one does. That is exactly the
        // situation the rollback exists for, and it needs no assumptions about poll timing.
        BuildAwareProbe probe = new BuildAwareProbe(jar, "old build");
        TestSupervisor supervisor = new TestSupervisor(props(root, 1),
                new UpdateHistory(root.resolve("update-history.json")), 9996, jar,
                launcher, probe, code -> { });

        assertTrue(supervisor.scheduleRestart(VERSION));
        await("the restart to roll back", () -> "rolled-back".equals(supervisor.state().get("phase")));

        Map<String, Object> state = supervisor.state();
        assertEquals("rolled-back", state.get("phase"));
        assertEquals(true, state.get("rolledBack"));
        assertEquals(false, state.get("running"));
        // The running jar is the backup again, byte for byte.
        assertArrayEquals(Files.readAllBytes(backup), Files.readAllBytes(jar));
        assertEquals(2, launcher.commands.size(), "the new build and then the restored one");
        assertTrue(launcher.commands.get(1).contains(jar.toAbsolutePath().toString()));
        RestartStatus fromDisk = RestartStatus.read(root.resolve(RestartStatus.FILE_NAME));
        assertNotNull(fromDisk);
        assertEquals("rolled-back", fromDisk.getPhase());
        assertTrue(fromDisk.isRolledBack());
    }

    @Test
    void failsWithoutABackupInsteadOfPretendingToRollBack(@TempDir Path root) throws IOException {
        skipOnWindows();
        Path jar = install(root, false);
        TestSupervisor supervisor = new TestSupervisor(props(root, 1),
                new UpdateHistory(root.resolve("update-history.json")), 9996, jar,
                new RecordingLauncher(), new BuildAwareProbe(jar, "old build"), code -> { });

        assertTrue(supervisor.scheduleRestart(VERSION));
        await("the restart to fail", () -> "failed".equals(supervisor.state().get("phase")));

        assertEquals("failed", supervisor.state().get("phase"));
        assertEquals(false, supervisor.state().get("rolledBack"));
        assertEquals("new build", Files.readString(jar, StandardCharsets.UTF_8));
    }

    // ----------------------------------------------------------- concurrency

    @Test
    void refusesASecondRestartWhileOneIsRunning(@TempDir Path root) throws IOException {
        skipOnWindows();
        Path jar = install(root, true);
        RecordingLauncher launcher = new RecordingLauncher();
        TestSupervisor supervisor = new TestSupervisor(props(root, 5),
                new UpdateHistory(root.resolve("update-history.json")), 9996, jar,
                launcher, url -> false, code -> { });
        supervisor.exitDelay(60_000L); // keeps the first attempt in flight for this test

        assertTrue(supervisor.scheduleRestart(VERSION));
        assertFalse(supervisor.scheduleRestart(VERSION), "a second restart must be refused");
        assertEquals(1, launcher.commands.size());
    }

    // ------------------------------------------------------------------ exit

    @Test
    void exitsExactlyOnceAndOnlyAfterTheDelay(@TempDir Path root) throws IOException {
        skipOnWindows();
        Path jar = install(root, true);
        AtomicInteger exits = new AtomicInteger();
        TestSupervisor supervisor = new TestSupervisor(props(root, 5),
                new UpdateHistory(root.resolve("update-history.json")), 9996, jar,
                new RecordingLauncher(), url -> true, code -> exits.incrementAndGet());

        assertTrue(supervisor.scheduleRestart(VERSION));
        // The response still has to be written when the call returns, so the exit is not immediate.
        assertEquals(0, exits.get());
        await("the exit action to run", () -> exits.get() > 0);
        assertEquals(1, exits.get());
        try {
            Thread.sleep(200L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertEquals(1, exits.get(), "the exit must not be scheduled twice");
    }

    @Test
    void aFailingLauncherIsReportedAsAFailedRestartRatherThanThrown(@TempDir Path root) throws IOException {
        skipOnWindows();
        Path jar = install(root, true);
        ProcessLauncher broken = (command, logFile) -> {
            throw new IOException("cannot fork");
        };
        AtomicInteger exits = new AtomicInteger();
        TestSupervisor supervisor = new TestSupervisor(props(root, 5),
                new UpdateHistory(root.resolve("update-history.json")), 9996, jar,
                broken, url -> true, code -> exits.incrementAndGet());

        assertFalse(supervisor.scheduleRestart(VERSION));
        assertEquals("failed", supervisor.state().get("phase"));
        assertTrue(((String) supervisor.state().get("message")).contains("cannot fork"));
        assertEquals(0, exits.get(), "a restart that never started must not end the process");
        // The flag is released, so the operator can try again.
        assertFalse((Boolean) supervisor.state().get("running"));
    }

    // --------------------------------------------------------------- script

    @Test
    void theDaemonScriptIsPlainPosixShell() {
        String script = RestartSupervisor.daemonScript();
        assertTrue(script.startsWith("#!/bin/sh"));
        // No bashisms: the script has to run where the service runs, and start.sh only assumes sh
        // for its own daemonising path.
        assertFalse(script.contains("[["));
        assertFalse(script.contains("set -euo pipefail"));
        assertTrue(script.contains("HEALTH_URL"));
        assertTrue(script.contains("STATUS_FILE"));
    }
}
