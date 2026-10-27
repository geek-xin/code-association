package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.update.GitHubVersionSource;
import com.geek.association.update.ReleaseInfo;
import com.geek.association.update.UpdateService;
import com.geek.association.update.Version;
import com.geek.association.update.VersionSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The startup check is a setting, and a setting nobody reads is a lie.
 *
 * An air-gapped installation turns {@code check-on-startup} off precisely so its log stays free of
 * a failure it already knows about; if the flag were ignored, that installation would log an error
 * on every boot and the operator would go looking for a problem that does not exist.
 */
class UpdateStartupCheckTest {

    @Test
    void theStartupCheckRunsWhenItIsEnabled(@TempDir Path root) {
        AppProperties properties = props(root);
        properties.getUpdate().setCheckOnStartup(true);
        CountingSource source = new CountingSource();
        UpdateService service = new UpdateService(properties, source, HttpClient.newHttpClient(),
                Version.parse("0.3.0"));

        service.onStartup();

        assertTrue(waitFor(source.calls), "the catch-up check must run on a fresh start");
    }

    @Test
    void theStartupCheckStaysSilentWhenItIsDisabled(@TempDir Path root) {
        AppProperties properties = props(root);
        properties.getUpdate().setCheckOnStartup(false);
        CountingSource source = new CountingSource();
        UpdateService service = new UpdateService(properties, source, HttpClient.newHttpClient(),
                Version.parse("0.3.0"));

        service.onStartup();

        // The check runs on a daemon thread, so "did not run" has to be given the same window as
        // "did run" - asserting immediately would pass even if the thread were merely slow.
        assertTrue(waitForNothing(source.calls), "check-on-startup: false must not reach the network");
    }

    @Test
    void aDisabledUpdateFeatureAlsoSkipsTheStartupCheck(@TempDir Path root) {
        AppProperties properties = props(root);
        properties.getUpdate().setEnabled(false);
        properties.getUpdate().setCheckOnStartup(true);
        CountingSource source = new CountingSource();
        UpdateService service = new UpdateService(properties, source, HttpClient.newHttpClient(),
                Version.parse("0.3.0"));

        service.onStartup();

        assertTrue(waitForNothing(source.calls));
    }

    private static AppProperties props(Path root) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(root.toString());
        properties.getUpdate().setRepo("o/r");
        properties.getUpdate().setCheckIntervalMinutes(0);
        return properties;
    }

    private static boolean waitFor(AtomicInteger calls) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (calls.get() > 0) {
                return true;
            }
            sleep();
        }
        return false;
    }

    private static boolean waitForNothing(AtomicInteger calls) {
        long deadline = System.currentTimeMillis() + 600L;
        while (System.currentTimeMillis() < deadline) {
            if (calls.get() > 0) {
                return false;
            }
            sleep();
        }
        return true;
    }

    private static void sleep() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class CountingSource implements VersionSource {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public List<ReleaseInfo> fetch() {
            calls.incrementAndGet();
            ReleaseInfo release = new ReleaseInfo();
            release.version = "0.9.0";
            release.tag = "v0.9.0";
            release.name = "0.9.0";
            release.url = "https://example.test/0.9.0";
            return List.of(release);
        }
    }
}
