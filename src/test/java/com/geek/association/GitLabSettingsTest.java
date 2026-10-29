package com.geek.association;

import com.geek.association.config.GitLabSettings;
import com.geek.association.config.AppProperties;
import com.geek.association.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GitLab token is a credential, so these tests pin the four things that would silently leak or
 * lose it: the token never coming back out, an empty field not erasing it, the snapshot overriding
 * application.yml after a restart, and the file being unreadable by other local users.
 */
class GitLabSettingsTest {

    private static final String TOKEN = "glpat-abcdefghij";

    private static GitLabSettings settings(Path tmp) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(tmp.toString());
        GitLabSettings settings = new GitLabSettings(properties);
        settings.load();
        return settings;
    }

    @Test
    void savedTokenIsMaskedAndNeverReturnedInPlainText(@TempDir Path tmp) {
        GitLabSettings settings = settings(tmp);
        settings.update(Map.of("token", TOKEN));

        Map<String, Object> view = settings.view();
        assertEquals(true, view.get("tokenSet"));
        assertEquals("glpa…ghij", view.get("tokenMasked"));
        assertEquals("file", view.get("configuredBy"));
        assertEquals(true, String.valueOf(view.get("settingsFile")).endsWith("gitlab-settings.json"));
        assertFalse(view.containsKey("token"), "the token itself must never be part of the payload");

        String raw = Json.write(view);
        assertFalse(raw.contains(TOKEN), "no serialised form of the view may contain the token: " + raw);
    }

    @Test
    void shortTokenIsMaskedCompletely(@TempDir Path tmp) {
        GitLabSettings settings = settings(tmp);
        settings.update(Map.of("token", "short"));
        assertEquals("••••", settings.view().get("tokenMasked"));
    }

    @Test
    void blankTokenKeepsTheStoredOneAndClearTokenRemovesIt(@TempDir Path tmp) {
        GitLabSettings settings = settings(tmp);
        settings.update(Map.of("token", TOKEN));

        // The form only ever holds a mask, so an empty string means "untouched", not "erase".
        settings.update(Map.of("token", ""));
        assertEquals(true, settings.view().get("tokenSet"));

        settings.update(Map.of("clearToken", true));
        assertEquals(false, settings.view().get("tokenSet"));
        assertEquals("", settings.view().get("tokenMasked"));
    }

    @Test
    void snapshotOverridesTheApplicationYmlBaselineAfterARestart(@TempDir Path tmp) {
        settings(tmp).update(Map.of("token", TOKEN));

        AppProperties restarted = new AppProperties();
        restarted.setDataDir(tmp.toString());
        restarted.getGitlab().setToken("from-application-yml");
        GitLabSettings reloaded = new GitLabSettings(restarted);
        reloaded.load();

        assertEquals(TOKEN, restarted.getGitlab().getToken(),
                "the settings page snapshot must win over the yml baseline, as AI settings do");
        assertEquals("file", reloaded.view().get("configuredBy"));
    }

    @Test
    void missingFileKeepsTheApplicationYmlBaseline(@TempDir Path tmp) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(tmp.toString());
        properties.getGitlab().setToken("from-application-yml");
        GitLabSettings settings = new GitLabSettings(properties);

        settings.load();

        assertEquals("from-application-yml", properties.getGitlab().getToken());
        assertEquals("application.yml", settings.view().get("configuredBy"));
        assertEquals(true, settings.view().get("tokenSet"));
    }

    @Test
    void corruptFileFallsBackToTheBaselineInsteadOfStoppingTheService(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("gitlab-settings.json"), "{ half written", StandardCharsets.UTF_8);
        AppProperties properties = new AppProperties();
        properties.setDataDir(tmp.toString());
        properties.getGitlab().setToken("from-application-yml");
        GitLabSettings settings = new GitLabSettings(properties);

        settings.load();

        assertEquals("from-application-yml", properties.getGitlab().getToken());
        assertEquals("application.yml", settings.view().get("configuredBy"));
    }

    @Test
    void tokenIsTrimmedOnSaveAndWhitespaceInsideIsRejected(@TempDir Path tmp) {
        GitLabSettings settings = settings(tmp);
        settings.update(Map.of("token", "  " + TOKEN + "\n"));
        assertEquals(TOKEN, settingsFileToken(tmp));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> settings.update(Map.of("token", "abc def")));
        assertTrue(e.getMessage().contains("空格"), e.getMessage());
    }

    @Test
    void anAbsurdlyLongPasteIsRejected(@TempDir Path tmp) {
        GitLabSettings settings = settings(tmp);
        String paste = "x".repeat(600); // above GitLabSettings.MAX_TOKEN_LENGTH (512)
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> settings.update(Map.of("token", paste)));
        assertTrue(e.getMessage().contains("过长"), e.getMessage());
    }

    @Test
    void tokenFileIsRestrictedToTheOwner(@TempDir Path tmp) throws IOException {
        GitLabSettings settings = settings(tmp);
        settings.update(Map.of("token", TOKEN));
        Set<PosixFilePermission> permissions;
        try {
            permissions = Files.getPosixFilePermissions(settings.file());
        } catch (UnsupportedOperationException e) {
            return; // Windows: inherited ACLs are the platform's answer, and the code says so.
        }
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions);
    }

    /** Reads the persisted snapshot directly: the view only ever exposes the mask. */
    private static String settingsFileToken(Path tmp) {
        try {
            return Json.mapper().readTree(Files.readString(tmp.resolve("gitlab-settings.json")))
                    .path("token").asText();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
