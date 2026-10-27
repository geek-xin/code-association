package com.geek.association.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.geek.association.util.Json;
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
 * Persists the AI settings the user edits in the UI, on top of {@code application.yml}.
 *
 * Two reasons for a file rather than only the properties object:
 *
 * <ul>
 *   <li>application.yml is deployment configuration. A settings form that rewrote it would turn a
 *       user's experiment into a diff in the deployed artifact, and a packaged jar would not be
 *       writable at all.</li>
 *   <li>The file holds the whole snapshot, so what the form shows after a restart is exactly what
 *       was saved, not a merge of three sources whose precedence the user has to remember.</li>
 * </ul>
 *
 * The file is written atomically and read defensively: it can hold an API key, so a crash must not
 * leave a truncated secret behind, and a hand-edited or half-written file must degrade to
 * "application.yml wins" instead of stopping the service from starting.
 */
@Component
public class AiSettings {

    private static final Logger log = LoggerFactory.getLogger(AiSettings.class);

    /** Bounds of {@link AppProperties.Ai#getMaxImpacts()}, kept here so the message and check agree. */
    private static final int MAX_IMPACTS_MIN = 1;
    private static final int MAX_IMPACTS_MAX = 50;
    private static final int TIMEOUT_MIN = 5;
    private static final int TIMEOUT_MAX = 300;

    private final AppProperties properties;
    /** Whether the file was read successfully, which is what {@code configuredBy} reports. */
    private volatile boolean loadedFromFile;

    public AiSettings(AppProperties properties) {
        this.properties = properties;
    }

    /**
     * Applies the saved snapshot over the application.yml baseline.
     *
     * Never throws. A missing file is the normal case (nobody has opened the settings page yet), and
     * a corrupt or unreadable one must cost the user their customisations, not the ability to start
     * the service - the baseline in application.yml is still a complete configuration.
     */
    @PostConstruct
    public void load() {
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode root = Json.mapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                log.warn("AI 设置文件不是 JSON 对象，已忽略并沿用 application.yml：{}", file);
                return;
            }
            AppProperties.Ai ai = properties.getAi();
            if (root.has("enabled")) {
                ai.setEnabled(root.path("enabled").asBoolean(ai.isEnabled()));
            }
            if (root.has("baseUrl")) {
                ai.setBaseUrl(root.path("baseUrl").asText(ai.getBaseUrl()));
            }
            if (root.has("model")) {
                ai.setModel(root.path("model").asText(ai.getModel()));
            }
            if (root.has("apiKey")) {
                ai.setApiKey(root.path("apiKey").asText(ai.getApiKey()));
            }
            if (root.has("maxImpacts")) {
                int value = root.path("maxImpacts").asInt(ai.getMaxImpacts());
                // A file written by an older build (or by hand) can hold a value this build
                // rejects; clamping keeps the prompt bounded instead of failing the whole load.
                ai.setMaxImpacts(clamp(value, MAX_IMPACTS_MIN, MAX_IMPACTS_MAX, ai.getMaxImpacts()));
            }
            if (root.has("timeoutSeconds")) {
                int value = root.path("timeoutSeconds").asInt(ai.getTimeoutSeconds());
                ai.setTimeoutSeconds(clamp(value, TIMEOUT_MIN, TIMEOUT_MAX, ai.getTimeoutSeconds()));
            }
            if (root.has("defaultForRuns")) {
                ai.setDefaultForRuns(root.path("defaultForRuns").asBoolean(ai.isDefaultForRuns()));
            }
            loadedFromFile = true;
            log.info("已从 {} 加载 AI 设置：enabled={} model={}", file, ai.isEnabled(), ai.getModel());
        } catch (IOException | RuntimeException e) {
            // The file is user-writable and may be truncated by a crash or edited by hand. Losing
            // the customisations is recoverable; refusing to boot over a settings file is not.
            log.warn("无法读取 AI 设置文件 {}，已沿用 application.yml：{}", file, e.toString());
        }
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value < min || value > max) {
            return fallback;
        }
        return value;
    }

    /**
     * The payload of {@code GET /api/ai}.
     *
     * The key itself is never part of it, only whether one is set and a mask to recognise it by: the
     * form has to show "something is stored" without a secret crossing the wire twice per page load.
     */
    public Map<String, Object> view() {
        AppProperties.Ai ai = properties.getAi();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", ai.isEnabled());
        out.put("baseUrl", ai.getBaseUrl());
        out.put("model", ai.getModel());
        out.put("apiKeySet", !Texts.isBlank(ai.getApiKey()));
        out.put("apiKeyMasked", mask(ai.getApiKey()));
        out.put("maxImpacts", ai.getMaxImpacts());
        out.put("timeoutSeconds", ai.getTimeoutSeconds());
        out.put("defaultForRuns", ai.isDefaultForRuns());
        out.put("ready", ai.ready());
        out.put("reason", ai.notReadyReason());
        out.put("configuredBy", loadedFromFile ? "file" : "application.yml");
        out.put("settingsFile", file().toString());
        return out;
    }

    /**
     * Validates and applies a settings update, then persists the whole snapshot.
     *
     * Every field is optional, and the current value is the fallback for a missing one, so the form
     * can send only what it changed. Two exceptions are spelled out rather than inferred:
     * {@code apiKey} that is absent or empty means "keep the stored key" (the form only ever holds
     * the mask), and {@code clearApiKey=true} is the explicit way to remove it.
     *
     * Validation happens before anything is applied or written, so a rejected request leaves the
     * running configuration untouched instead of half-updated.
     *
     * @throws IllegalArgumentException with a Chinese sentence for the 400 body
     */
    public Map<String, Object> update(Map<String, Object> body) {
        AppProperties.Ai ai = properties.getAi();
        synchronized (this) {
            boolean enabled = body != null && body.containsKey("enabled")
                    ? flag(body.get("enabled"), ai.isEnabled()) : ai.isEnabled();
            String baseUrl = body != null && body.containsKey("baseUrl")
                    ? normalizeBaseUrl(text(body.get("baseUrl"), ai.getBaseUrl())) : ai.getBaseUrl();
            String model = body != null && body.containsKey("model")
                    ? text(body.get("model"), ai.getModel()) : ai.getModel();
            int maxImpacts = body != null && body.containsKey("maxImpacts")
                    ? number(body.get("maxImpacts"), ai.getMaxImpacts()) : ai.getMaxImpacts();
            int timeoutSeconds = body != null && body.containsKey("timeoutSeconds")
                    ? number(body.get("timeoutSeconds"), ai.getTimeoutSeconds()) : ai.getTimeoutSeconds();
            boolean defaultForRuns = body != null && body.containsKey("defaultForRuns")
                    ? flag(body.get("defaultForRuns"), ai.isDefaultForRuns()) : ai.isDefaultForRuns();

            // The protocol is checked before "is it filled in" so a half-typed URL gets the message
            // that names the actual problem instead of "已启用但未填写接口地址".
            if (!Texts.isBlank(baseUrl) && !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                throw new IllegalArgumentException("接口地址需以 http:// 或 https:// 开头");
            }
            if (enabled && Texts.isBlank(baseUrl)) {
                throw new IllegalArgumentException("已启用 AI 审核，但未填写接口地址");
            }
            if (enabled && Texts.isBlank(model)) {
                throw new IllegalArgumentException("已启用 AI 审核，但未填写模型");
            }
            if (maxImpacts < MAX_IMPACTS_MIN || maxImpacts > MAX_IMPACTS_MAX) {
                throw new IllegalArgumentException("每条分析最多影响条数需在 " + MAX_IMPACTS_MIN
                        + ".." + MAX_IMPACTS_MAX + " 之间");
            }
            if (timeoutSeconds < TIMEOUT_MIN || timeoutSeconds > TIMEOUT_MAX) {
                throw new IllegalArgumentException("请求超时需在 " + TIMEOUT_MIN + ".." + TIMEOUT_MAX + " 秒之间");
            }

            String apiKey = ai.getApiKey();
            if (body != null && Boolean.TRUE.equals(bool(body.get("clearApiKey")))) {
                apiKey = "";
            } else if (body != null && body.containsKey("apiKey")) {
                // An empty string is the form saying "I did not touch this field", because the
                // input is only ever pre-filled with a mask and the real key is not in the browser.
                String sent = text(body.get("apiKey"), "");
                if (!sent.isBlank()) {
                    apiKey = sent.strip();
                }
            }

            // Applied only after every check passed: a 400 must not leave the process running with
            // half of the new settings.
            ai.setEnabled(enabled);
            ai.setBaseUrl(baseUrl);
            ai.setModel(model);
            ai.setApiKey(apiKey);
            ai.setMaxImpacts(maxImpacts);
            ai.setTimeoutSeconds(timeoutSeconds);
            ai.setDefaultForRuns(defaultForRuns);

            loadedFromFile = persist(ai);
        }
        AppProperties.Ai applied = properties.getAi();
        // No key, ever: this line is the one place that could leak it into a log file.
        log.info("AI 设置已更新：enabled={} baseUrl={} model={} maxImpacts={}",
                applied.isEnabled(), applied.getBaseUrl(), applied.getModel(), applied.getMaxImpacts());
        return view();
    }

    /** Absolute path of the snapshot, so the settings card can show where the configuration lives. */
    public Path file() {
        return properties.dataPath().resolve("ai-settings.json");
    }

    /**
     * Writes the snapshot atomically and reports whether it landed.
     *
     * Same-directory {@code .tmp} + {@code ATOMIC_MOVE} so a reader (or a crash) never sees a
     * partial file: the previous content survives until the rename, and the rename is one step on a
     * POSIX filesystem. The key makes the file a secret, so it is chmod 600 where the platform has
     * POSIX permissions; Windows silently skips that and relies on the user profile's ACLs.
     *
     * A failure to write is not a failure of the request: the new settings are already live in
     * memory, and the next start will fall back to application.yml. That is a smaller loss than
     * refusing a change the user just made because the data directory is read-only.
     */
    private boolean persist(AppProperties.Ai ai) {
        Path file = file();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("enabled", ai.isEnabled());
        snapshot.put("baseUrl", ai.getBaseUrl());
        snapshot.put("model", ai.getModel());
        snapshot.put("apiKey", ai.getApiKey());
        snapshot.put("maxImpacts", ai.getMaxImpacts());
        snapshot.put("timeoutSeconds", ai.getTimeoutSeconds());
        snapshot.put("defaultForRuns", ai.isDefaultForRuns());

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
                // A few filesystems (some network mounts) cannot rename atomically. A plain replace
                // is still better than not persisting at all, and the .tmp step already prevents a
                // partially written file from being mistaken for a complete one.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("无法写入 AI 设置文件 {}：{}", file, e.toString());
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
            // Windows and some mounted filesystems: nothing to do, and not worth a log line.
        }
    }

    /**
     * Mask for the UI: enough of the key to recognise it, never enough to use it.
     *
     * A short key is masked entirely rather than partly - showing 4 of 6 characters of a real
     * secret is a leak dressed up as a hint.
     */
    static String mask(String apiKey) {
        if (Texts.isBlank(apiKey)) {
            return "";
        }
        if (apiKey.length() <= 8) {
            return "••••";
        }
        return apiKey.substring(0, 4) + "…" + apiKey.substring(apiKey.length() - 4);
    }

    private static String normalizeBaseUrl(String value) {
        String url = Texts.trim(value);
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        return value.toString().strip();
    }

    /** Lenient integer read: the body is an untyped map, so a JSON string is a valid input too. */
    private static int number(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.strip());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    /**
     * Lenient boolean read, matching the update-settings endpoint: "true"/"1" and true/1 are both
     * accepted, anything unrecognised keeps the current value rather than flipping a switch.
     */
    private static boolean flag(Object value, boolean fallback) {
        Boolean parsed = bool(value);
        return parsed == null ? fallback : parsed;
    }

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
