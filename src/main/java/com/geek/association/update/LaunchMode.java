package com.geek.association.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * How this process was started, as far as the self-update feature is concerned.
 *
 * The distinction exists because "can I replace the running jar?" is not a yes/no question with a
 * single cause. It has three answers, and telling them apart is the difference between a message a
 * user can act on ("run ./start.sh instead of mvn spring-boot:run") and one that merely restates
 * the failure ("not running from a jar, maybe an IDE").
 *
 * <p>Everything here is derived from the classloader location, which is the only fact the process
 * actually has. Guessing from the command line would be more precise in theory and wrong in
 * practice: {@code mvn spring-boot:run} forks a second JVM whose command line is a plain
 * {@code java -cp ...}, and a {@code java -cp target/classes} run is a development run as well.
 * The location answers "is there a file I could overwrite?" directly.</p>
 */
public enum LaunchMode {

    /** A packaged Spring Boot jar: the one mode where the running file can be swapped. */
    JAR("jar", "打包后的 jar（./start.sh 或 java -jar 启动）"),

    /**
     * A classes directory: {@code mvn spring-boot:run}, an IDE run configuration, or a test.
     *
     * These look different to a user but are identical to the updater - there is no jar, so there
     * is nothing to replace - and lumping them together is what makes the message honest about all
     * of them instead of naming only the IDE case, which was the one thing the old text said.
     */
    DEV("dev", "开发模式（mvn spring-boot:run、IDE 或测试直接运行 target/classes）"),

    /** No code source, or a location that is neither a jar nor a classes directory. */
    OTHER("other", "未打包运行（无法定位正在运行的 jar）");

    private static final Logger log = LoggerFactory.getLogger(LaunchMode.class);

    private final String wireName;
    private final String label;

    LaunchMode(String wireName, String label) {
        this.wireName = wireName;
        this.label = label;
    }

    /** The stable identifier the frontend keys on; never the Chinese label. */
    public String wireName() {
        return wireName;
    }

    /** One line for the settings card, in the user's language. */
    public String label() {
        return label;
    }

    /** What this process is running as, decided from where its classes were loaded. */
    public static LaunchMode detect() {
        return of(JarLocation.currentJar(), JarLocation.codeSourceLocation());
    }

    /**
     * The real decision, with both inputs passed in so it can be tested offline.
     *
     * @param runningJar the jar {@link JarLocation} found, or null; a non-null value is decisive,
     *                   because it means a replaceable file exists whatever the URL looked like
     * @param location   the raw classloader location, used only when there is no jar
     */
    public static LaunchMode of(Path runningJar, String location) {
        if (runningJar != null) {
            return JAR;
        }
        if (location == null || location.isBlank()) {
            log.debug("no code source location; treating the launch mode as unknown");
            return OTHER;
        }
        Path directory = JarLocation.directoryOf(location);
        if (directory != null && looksLikeClassesDirectory(directory)) {
            return DEV;
        }
        return OTHER;
    }

    /**
     * Whether a directory is a compiled classes tree rather than an arbitrary folder.
     *
     * The check is deliberately loose - one marker package is enough - because the answer only
     * picks a sentence to show; being strict would label an unusual build layout "unknown" and
     * lose the actionable hint without protecting anything.
     */
    private static boolean looksLikeClassesDirectory(Path directory) {
        return Files.isDirectory(directory.resolve("com").resolve("geek").resolve("association"))
                || Files.isDirectory(directory.resolve("META-INF"));
    }

    /** Parses the wire name back, for tests and for reading a value that crossed the wire. */
    public static LaunchMode parse(String wireName) {
        String text = wireName == null ? "" : wireName.strip().toLowerCase(Locale.ROOT);
        for (LaunchMode mode : values()) {
            if (mode.wireName.equals(text)) {
                return mode;
            }
        }
        return OTHER;
    }

    @Override
    public String toString() {
        return wireName;
    }
}
