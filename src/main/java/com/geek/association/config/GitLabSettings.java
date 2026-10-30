package com.geek.association.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.geek.association.util.Json;
import com.geek.association.util.LogEvents;
import com.geek.association.util.Texts;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Persists the GitLab personal access token edited in the settings page.
 *
 * <p>Same shape as {@link AiSettings}, for the same reasons: application.yml is deployment
 * configuration and a packaged jar is not writable, so a form that saved there would either edit a
 * deployed artifact or fail. The snapshot file is written atomically, restricted to the owner,
 * and read defensively - a truncated or hand-edited file degrades to "application.yml wins"
 * instead of stopping the service.
 *
 * <p>The token itself never leaves this class except as a credential handed to the Git transport:
 * {@link #view()} returns a mask, and no log line contains it. A source can still name its own
 * credential environment variable; that wins over this token (see {@code RepositoryPreparer}).
 */
@Component
public class GitLabSettings {

    private static final Logger log = LoggerFactory.getLogger(GitLabSettings.class);

    /**
     * A GitLab personal access token is currently 20 characters (glpat-...), and no supported
     * token type is anywhere near this bound. The limit exists so a paste of a whole config file
     * is rejected with a message instead of being sent to the Git server as a credential.
     */
    static final int MAX_TOKEN_LENGTH = 512;

    private final AppProperties properties;

    /** Whether the last load/update came from the snapshot file, which {@code configuredBy} reports. */
    private volatile boolean loadedFromFile;

    public GitLabSettings(AppProperties properties) {
        this.properties = properties;
    }

    /**
     * Applies the saved snapshot over the application.yml baseline.
     *
     * Never throws: a missing file is the normal first run, and a corrupt one must cost the user
     * their customisations, not the ability to start the service.
     */
    @PostConstruct
    public void load() {
        Path file = file();
        String baseline = properties.getGitlab().getToken();
        if (!Files.isRegularFile(file)) {
            log.info(LogEvents.of("gitlab.settings", "loaded", false, "source", "default",
                    "file", file, "tokenSet", !Texts.isBlank(baseline)));
            return;
        }
        try {
            JsonNode root = Json.mapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                log.warn(LogEvents.of("gitlab.settings", "loaded", false, "source", "default",
                        "file", file, "reason", "设置文件不是 JSON 对象，已沿用 application.yml"));
                return;
            }
            if (root.has("token")) {
                // A hand-edited file can hold whitespace that would corrupt the credential used in
                // the URL, so the same normalization the form applies is applied here.
                properties.getGitlab().setToken(normalize(root.path("token").asText("")));
            }
            loadedFromFile = true;
            log.info(LogEvents.of("gitlab.settings", "loaded", true, "source", "file", "file", file,
                    "tokenSet", !Texts.isBlank(properties.getGitlab().getToken())));
        } catch (IOException | RuntimeException e) {
            log.warn(LogEvents.of("gitlab.settings", "loaded", false, "source", "default",
                    "file", file, "error", e.toString()));
        }
    }

    /**
     * The payload of {@code GET /api/gitlab}.
     *
     * The token itself is never part of it, only whether one is set and a mask to recognize it by:
     * the form must show "something is stored" without a secret crossing the wire twice per page
     * load.
     */
    public Map<String, Object> view() {
        String token = properties.getGitlab().getToken();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tokenSet", !Texts.isBlank(token));
        out.put("tokenMasked", AiSettings.mask(token));
        out.put("configuredBy", loadedFromFile ? "file" : "application.yml");
        out.put("settingsFile", file().toString());
        return out;
    }

    /**
     * The token as a plain map, for the workspace export.
     *
     * <p>Only the token lives in this snapshot, and it is omitted unless the caller asked for
     * secrets: the export file is a shareable artifact by default, and a token that slipped in
     * silently would be the first thing leaked when the file is sent to a teammate.
     */
    public Map<String, Object> exportMap(boolean includeSecrets) {
        Map<String, Object> out = new LinkedHashMap<>();
        String token = properties.getGitlab().getToken();
        if (includeSecrets && !Texts.isBlank(token)) {
            out.put("token", token);
        }
        return out;
    }

    /**
     * Validates and applies a settings update, then persists the snapshot.
     *
     * The body is optional and the current token is the fallback, so the form can send only what it
     * changed. Two exceptions are spelled out rather than inferred: an absent or blank
     * {@code token} means "keep the stored one" (the form only ever holds a mask), and
     * {@code clearToken=true} is the explicit way to remove it.
     *
     * @throws IllegalArgumentException with a Chinese sentence for the 400 body
     */
    public synchronized Map<String, Object> update(Map<String, Object> body) {
        String current = properties.getGitlab().getToken();
        String token = current;
        if (body != null && Boolean.TRUE.equals(bool(body.get("clearToken")))) {
            token = "";
        } else if (body != null && body.containsKey("token")) {
            String sent = normalize(body.get("token") == null ? "" : body.get("token").toString());
            if (!sent.isBlank()) {
                if (sent.length() > MAX_TOKEN_LENGTH) {
                    throw new IllegalArgumentException("GitLab 令牌过长，请确认粘贴的是个人访问令牌而不是整段配置");
                }
                if (sent.chars().anyMatch(Character::isWhitespace)) {
                    throw new IllegalArgumentException("GitLab 令牌不能包含空格或换行");
                }
                token = sent;
            }
        }
        properties.getGitlab().setToken(token);
        loadedFromFile = persist(token);
        // No token, ever: this line is the one place that could leak it into a log file.
        log.info(LogEvents.of("gitlab.settings", "loaded", loadedFromFile,
                "source", loadedFromFile ? "file" : "default",
                "tokenSet", !Texts.isBlank(token)));
        return view();
    }

    /** Absolute path of the snapshot, so the settings card can show where the configuration lives. */
    public Path file() {
        return properties.dataPath().resolve("gitlab-settings.json");
    }

    /**
     * Writes the snapshot atomically and reports whether it landed.
     *
     * Same-directory {@code .tmp} + {@code ATOMIC_MOVE}, and chmod 600 where POSIX permissions
     * exist: the file is a credential, so a crash must not leave a partial secret and another
     * local user must not be able to read it. A failed write is not a failed request - the new
     * token is already live in memory.
     */
    private boolean persist(String token) {
        Path file = file();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("token", token);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Path directory = file.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            Files.writeString(tmp, Json.write(snapshot), StandardCharsets.UTF_8);
            restrictToOwner(tmp);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("无法写入 GitLab 设置文件 {}：{}", file, e.toString());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // A leftover .tmp is harmless; the next successful write replaces it.
            }
            return loadedFromFile;
        }
    }

    /** Best effort: a platform without POSIX permissions keeps its inherited ACLs. */
    private static void restrictToOwner(Path path) {
        try {
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException e) {
            // Windows and some mounted filesystems: nothing to do.
        }
    }

    /** Strips the whitespace a copy/paste adds; the token itself never contains any. */
    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * Lenient boolean read, matching the update-settings endpoint: "true"/"1" and true/1 are both
     * accepted, anything unrecognized keeps the current value.
     */
    private static Boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            String normalized = s.strip().toLowerCase(java.util.Locale.ROOT);
            if ("true".equals(normalized) || "1".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized) || "0".equals(normalized)) {
                return false;
            }
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        return null;
    }
}
