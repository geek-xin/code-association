package com.geek.association;

import com.geek.association.config.AiSettings;
import com.geek.association.config.AppProperties;
import com.geek.association.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings file is the only place a user's AI configuration survives a restart, so these tests
 * pin the three things that would silently break it: the key never coming back out, an empty field
 * not erasing a stored secret, and a corrupt file not stopping the service from starting.
 */
class AiSettingsTest {

    private static AiSettings settings(Path tmp) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(tmp.toString());
        AiSettings settings = new AiSettings(properties);
        settings.load();
        return settings;
    }

    @Test
    void savedKeyIsMaskedAndNeverReturnedInPlainText(@TempDir Path tmp) {
        AiSettings settings = settings(tmp);
        settings.update(Map.of(
                "enabled", true,
                "baseUrl", "https://api.deepseek.com/v1/",
                "model", "deepseek-chat",
                "apiKey", "sk-abcdefghijklmnopqrstuvwxyz3f9c"));

        Map<String, Object> view = settings.view();
        assertEquals(true, view.get("enabled"));
        // Trailing slash dropped: the endpoint builder appends the path itself.
        assertEquals("https://api.deepseek.com/v1", view.get("baseUrl"));
        assertEquals(true, view.get("apiKeySet"));
        assertEquals("sk-a…3f9c", view.get("apiKeyMasked"));
        assertEquals(true, view.get("ready"));
        assertEquals("", view.get("reason"));
        assertEquals("file", view.get("configuredBy"));
        assertFalse(view.containsKey("apiKey"), "the key itself must never be part of the payload");

        String raw = Json.write(view);
        assertFalse(raw.contains("sk-abcdefghijklmnopqrstuvwxyz3f9c"),
                "no serialised form of the view may contain the key: " + raw);
    }

    @Test
    void shortKeyIsMaskedCompletely(@TempDir Path tmp) {
        AiSettings settings = settings(tmp);
        settings.update(Map.of("baseUrl", "http://localhost:11434/v1", "model", "qwen", "apiKey", "short"));
        // Four of six characters would be most of the secret; a short key is shown as "there is one".
        assertEquals("••••", settings.view().get("apiKeyMasked"));
    }

    @Test
    void blankKeyKeepsTheStoredOneAndClearApiKeyRemovesIt(@TempDir Path tmp) {
        AiSettings settings = settings(tmp);
        settings.update(Map.of("baseUrl", "https://x.test/v1", "model", "m", "apiKey", "sk-keepme-123456789"));

        // The form only ever holds the mask, so an empty string means "untouched", not "erase".
        settings.update(Map.of("model", "m2", "apiKey", ""));
        assertEquals(true, settings.view().get("apiKeySet"));
        assertEquals("sk-k…6789", settings.view().get("apiKeyMasked"));
        assertEquals("m2", settings.view().get("model"));

        settings.update(Map.of("clearApiKey", true));
        assertEquals(false, settings.view().get("apiKeySet"));
        assertEquals("", settings.view().get("apiKeyMasked"));
    }

    @Test
    void invalidSettingsAreRejectedWithAReadableMessage(@TempDir Path tmp) {
        AiSettings settings = settings(tmp);

        IllegalArgumentException protocol = assertThrows(IllegalArgumentException.class,
                () -> settings.update(Map.of("baseUrl", "api.deepseek.com/v1")));
        assertEquals("接口地址需以 http:// 或 https:// 开头", protocol.getMessage());

        IllegalArgumentException missingUrl = assertThrows(IllegalArgumentException.class,
                () -> settings.update(Map.of("enabled", true)));
        assertEquals("已启用 AI 审核，但未填写接口地址", missingUrl.getMessage());

        IllegalArgumentException missingModel = assertThrows(IllegalArgumentException.class,
                () -> settings.update(Map.of("enabled", true, "baseUrl", "https://x.test/v1")));
        assertEquals("已启用 AI 审核，但未填写模型", missingModel.getMessage());

        IllegalArgumentException impacts = assertThrows(IllegalArgumentException.class,
                () -> settings.update(Map.of("maxImpacts", 51)));
        assertEquals("每条分析最多影响条数需在 1..50 之间", impacts.getMessage());

        IllegalArgumentException timeout = assertThrows(IllegalArgumentException.class,
                () -> settings.update(Map.of("timeoutSeconds", 4)));
        assertEquals("请求超时需在 5..300 秒之间", timeout.getMessage());

        // A rejected request must not have applied the fields it carried.
        assertEquals("", settings.view().get("baseUrl"));
        assertEquals(12, settings.view().get("maxImpacts"));
        assertEquals(60, settings.view().get("timeoutSeconds"));
    }

    @Test
    void corruptFileDegradesToTheBaselineInsteadOfThrowing(@TempDir Path tmp) throws Exception {
        AppProperties properties = new AppProperties();
        properties.setDataDir(tmp.toString());
        properties.getAi().setBaseUrl("https://baseline.test/v1");
        properties.getAi().setModel("baseline-model");
        Files.writeString(tmp.resolve("ai-settings.json"), "{ this is not json", StandardCharsets.UTF_8);

        AiSettings settings = new AiSettings(properties);
        settings.load();

        assertEquals("https://baseline.test/v1", settings.view().get("baseUrl"));
        assertEquals("baseline-model", settings.view().get("model"));
        assertEquals("application.yml", settings.view().get("configuredBy"));
    }

    @Test
    void settingsSurviveARestartAndTheFileIsOwnerOnly(@TempDir Path tmp) throws Exception {
        AiSettings first = settings(tmp);
        first.update(Map.of(
                "enabled", true,
                "baseUrl", "https://api.openai.com/v1",
                "model", "gpt-4o-mini",
                "apiKey", "sk-persist-1234567890",
                "maxImpacts", 20,
                "timeoutSeconds", 120,
                "defaultForRuns", false));

        // A new instance is what the next process start does: read the file over the baseline.
        AppProperties restarted = new AppProperties();
        restarted.setDataDir(tmp.toString());
        AiSettings second = new AiSettings(restarted);
        second.load();

        Map<String, Object> view = second.view();
        assertEquals(true, view.get("enabled"));
        assertEquals("https://api.openai.com/v1", view.get("baseUrl"));
        assertEquals("gpt-4o-mini", view.get("model"));
        assertEquals(20, view.get("maxImpacts"));
        assertEquals(120, view.get("timeoutSeconds"));
        assertEquals(false, view.get("defaultForRuns"));
        assertEquals("file", view.get("configuredBy"));
        assertEquals(true, view.get("apiKeySet"), "the key has to survive the restart too");

        Path file = second.file();
        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.exists(file.resolveSibling("ai-settings.json.tmp")),
                "the temporary file must be renamed away, not left behind");
        try {
            assertEquals(java.util.Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file),
                    "a file holding an API key must not be group/world readable");
        } catch (UnsupportedOperationException e) {
            // Non-POSIX platform: the permission check does not apply.
        }
    }

    @Test
    void fileLocationIsAbsoluteAndInsideTheDataDir(@TempDir Path tmp) {
        AiSettings settings = settings(tmp);
        Path file = settings.file();
        assertTrue(file.isAbsolute());
        assertEquals(tmp.toAbsolutePath().normalize().resolve("ai-settings.json"), file);
        assertEquals(file.toString(), settings.view().get("settingsFile"));
    }

    @Test
    void readyFollowsTheThreeConditionsAndExplainsWhatIsMissing(@TempDir Path tmp) {
        // The "on but incomplete" states are reachable from application.yml or a hand-edited file,
        // not from the form: update() refuses to create them (see the rejection test below). They
        // still have to report a reason, because that is what the settings card displays.
        AppProperties properties = new AppProperties();
        properties.setDataDir(tmp.toString());
        AiSettings settings = new AiSettings(properties);
        settings.load();

        assertEquals(false, settings.view().get("ready"));
        assertEquals("总开关未打开", settings.view().get("reason"));

        properties.getAi().setEnabled(true);
        assertEquals(false, settings.view().get("ready"));
        assertEquals("已启用但未填写接口地址", settings.view().get("reason"));

        properties.getAi().setBaseUrl("https://x.test/v1");
        assertEquals(false, settings.view().get("ready"));
        assertEquals("已启用但未填写模型", settings.view().get("reason"));

        settings.update(Map.of("model", "m"));
        assertEquals(true, settings.view().get("ready"));
        assertEquals("", settings.view().get("reason"));
        // No key configured, and that is a valid state: local models do not use one.
        assertEquals(false, settings.view().get("apiKeySet"));
        assertNotNull(settings.view().get("settingsFile"));
    }
}
