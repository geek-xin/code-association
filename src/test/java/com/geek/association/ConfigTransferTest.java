package com.geek.association;

import com.geek.association.config.AiSettings;
import com.geek.association.config.AppProperties;
import com.geek.association.config.GitLabSettings;
import com.geek.association.model.RepoSource;
import com.geek.association.transfer.ConfigTransfer;
import com.geek.association.transfer.SourceTransfer;
import com.geek.association.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置导入导出：一个文件同时带走设置项与代码源。
 *
 * <p>These tests pin the four things that would silently go wrong: a secret appearing in a file
 * nobody asked to contain one, an import wiping the recipient's own key because the shared file
 * omitted it, one broken section taking the whole file down, and an import that duplicates the
 * registry instead of merging it.
 */
class ConfigTransferTest {

    private static final String API_KEY = "test-api-key-value";
    private static final String TOKEN = "test-gitlab-token-value";

    /** A settings snapshot on its own data directory, loaded the way the running service does. */
    private record Settings(AiSettings ai, GitLabSettings gitlab) {
    }

    private static Settings settings(Path dataDir) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(dataDir.toString());
        AiSettings ai = new AiSettings(properties);
        ai.load();
        GitLabSettings gitlab = new GitLabSettings(properties);
        gitlab.load();
        return new Settings(ai, gitlab);
    }

    /** In-memory registry sink: enough to pin the merge contract without a database. */
    private static final class MemorySources implements SourceTransfer.Sink {
        final Map<String, RepoSource> rows = new LinkedHashMap<>();

        @Override
        public RepoSource find(String type, String location) {
            return rows.get(type + "|" + location);
        }

        @Override
        public RepoSource save(RepoSource source) {
            rows.put(source.type + "|" + SourceTransfer.normalizeLocation(source.location), source);
            return source;
        }
    }

    private static ConfigTransfer.SettingsSink sink(Settings settings) {
        return new ConfigTransfer.SettingsSink() {
            @Override
            public void applyAi(Map<String, Object> body) {
                settings.ai().update(body);
            }

            @Override
            public void applyGitLab(Map<String, Object> body) {
                settings.gitlab().update(body);
            }
        };
    }

    private static RepoSource source(String name, String type, String location, String... tags) {
        RepoSource source = new RepoSource();
        source.name = name;
        source.type = type;
        source.location = location;
        source.tags = new ArrayList<>(List.of(tags));
        return source;
    }

    private static Settings configured(Path dataDir) {
        Settings settings = settings(dataDir);
        settings.ai().update(Map.of(
                "enabled", true,
                "baseUrl", "https://api.deepseek.com/v1",
                "model", "deepseek-chat",
                "apiKey", API_KEY,
                "maxImpacts", 20,
                "timeoutSeconds", 90,
                "comments", false,
                "maxComments", 5,
                "reflection", false,
                "defaultForRuns", false));
        settings.gitlab().update(Map.of("token", TOKEN));
        return settings;
    }

    @Test
    void secretsAreOmittedUnlessTheCallerAsksForThem(@TempDir Path tmp) {
        Settings settings = configured(tmp);
        List<RepoSource> sources = List.of(source("alpha", "LOCAL_DIR", "/work/alpha", "core"));

        Map<String, Object> safe = ConfigTransfer.exportPayload(
                settings.ai().exportMap(false), settings.gitlab().exportMap(false),
                sources, false, Instant.EPOCH);
        assertEquals(false, safe.get("secretsIncluded"));
        Map<String, Object> safeSettings = mapOf(safe.get("settings"));
        assertFalse(mapOf(safeSettings.get("ai")).containsKey("apiKey"));
        assertTrue(mapOf(safeSettings.get("gitlab")).isEmpty());
        String safeJson = Json.write(safe);
        assertFalse(safeJson.contains(API_KEY), "默认导出不得包含 API Key：" + safeJson);
        assertFalse(safeJson.contains(TOKEN), "默认导出不得包含 GitLab 令牌：" + safeJson);
        // The registry still travels with it: this is the "one file" feature.
        assertEquals(1, ((List<?>) safe.get("sources")).size());

        Map<String, Object> withSecrets = ConfigTransfer.exportPayload(
                settings.ai().exportMap(true), settings.gitlab().exportMap(true),
                sources, true, Instant.EPOCH);
        assertEquals(true, withSecrets.get("secretsIncluded"));
        assertEquals(API_KEY, mapOf(mapOf(withSecrets.get("settings")).get("ai")).get("apiKey"));
        assertEquals(TOKEN, mapOf(mapOf(withSecrets.get("settings")).get("gitlab")).get("token"));
    }

    @Test
    void importRestoresSettingsAndSources(@TempDir Path originDir, @TempDir Path targetDir) {
        Settings origin = configured(originDir);
        List<RepoSource> sources = List.of(
                source("alpha", "LOCAL_DIR", "/work/alpha/", "core", "payment"),
                source("beta", "GITHUB", "https://github.com/acme/beta.git"));
        Map<String, Object> payload = ConfigTransfer.exportPayload(
                origin.ai().exportMap(true), origin.gitlab().exportMap(true),
                sources, true, Instant.EPOCH);

        Settings target = settings(targetDir);
        MemorySources registry = new MemorySources();
        Map<String, Object> result = ConfigTransfer.importPayload(payload, sink(target), registry);

        Map<String, Object> settingsResult = mapOf(result.get("settings"));
        assertEquals(List.of("ai", "gitlab"), settingsResult.get("applied"));
        assertEquals(List.of(), settingsResult.get("errors"));
        assertEquals(true, settingsResult.get("secretsImported"));
        assertEquals(true, target.ai().view().get("enabled"));
        assertEquals("deepseek-chat", target.ai().view().get("model"));
        assertEquals(20, target.ai().view().get("maxImpacts"));
        assertEquals(90, target.ai().view().get("timeoutSeconds"));
        assertEquals(false, target.ai().view().get("comments"));
        assertEquals(false, target.ai().view().get("reflection"));
        assertEquals(true, target.gitlab().view().get("tokenSet"));
        // Secrets crossed only because the export asked for them.
        assertEquals(API_KEY, target.ai().exportMap(true).get("apiKey"));
        assertEquals(TOKEN, target.gitlab().exportMap(true).get("token"));

        Map<String, Object> sourceResult = mapOf(result.get("sources"));
        assertEquals(2, sourceResult.get("total"));
        assertEquals(2, sourceResult.get("created"));
        assertEquals(0, sourceResult.get("updated"));
        assertEquals(List.of("core", "payment"), registry.rows.get("LOCAL_DIR|/work/alpha").tags);

        // Importing the same file again must merge, not duplicate: this is the idempotency the
        // sources transfer already promises, now covering the settings too.
        Map<String, Object> again = ConfigTransfer.importPayload(payload, sink(target), registry);
        assertEquals(0, mapOf(again.get("sources")).get("created"));
        assertEquals(2, mapOf(again.get("sources")).get("updated"));
        assertEquals(2, registry.rows.size());
    }

    @Test
    void aFileWithoutSecretsKeepsTheSecretsAlreadyStored(@TempDir Path tmp) {
        Settings settings = settings(tmp);
        settings.ai().update(Map.of("baseUrl", "https://local.test/v1", "model", "local-model",
                "apiKey", "own-api-key-value"));
        settings.gitlab().update(Map.of("token", "own-gitlab-token-value"));

        // A teammate's file: same shape, no secrets, a different model.
        Map<String, Object> payload = Map.of(
                "kind", ConfigTransfer.KIND,
                "version", 1,
                "settings", Map.of(
                        "ai", Map.of("enabled", true, "baseUrl", "https://shared.test/v1",
                                "model", "shared-model"),
                        "gitlab", Map.of()),
                "sources", List.of());
        Map<String, Object> result = ConfigTransfer.importPayload(payload, sink(settings), new MemorySources());

        // The empty gitlab section has nothing to apply, so it is not reported as applied.
        assertEquals(List.of("ai"), mapOf(result.get("settings")).get("applied"));
        assertEquals(false, mapOf(result.get("settings")).get("secretsImported"));
        assertEquals("shared-model", settings.ai().view().get("model"));
        // The shared file must not erase this machine's own credentials.
        assertEquals("own-api-key-value", settings.ai().exportMap(true).get("apiKey"));
        assertEquals("own-gitlab-token-value", settings.gitlab().exportMap(true).get("token"));
    }

    @Test
    void aFileWithoutSecretsDoesNotRewriteTheGitLabSnapshot(@TempDir Path tmp) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(tmp.toString());
        properties.getGitlab().setToken("baseline-gitlab-token");
        AiSettings ai = new AiSettings(properties);
        ai.load();
        GitLabSettings gitlab = new GitLabSettings(properties);
        gitlab.load();
        Settings settings = new Settings(ai, gitlab);

        Map<String, Object> payload = Map.of(
                "kind", ConfigTransfer.KIND,
                "version", 1,
                "settings", Map.of("ai", Map.of("model", "shared-model"), "gitlab", Map.of()),
                "sources", List.of());
        ConfigTransfer.importPayload(payload, sink(settings), new MemorySources());

        // The token from application.yml survives, and no local snapshot was created that would
        // override application.yml on the next start.
        assertEquals("baseline-gitlab-token", settings.gitlab().exportMap(true).get("token"));
        assertEquals("application.yml", settings.gitlab().view().get("configuredBy"));
    }

    @Test
    void aBrokenSectionIsReportedWithoutBlockingTheRest(@TempDir Path tmp) {
        Settings settings = settings(tmp);
        MemorySources registry = new MemorySources();
        Map<String, Object> payload = Map.of(
                "kind", ConfigTransfer.KIND,
                "version", 1,
                "settings", Map.of(
                        "ai", Map.of("enabled", true, "baseUrl", "ftp://bad.test/v1", "model", "m"),
                        "gitlab", Map.of("token", "new-gitlab-token-value")),
                "sources", List.of(Map.of("name", "gamma", "type", "LOCAL_DIR", "location", "/work/gamma")));

        Map<String, Object> result = ConfigTransfer.importPayload(payload, sink(settings), registry);

        Map<String, Object> settingsResult = mapOf(result.get("settings"));
        assertEquals(List.of("gitlab"), settingsResult.get("applied"));
        List<?> errors = (List<?>) settingsResult.get("errors");
        assertEquals(1, errors.size());
        assertEquals("ai", mapOf(errors.get(0)).get("section"));
        assertTrue(String.valueOf(mapOf(errors.get(0)).get("reason")).contains("http://"),
                String.valueOf(mapOf(errors.get(0)).get("reason")));
        assertEquals(false, settings.ai().view().get("enabled"), "被拒绝的段落不得部分生效");
        assertEquals(true, settings.gitlab().view().get("tokenSet"));
        assertEquals(1, mapOf(result.get("sources")).get("created"));
    }

    @Test
    void aSourcesOnlyExportIsAccepted(@TempDir Path tmp) {
        Settings settings = settings(tmp);
        MemorySources registry = new MemorySources();
        Map<String, Object> payload = SourceTransfer.exportPayload(
                List.of(source("alpha", "LOCAL_DIR", "/work/alpha")), Instant.EPOCH);

        Map<String, Object> result = ConfigTransfer.importPayload(payload, sink(settings), registry);

        assertEquals(List.of(), mapOf(result.get("settings")).get("applied"));
        assertEquals(1, mapOf(result.get("sources")).get("created"));
        assertEquals(1, registry.rows.size());
    }

    @Test
    void aNewerPayloadVersionIsRejected(@TempDir Path tmp) {
        Settings settings = settings(tmp);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConfigTransfer.importPayload(
                        Map.of("kind", ConfigTransfer.KIND, "version", 2),
                        sink(settings), new MemorySources()));
        assertTrue(e.getMessage().contains("更新的版本"), e.getMessage());
    }

    @Test
    void aFileFromAnotherToolIsRejected(@TempDir Path tmp) {
        Settings settings = settings(tmp);
        IllegalArgumentException kind = assertThrows(IllegalArgumentException.class,
                () -> ConfigTransfer.importPayload(Map.of("kind", "other.tool"),
                        sink(settings), new MemorySources()));
        assertTrue(kind.getMessage().contains("不是本工具导出的配置文件"), kind.getMessage());

        IllegalArgumentException notObject = assertThrows(IllegalArgumentException.class,
                () -> ConfigTransfer.importPayload(List.of(), sink(settings), new MemorySources()));
        assertTrue(notObject.getMessage().contains("JSON 对象"), notObject.getMessage());
    }

    @Test
    void twoExportsOfTheSameWorkspaceDifferOnlyInTheirTimestamp(@TempDir Path tmp) {
        Settings settings = configured(tmp);
        List<RepoSource> sources = List.of(source("alpha", "LOCAL_DIR", "/work/alpha", "core"));
        Instant moment = Instant.parse("2026-10-30T03:20:00Z");
        String first = Json.write(ConfigTransfer.exportPayload(settings.ai().exportMap(false),
                settings.gitlab().exportMap(false), sources, false, moment));
        String second = Json.write(ConfigTransfer.exportPayload(settings.ai().exportMap(false),
                settings.gitlab().exportMap(false), sources, false, moment));

        assertEquals(first, second, "同一份配置的两次导出应当逐字节一致（时间戳固定时）");
        assertFalse(first.contains("settingsFile"), "导出的是可迁移的配置，不是本机路径");
        assertFalse(first.contains("budgetSeconds"), "派生值不属于配置");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
