package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.update.ApplySupport;
import com.geek.association.update.JarLocation;
import com.geek.association.update.LaunchMode;
import com.geek.association.update.ReleaseInfo;
import com.geek.association.update.UpdateService;
import com.geek.association.update.Version;
import com.geek.association.update.VersionSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the settings card is told about the launch mode, and what it can therefore offer.
 *
 * The bug this covers is a message bug with a real cost: every run without a jar was reported as
 * "probably an IDE", including the {@code mvn spring-boot:run} runs the project actually uses, and
 * nothing told the user how to get out of that state. So the assertions here are about the
 * distinction between the three modes and about the reason being actionable, not about wording.
 */
class UpdateLaunchModeTest {

    // ------------------------------------------------------------ launch mode

    @Test
    void aJarLocationWinsWhateverTheUrlLookedLike(@TempDir Path tmp) throws Exception {
        Path jar = Files.createFile(tmp.resolve("code-association.jar"));

        assertEquals(LaunchMode.JAR, LaunchMode.of(jar, "jar:nested:" + jar.toUri() + "/!BOOT-INF/classes/!/"));
        // A jar found on disk is decisive even if the location string cannot be parsed; the file is
        // what can be replaced, and the string is only evidence.
        assertEquals(LaunchMode.JAR, LaunchMode.of(jar, "something-unparseable"));
    }

    @Test
    void aClassesDirectoryIsDevelopmentMode(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("target").resolve("classes"));
        Files.createDirectories(classes.resolve("com").resolve("geek").resolve("association"));

        assertEquals(LaunchMode.DEV, LaunchMode.of(null, classes.toUri().toString()));
        // The form Maven actually hands to the forked JVM, without a trailing slash and without a
        // scheme: both have to resolve to the same directory.
        assertEquals(LaunchMode.DEV, LaunchMode.of(null, classes.toString()));
    }

    @Test
    void anEmptyOrUnnamedLocationIsUnknownRatherThanDevelopment() {
        // "dev" would be a claim about the user's setup; with no location there is nothing to
        // support that claim, and the card should not invent a fix for a state it cannot name.
        assertEquals(LaunchMode.OTHER, LaunchMode.of(null, null));
        assertEquals(LaunchMode.OTHER, LaunchMode.of(null, "  "));
        assertEquals(LaunchMode.OTHER, LaunchMode.of(null, "jar:file:/nonexistent/app.jar!/BOOT-INF/classes!/"));
    }

    @Test
    void anOrdinaryFolderIsNotMistakenForAClassesTree(@TempDir Path tmp) {
        assertEquals(LaunchMode.OTHER, LaunchMode.of(null, tmp.toUri().toString()));
    }

    @Test
    void everyModeHasAStableWireNameAndAReadableLabel() {
        for (LaunchMode mode : LaunchMode.values()) {
            assertTrue(mode.wireName().matches("[a-z]+"), mode.wireName());
            assertFalse(mode.label().isBlank());
            assertEquals(mode, LaunchMode.parse(mode.wireName()));
        }
        assertEquals(LaunchMode.OTHER, LaunchMode.parse("nonsense"));
        assertEquals(LaunchMode.OTHER, LaunchMode.parse(null));
        // The wire name is what crosses the boundary; the Chinese label must never be the key.
        assertEquals("jar", LaunchMode.JAR.toString());
    }

    // ---------------------------------------------------------- apply support

    @Test
    void developmentModeExplainsItselfAndGivesTheCommandThatFixesIt() {
        ApplySupport support = ApplySupport.of(LaunchMode.DEV, false);

        assertFalse(support.supported());
        assertTrue(support.reason().contains("开发模式"), support.reason());
        // The point of the whole change: the message must not send a script user to their IDE.
        assertFalse(support.reason().contains("IDE 里启动"), support.reason());
        assertEquals("mvn -q -DskipTests package && ./start.sh", support.hint());
    }

    @Test
    void windowsOnAJarIsADifferentProblemFromHavingNoJar() {
        ApplySupport support = ApplySupport.of(LaunchMode.JAR, true);

        assertFalse(support.supported());
        assertTrue(support.reason().contains("Windows"), support.reason());
        assertNotNull(support.hint(), "a refused apply still needs a next step");
    }

    @Test
    void aJarOnAPosixHostNeedsNoExplanation() {
        ApplySupport support = ApplySupport.of(LaunchMode.JAR, false);

        assertTrue(support.supported());
        assertNull(support.reason(), "a supported environment must not carry a stale complaint");
        assertNull(support.hint());
    }

    // ------------------------------------------------------------ the payload

    @Test
    void statusNamesTheLaunchModeAndRepeatsTheReasonTheButtonIsDisabled(@TempDir Path tmp) {
        // A test JVM loads classes from target/test-classes, which is the development shape: no jar,
        // a classes directory. Asserting the concrete mode keeps the payload honest in exactly the
        // environment that used to report the misleading message.
        UpdateService service = new UpdateService(props(tmp), new FakeSource("0.9.0"),
                HttpClient.newHttpClient(), Version.parse("0.5.0"));

        Map<String, Object> status = service.status();

        assertEquals("dev", status.get("launchMode"));
        assertNotNull(status.get("launchModeLabel"));
        assertEquals(false, status.get("applySupported"));
        assertEquals(false, status.get("runningFromJar"));
        assertNotNull(status.get("applyUnsupportedReason"));
        assertTrue(((String) status.get("applyUnsupportedReason")).contains("开发模式"));
        assertNotNull(status.get("applyHint"), "the card needs a command, not only a diagnosis");
    }

    @Test
    void aJarPayloadHasNoUnsupportedReasonLeftOver(@TempDir Path tmp) throws Exception {
        // The installed case, driven through the seam the apply tests already use: a jar exists, so
        // the card must render the supported state with both explanation fields empty.
        Path jar = Files.createFile(tmp.resolve("code-association.jar"));
        UpdateService service = new Installed(props(tmp.resolve("data")), new FakeSource("0.9.0"), jar);

        Map<String, Object> status = service.status();

        assertEquals("jar", status.get("launchMode"));
        assertEquals(true, status.get("applySupported"));
        assertNull(status.get("applyUnsupportedReason"));
        assertNull(status.get("applyHint"));
    }

    // ------------------------------------------------------- version in dev mode

    @Test
    void theRunningVersionComesFromBuildInfoWhenThereIsNoJar() {
        // Tests run without a jar, so this is the development path. Before build-info was wired in
        // this returned 0.0.0-dev, which is what the settings page showed to anyone running
        // mvn spring-boot:run - the version was simply unknown to the process.
        String buildInfo = JarLocation.buildInfoVersion();
        if (buildInfo == null) {
            // Not built with the build-info goal (a bare mvn test from a stale target dir): the
            // development marker is still the honest answer, so only the fallback is asserted.
            assertEquals("0.0.0-dev", Version.running().toString());
            return;
        }
        assertEquals(buildInfo, Version.running().toString(),
                "the process must report the version the build wrote, not a placeholder");
    }

    // ------------------------------------------------------------------ stubs

    private static AppProperties props(Path dataDir) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(dataDir.toString());
        properties.getUpdate().setCheckIntervalMinutes(360);
        return properties;
    }

    private static final class FakeSource implements VersionSource {
        private final String version;

        FakeSource(String version) {
            this.version = version;
        }

        @Override
        public List<ReleaseInfo> fetch() {
            ReleaseInfo release = new ReleaseInfo();
            release.version = version;
            release.tag = "v" + version;
            release.assets.add(new ReleaseInfo.Asset("code-association-" + version + ".jar", 1L,
                    "https://example.test/code-association-" + version + ".jar"));
            List<ReleaseInfo> out = new ArrayList<>();
            out.add(release);
            return out;
        }
    }

    /** The same seam UpdateApplyRollbackTest uses: a jar that is not really running. */
    private static final class Installed extends UpdateService {
        private final Path jar;

        Installed(AppProperties properties, VersionSource source, Path jar) {
            super(properties, source, HttpClient.newHttpClient(), Version.parse("0.5.0"));
            this.jar = jar;
        }

        @Override
        public Path runningJar() {
            return jar;
        }
    }
}
