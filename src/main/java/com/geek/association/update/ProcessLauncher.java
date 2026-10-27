package com.geek.association.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Starts a detached process and lets its output go to a log file.
 *
 * This is the seam that keeps {@link RestartSupervisor} testable. A restart ends with a new JVM
 * being started by a script, and a unit test must never do that: it would occupy a port, outlive
 * the test run and be impossible to assert on. Everything the supervisor needs from the outside
 * world therefore goes through this one method, which a test replaces with a recorder.
 *
 * The command list is also where the "restart me the way I was started" rule lives: the daemon
 * script has to run the same JVM binary with the same flags, otherwise a restart silently drops
 * {@code -Xmx} or a {@code -D} property the operator set.
 */
public interface ProcessLauncher {

    /**
     * Starts {@code command}, sending its stdout and stderr to {@code logFile}.
     *
     * Implementations must return promptly and must not wait for the process: the caller is an
     * HTTP request thread on its way to answering the browser.
     */
    Process start(List<String> command, Path logFile) throws IOException;

    /**
     * The {@link ProcessLauncher} used in production: {@link ProcessBuilder}, output appended to
     * the restart log, and a child that does not keep this JVM's stdin open.
     */
    final class DefaultProcessLauncher implements ProcessLauncher {

        private static final Logger log = LoggerFactory.getLogger(DefaultProcessLauncher.class);

        @Override
        public Process start(List<String> command, Path logFile) throws IOException {
            if (command == null || command.isEmpty()) {
                throw new IOException("empty command");
            }
            ProcessBuilder builder = new ProcessBuilder(command);
            if (logFile != null) {
                Path directory = logFile.getParent();
                if (directory != null) {
                    Files.createDirectories(directory);
                }
                File file = logFile.toFile();
                // Redirected to a file rather than piped: nobody reads the pipe, and a full pipe
                // buffer would block the child forever.
                builder.redirectOutput(ProcessBuilder.Redirect.appendTo(file));
                builder.redirectError(ProcessBuilder.Redirect.appendTo(file));
            }
            builder.redirectInput(ProcessBuilder.Redirect.from(new File(devNull())));
            log.info("starting restart process: {}", String.join(" ", command));
            return builder.start();
        }

        private static String devNull() {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")
                    ? "NUL" : "/dev/null";
        }
    }

    /**
     * The command line for {@code java} itself, reused by anything that has to start a JVM.
     *
     * Built from this process rather than from {@code PATH}: the installation may run on a JDK that
     * is not the default one, and {@code java.home} of the running JVM is the binary that is known
     * to work. The JVM input arguments ({@code -Xmx}, {@code -Dhttps.proxyHost}, ...) are carried
     * over because a restart that loses them starts a differently configured service.
     */
    static List<String> javaCommand() {
        List<String> command = new ArrayList<>();
        command.add(javaBinary());
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        return command;
    }

    /** This JVM's executable, with a {@code java.home} fallback for platforms that report no path. */
    static String javaBinary() {
        try {
            String command = ProcessHandle.current().info().command().orElse(null);
            if (command != null && !command.isBlank()) {
                return command;
            }
        } catch (RuntimeException e) {
            // ProcessHandle is best-effort and the fallback below is always available, so a
            // platform that refuses to report its own command is not worth failing a restart over.
        }
        String home = System.getProperty("java.home");
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")
                ? "java.exe" : "java";
        return home == null || home.isBlank() ? executable : Path.of(home, "bin", executable).toString();
    }
}
