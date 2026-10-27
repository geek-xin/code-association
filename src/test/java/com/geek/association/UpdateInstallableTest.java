package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.update.ReleaseInfo;
import com.geek.association.update.UpdateService;
import com.geek.association.update.Version;
import com.geek.association.update.VersionSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "A newer version exists" and "a newer version can be installed from here" are different claims.
 *
 * Reading git tags means the newest entry can be a tag with no release behind it: no notes, no
 * assets, nothing to download. Advertising that as an available update would send the user to a
 * download button that can only fail, so the status view keeps the two notions apart and flags each
 * release with whether it is installable at all.
 */
class UpdateInstallableTest {

    private static AppProperties props(Path dataDir) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(dataDir.toString());
        properties.getUpdate().setCheckIntervalMinutes(360);
        return properties;
    }

    /** A tags-only release: the shape GitHubVersionSource produces for a tag with no release. */
    private static ReleaseInfo tagOnly(String version) {
        ReleaseInfo release = new ReleaseInfo();
        release.version = version;
        release.tag = "v" + version;
        release.name = release.tag;
        release.fromTag = true;
        release.notes = "";
        return release;
    }

    private static ReleaseInfo withJar(String version) {
        ReleaseInfo release = new ReleaseInfo();
        release.version = version;
        release.tag = "v" + version;
        release.name = "code-association " + version;
        release.assets.add(new ReleaseInfo.Asset(
                "code-association-" + version + ".jar", 1024L, "http://127.0.0.1:1/never.jar"));
        return release;
    }

    private static final class FakeSource implements VersionSource {
        private final List<ReleaseInfo> releases;

        FakeSource(ReleaseInfo... releases) {
            this.releases = new ArrayList<>(List.of(releases));
        }

        @Override
        public List<ReleaseInfo> fetch() {
            return releases;
        }
    }

    private static UpdateService service(Path dataDir, VersionSource source, String running) {
        return new UpdateService(props(dataDir), source, HttpClient.newHttpClient(), Version.parse(running));
    }

    @SuppressWarnings("unchecked")
    private static List<ReleaseInfo> releases(Map<String, Object> status) {
        return (List<ReleaseInfo>) status.get("releases");
    }

    @Test
    void aTagsOnlyVersionIsListedButNotOfferedForDownload(@TempDir Path tmp) {
        UpdateService service = service(tmp, new FakeSource(tagOnly("0.6.0"), withJar("0.5.0")), "0.4.0");

        Map<String, Object> status = service.status();
        List<ReleaseInfo> list = releases(status);

        assertTrue(list.get(0).fromTag);
        assertFalse(list.get(0).downloadable, "a tag without assets cannot be installed");
        assertTrue(list.get(1).downloadable, "the release does ship a jar");
    }

    @Test
    void theBannerMustNotAdvertiseAnUninstallableNewestVersion(@TempDir Path tmp) {
        // The newest tag has no jar; the newest *installable* version is one step behind it.
        UpdateService service = service(tmp, new FakeSource(tagOnly("0.6.0"), withJar("0.5.0")), "0.4.0");

        Map<String, Object> status = service.status();

        assertEquals("0.6.0", status.get("latestVersion"));
        assertTrue((Boolean) status.get("updateAvailable"), "a newer version does exist");
        assertEquals("0.5.0", status.get("latestInstallableVersion"));
        assertTrue((Boolean) status.get("installableUpdateAvailable"),
                "0.5.0 is newer than the running 0.4.0 and can be fetched");
    }

    @Test
    void aNewerTagWithNoInstallableReleaseAtAllReportsNoInstallableUpdate(@TempDir Path tmp) {
        // Nothing newer than the running version can be installed, so there is no update to offer.
        UpdateService service = service(tmp, new FakeSource(tagOnly("0.6.0"), withJar("0.4.0")), "0.4.0");

        Map<String, Object> status = service.status();

        assertEquals("0.6.0", status.get("latestVersion"));
        assertTrue((Boolean) status.get("updateAvailable"));
        assertEquals("0.4.0", status.get("latestInstallableVersion"), "the running version itself");
        assertFalse((Boolean) status.get("installableUpdateAvailable"));
    }

    @Test
    void whenEverythingIsInstallableTheTwoNotionsAgree(@TempDir Path tmp) {
        UpdateService service = service(tmp, new FakeSource(withJar("0.5.0"), withJar("0.4.0")), "0.4.0");

        Map<String, Object> status = service.status();

        assertEquals(status.get("latestVersion"), status.get("latestInstallableVersion"));
        assertEquals(status.get("updateAvailable"), status.get("installableUpdateAvailable"));
    }

    @Test
    void aReleaseWithOnlySourceJarsIsNotInstallable(@TempDir Path tmp) {
        // -sources.jar is a jar by name but would brick the install if copied over the running one,
        // so the "is it downloadable" flag must use the same rule as the apply path.
        ReleaseInfo release = new ReleaseInfo();
        release.version = "0.5.0";
        release.tag = "v0.5.0";
        release.assets.add(new ReleaseInfo.Asset(
                "code-association-0.5.0-sources.jar", 10L, "http://127.0.0.1:1/s.jar"));

        UpdateService service = service(tmp, new FakeSource(release), "0.4.0");

        Map<String, Object> status = service.status();

        assertFalse(releases(status).get(0).downloadable);
        assertFalse((Boolean) status.get("installableUpdateAvailable"));
        assertNull(status.get("latestInstallableVersion"), "nothing here can be installed");
    }
}
