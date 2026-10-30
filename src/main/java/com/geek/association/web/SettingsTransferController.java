package com.geek.association.web;

import com.geek.association.config.AiSettings;
import com.geek.association.config.GitLabSettings;
import com.geek.association.model.RepoSource;
import com.geek.association.store.Store;
import com.geek.association.transfer.ConfigTransfer;
import com.geek.association.transfer.SourceTransfer;
import com.geek.association.util.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Import and export of the whole workspace configuration in one file.
 *
 * <p>A separate controller rather than more methods on {@link ApiController}: these endpoints have
 * their own two collaborators (the settings snapshots) and touch no analysis state, and keeping
 * them together makes the download/import contract readable in one place.
 */
@RestController
@RequestMapping("/api")
public class SettingsTransferController {

    private static final Logger log = LoggerFactory.getLogger(SettingsTransferController.class);

    /** UTC stamp in the filename, so two exports of the same day do not overwrite each other. */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final AiSettings aiSettings;
    private final GitLabSettings gitLabSettings;
    private final Store store;

    public SettingsTransferController(AiSettings aiSettings, GitLabSettings gitLabSettings, Store store) {
        this.aiSettings = aiSettings;
        this.gitLabSettings = gitLabSettings;
        this.store = store;
    }

    /**
     * Downloads the configuration as a JSON attachment.
     *
     * <p>{@code secrets=1} is the explicit opt-in for the API key and the GitLab token. Without it
     * the file is safe to hand to a teammate; with it the file is a credential, which is why the
     * response is marked {@code no-store} - a shared machine's browser cache must not keep a
     * second copy of it.
     */
    @GetMapping("/settings/export")
    public ResponseEntity<Map<String, Object>> export(
            @RequestParam(name = "secrets", required = false) String secrets) {
        boolean includeSecrets = truthy(secrets);
        Instant now = Instant.now();
        Map<String, Object> payload = ConfigTransfer.exportPayload(
                aiSettings.exportMap(includeSecrets),
                gitLabSettings.exportMap(includeSecrets),
                store.sources(),
                includeSecrets,
                now);
        String filename = "code-association-config-" + STAMP.format(now) + ".json";
        // Counts only: the payload can contain credentials, and this is the one place that could
        // copy them into a log file.
        log.info(LogEvents.of("config.export",
                "sources", sizeOf(payload.get("sources")),
                "secrets", includeSecrets,
                "file", filename));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload);
    }

    /**
     * Applies a previously exported configuration.
     *
     * <p>Partial success is the normal case - a section written by a newer build, a source whose
     * type this build does not know - so the response always carries what was applied and what was
     * rejected, and the status stays 200. Only a file that is not a configuration export at all
     * (wrong kind, newer payload version) is refused up front, before anything is touched.
     */
    @PostMapping("/settings/import")
    public Map<String, Object> importConfig(@RequestBody Object body) {
        ConfigTransfer.SettingsSink settings = new ConfigTransfer.SettingsSink() {
            @Override
            public void applyAi(Map<String, Object> values) {
                aiSettings.update(values);
            }

            @Override
            public void applyGitLab(Map<String, Object> values) {
                gitLabSettings.update(values);
            }
        };
        SourceTransfer.Sink sources = new SourceTransfer.Sink() {
            @Override
            public RepoSource find(String type, String location) {
                return store.findByTypeAndLocation(type, location);
            }

            @Override
            public RepoSource save(RepoSource source) {
                return store.saveSource(source);
            }
        };
        Map<String, Object> result = ConfigTransfer.importPayload(body, settings, sources);
        Map<String, Object> appliedSettings = mapOf(result.get("settings"));
        Map<String, Object> importedSources = mapOf(result.get("sources"));
        log.info(LogEvents.of("config.import",
                "applied", sizeOf(appliedSettings.get("applied")),
                "errors", sizeOf(appliedSettings.get("errors")),
                "secrets", Boolean.TRUE.equals(appliedSettings.get("secretsImported")),
                "sources", number(importedSources.get("created")) + number(importedSources.get("updated")),
                "skipped", number(importedSources.get("skipped"))));
        return result;
    }

    /** Lenient read of the export flag: a hand-written URL may spell it any of these ways. */
    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return "1".equals(normalized) || "true".equals(normalized)
                || "yes".equals(normalized) || "on".equals(normalized);
    }

    private static int sizeOf(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private static int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
