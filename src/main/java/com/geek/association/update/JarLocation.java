package com.geek.association.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Locale;
import java.util.Properties;
import java.util.jar.JarFile;

/**
 * Where this code was loaded from, resolved the way a Spring Boot executable jar actually reports it.
 *
 * The naive {@code Path.of(codeSource.getLocation().toURI())} works in an IDE and in a plain jar, but
 * inside a Spring Boot executable jar the location is not a file URI at all, so it throws and the
 * whole update feature silently degrades to "cannot apply" - exactly the case it exists for.
 *
 * Spring Boot 3.2+ reports {@code jar:nested:/app.jar/!BOOT-INF/classes/!/}, while older versions and
 * plain {@code java -jar} report {@code jar:file:/app.jar!/BOOT-INF/classes!/}. Both are handled
 * here, because the difference is invisible until someone runs the packaged jar.
 *
 * Public so the URL forms above can be tested offline; it is otherwise an implementation detail of
 * {@link Version} and {@link UpdateService}, not part of the update API.
 */
public final class JarLocation {

    private static final Logger log = LoggerFactory.getLogger(JarLocation.class);

    private JarLocation() {
    }

    /**
     * The jar file this code was loaded from, or null when running from a directory.
     *
     * Null is the honest answer in an IDE or a test run, where there is no jar to replace.
     */
    public static Path currentJar() {
        String location = codeSourceLocation();
        return location == null ? null : jarOf(location);
    }

    /**
     * The raw classloader location, or null when the class was loaded without one.
     *
     * Kept separate from {@link #currentJar()} because "there is no jar" and "there is no code
     * source at all" are different answers: the first one is the normal development case and the
     * second one is unusual enough that {@link LaunchMode} reports it as unknown.
     */
    public static String codeSourceLocation() {
        CodeSource source = JarLocation.class.getProtectionDomain().getCodeSource();
        if (source == null) {
            log.debug("no code source: the class was loaded without one");
            return null;
        }
        URL location = source.getLocation();
        if (location == null) {
            log.debug("code source has no location");
            return null;
        }
        log.debug("class loaded from {}", location);
        return location.toString();
    }

    /**
     * Turns a classloader location into the jar file it belongs to.
     *
     * Returns null for anything that is not a file on disk: a directory (IDE, tests) or a URL whose
     * scheme cannot name a local path. Callers treat null as "not running from a jar".
     */
    public static Path jarOf(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String text = location.strip();
        // Unwrap the nesting wrappers. Order matters: "jar:nested:" needs both prefixes gone, and a
        // plain jar only needs the first.
        text = stripPrefix(text, "jar:");
        text = stripPrefix(text, "nested:");
        // Cut at the first "!": everything after it is the path inside the archive, and the outer
        // part is the archive itself. Older Boot writes "!/", 3.2+ writes "/!BOOT-INF/...".
        int inner = text.indexOf('!');
        if (inner >= 0) {
            text = text.substring(0, inner);
        }
        text = stripPrefix(text, "file:");
        // A trailing separator is left behind by the "!/" form; harmless for Path, but normalising
        // it keeps the resolved path equal to the one an operator would type.
        while (text.length() > 1 && text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        Path path;
        try {
            // A location without a scheme (some custom classloaders) is already a plain path; a
            // URI with one must be decoded, or a path containing spaces stays percent-encoded.
            path = text.contains("://") || text.startsWith("file:")
                    ? Path.of(new URI(location.strip()))
                    : Path.of(text);
        } catch (URISyntaxException | IllegalArgumentException e) {
            log.debug("could not turn {} into a path", location, e);
            return null;
        }
        if (!Files.isRegularFile(path)) {
            return null;
        }
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar") ? path : null;
    }

    private static String stripPrefix(String text, String prefix) {
        return text.startsWith(prefix) ? text.substring(prefix.length()) : text;
    }

    /**
     * The directory a classloader location points at, or null when it is not a local directory.
     *
     * This is the development half of {@link #jarOf(String)}: {@code mvn spring-boot:run} and an
     * IDE both report {@code file:/.../target/classes/}, which is a directory on disk and not a
     * jar. Naming it explicitly is what lets the update card say "you are in development mode"
     * instead of "not a jar, cause unknown".
     */
    public static Path directoryOf(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String text = location.strip();
        if (text.startsWith("jar:") || text.contains("!")) {
            // A nested archive location always names an archive, never a plain classes folder.
            return null;
        }
        text = stripPrefix(text, "file:");
        while (text.length() > 1 && text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        Path path;
        try {
            path = text.contains("://") || text.startsWith("file:")
                    ? Path.of(new URI(location.strip()))
                    : Path.of(text);
        } catch (URISyntaxException | IllegalArgumentException e) {
            log.debug("could not turn {} into a directory", location, e);
            return null;
        }
        return Files.isDirectory(path) ? path : null;
    }

    /**
     * The version written by the Maven build, readable in development mode too.
     *
     * The jar manifest only exists once the project is packaged, so {@code mvn spring-boot:run}
     * and an IDE run had no version at all and the settings page fell back to "0.0.0-dev". The
     * {@code spring-boot-maven-plugin:build-info} goal writes the same version into
     * {@code META-INF/build-info.properties} inside {@code target/classes}, which is exactly what
     * those launch modes put on the classpath - so the same read works for every mode.
     */
    public static String buildInfoVersion() {
        try (InputStream in = JarLocation.class.getResourceAsStream("/META-INF/build-info.properties")) {
            if (in == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("build.version");
            return version == null || version.isBlank() ? null : version.strip();
        } catch (IOException | RuntimeException e) {
            log.debug("could not read META-INF/build-info.properties", e);
            return null;
        }
    }

    /**
     * {@code Implementation-Version} of the running jar, or null when there is no jar.
     *
     * Read from the outer jar, which is where the Spring Boot build writes it; the nested
     * application classes do not carry a manifest of their own.
     */
    static String manifestVersion() {
        Path jar = currentJar();
        if (jar == null) {
            return null;
        }
        try (JarFile file = new JarFile(jar.toFile())) {
            if (file.getManifest() == null) {
                return null;
            }
            return file.getManifest().getMainAttributes().getValue("Implementation-Version");
        } catch (IOException | RuntimeException e) {
            log.debug("could not read the manifest of {}", jar, e);
            return null;
        }
    }
}
