package com.geek.association;

import com.geek.association.config.AiSettings;
import com.geek.association.config.AppProperties;
import com.geek.association.config.GitLabSettings;
import com.geek.association.model.RepoSource;
import com.geek.association.store.Store;
import com.geek.association.transfer.ConfigTransfer;
import com.geek.association.util.Json;
import com.geek.association.web.SettingsTransferController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The configuration export/import endpoints, wired through the same collaborators the server uses.
 *
 * <p>Three behaviours matter: the export is a download (not a JSON blob rendered in a tab), the
 * credentials only travel when they were explicitly requested, and an import applies what it can
 * and reports the rest instead of failing the whole file.
 */
class SettingsTransferEndpointTest {

    private record Fixture(SettingsTransferController controller, AiSettings ai, GitLabSettings gitlab,
                           Store store) {
    }

    private static Fixture fixture(Path temp) throws Exception {
        AppProperties properties = new AppProperties();
        properties.setDataDir(temp.resolve("data").toString());
        Store store = new Store(properties);
        store.init();
        AiSettings ai = new AiSettings(properties);
        ai.load();
        GitLabSettings gitlab = new GitLabSettings(properties);
        gitlab.load();
        return new Fixture(new SettingsTransferController(ai, gitlab, store), ai, gitlab, store);
    }

    private static RepoSource localSource(Path temp, String name) {
        RepoSource source = new RepoSource();
        source.name = name;
        source.type = "LOCAL_DIR";
        source.location = temp.resolve(name).toString();
        return source;
    }

    @Test
    void exportIsADownloadAndOmitsSecretsByDefault(@TempDir Path temp) throws Exception {
        Fixture f = fixture(temp);
        f.ai().update(Map.of("baseUrl", "https://api.deepseek.com/v1", "model", "m",
                "apiKey", "endpoint-api-key"));
        f.gitlab().update(Map.of("token", "endpoint-gitlab-token"));
        f.store().saveSource(localSource(temp, "alpha"));

        ResponseEntity<Map<String, Object>> response = f.controller().export(null);

        assertEquals(200, response.getStatusCode().value());
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.startsWith("attachment; filename=\"code-association-config-"), disposition);
        assertTrue(disposition.endsWith(".json\""), disposition);
        // The file can contain credentials when asked; the browser must not keep a cached copy.
        assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));

        Map<String, Object> body = response.getBody();
        assertEquals(ConfigTransfer.KIND, body.get("kind"));
        assertEquals(false, body.get("secretsIncluded"));
        String raw = Json.write(body);
        assertFalse(raw.contains("endpoint-api-key"), "默认导出不得包含 API Key");
        assertFalse(raw.contains("endpoint-gitlab-token"), "默认导出不得包含 GitLab 令牌");
        assertEquals(1, ((List<?>) body.get("sources")).size(), "代码源随配置文件一起导出");
    }

    @Test
    void theSecretsFlagCarriesTheCredentials(@TempDir Path temp) throws Exception {
        Fixture f = fixture(temp);
        f.ai().update(Map.of("baseUrl", "https://api.deepseek.com/v1", "model", "m",
                "apiKey", "endpoint-api-key"));
        f.gitlab().update(Map.of("token", "endpoint-gitlab-token"));

        ResponseEntity<Map<String, Object>> response = f.controller().export("1");

        Map<String, Object> body = response.getBody();
        assertEquals(true, body.get("secretsIncluded"));
        assertEquals("endpoint-api-key", mapOf(mapOf(body.get("settings")).get("ai")).get("apiKey"));
        assertEquals("endpoint-gitlab-token", mapOf(mapOf(body.get("settings")).get("gitlab")).get("token"));
    }

    @Test
    void importAppliesSettingsAndSourcesAndReportsTheOutcome(@TempDir Path temp) throws Exception {
        Fixture f = fixture(temp);
        Map<String, Object> payload = ConfigTransfer.exportPayload(
                Map.of("enabled", true, "baseUrl", "https://shared.test/v1", "model", "shared-model",
                        "apiKey", "imported-api-key"),
                Map.of("token", "imported-gitlab-token"),
                List.of(localSource(temp, "alpha")),
                true,
                Instant.EPOCH);

        Map<String, Object> result = f.controller().importConfig(payload);

        assertEquals(List.of("ai", "gitlab"), mapOf(result.get("settings")).get("applied"));
        assertEquals(true, mapOf(result.get("settings")).get("secretsImported"));
        assertEquals(1, mapOf(result.get("sources")).get("created"));
        assertEquals(true, f.ai().view().get("apiKeySet"));
        assertEquals("shared-model", f.ai().view().get("model"));
        assertEquals(true, f.gitlab().view().get("tokenSet"));
        assertEquals(1, f.store().sources().size());
    }

    @Test
    void aFileFromAnotherToolIsRefused(@TempDir Path temp) throws Exception {
        Fixture f = fixture(temp);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> f.controller().importConfig(Map.of("kind", "other.tool")));
        assertTrue(e.getMessage().contains("不是本工具导出的配置文件"), e.getMessage());
        assertEquals(false, f.ai().view().get("apiKeySet"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
