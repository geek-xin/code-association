package com.geek.association.transfer;

import com.geek.association.model.RepoSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Export and import of the whole workspace configuration: the settings snapshots plus the source
 * registry, in one file.
 *
 * <p>Two narrower transfers already exist - the AI and GitLab settings each own a JSON snapshot,
 * and {@link SourceTransfer} moves the registry. This class is the "move to a new machine" view of
 * the same data: one file a user can back up, review and hand to a teammate instead of three.
 *
 * <p>Decisions worth knowing:
 *
 * <ul>
 *   <li><b>Secrets are opt-in.</b> The API key and the GitLab token are omitted unless the caller
 *       asked for them. A configuration file is a shareable artifact by default, and a file that
 *       silently carried credentials would leak them the first time someone sent it over chat.
 *       Import mirrors the same rule: an absent secret keeps the value this machine already has,
 *       so a file shared by a colleague never wipes the recipient's own key.</li>
 *   <li><b>Sections fail independently.</b> A file exported by a newer build can hold one section
 *       this build rejects; refusing the whole import would make the feature useless exactly when
 *       it is needed. The result names the section and the reason and still applies the rest - the
 *       same contract {@link SourceTransfer} uses for bad rows.</li>
 *   <li><b>No Spring, no database.</b> The caller supplies both sinks, which keeps the payload
 *       shape and the merge rules unit-testable without a running context.</li>
 * </ul>
 */
public final class ConfigTransfer {

    /** Marker for a full configuration file, so a sources-only export is not mistaken for one. */
    public static final String KIND = "code-association.config";

    /** Bumped when the payload shape changes in a way an older import could not read. */
    public static final int PAYLOAD_VERSION = 1;

    private ConfigTransfer() {
        // static only
    }

    /** Applies one settings section; implemented over {@code AiSettings} / {@code GitLabSettings}. */
    public interface SettingsSink {
        void applyAi(Map<String, Object> body);

        void applyGitLab(Map<String, Object> body);
    }

    // ------------------------------------------------------------------ export

    /**
     * Builds the export document.
     *
     * <p>Field order is fixed so two exports of the same workspace are byte-identical when the
     * timestamp is the same; that is what makes the file reviewable before it is imported
     * somewhere else.
     *
     * @param ai      the AI settings snapshot, already stripped of secrets when not requested
     * @param gitlab  the GitLab settings snapshot, same rule
     * @param sources the registry; built through {@link SourceTransfer} so the two export paths
     *                cannot disagree about which fields a source has
     */
    public static Map<String, Object> exportPayload(Map<String, Object> ai, Map<String, Object> gitlab,
                                                    List<RepoSource> sources, boolean includeSecrets,
                                                    Instant now) {
        Instant moment = now == null ? Instant.now() : now;
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("kind", KIND);
        root.put("version", PAYLOAD_VERSION);
        root.put("exportedAt", moment.toString());
        root.put("secretsIncluded", includeSecrets);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("ai", ai == null ? Map.of() : ai);
        settings.put("gitlab", gitlab == null ? Map.of() : gitlab);
        root.put("settings", settings);
        // Reuse the registry export instead of rebuilding the row shape here: a field added to a
        // source later must show up in both files, and two hand-written copies would drift.
        root.put("sources", SourceTransfer.exportPayload(sources, moment).get("sources"));
        return root;
    }

    // ------------------------------------------------------------------ import

    /**
     * Imports a full configuration file, or a sources-only export.
     *
     * @param raw      the parsed JSON object
     * @param settings where the two settings sections are applied
     * @param sources  where the registry is merged
     * @return applied settings sections, per-section errors, and the registry counts/errors
     * @throws IllegalArgumentException when the file is not a configuration export at all, or was
     *                                  written by a newer payload version - in that case nothing
     *                                  is applied
     */
    public static Map<String, Object> importPayload(Object raw, SettingsSink settings,
                                                    SourceTransfer.Sink sources) {
        Map<String, Object> root = rootOf(raw);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", KIND);
        result.put("version", PAYLOAD_VERSION);
        result.put("settings", importSettings(root, settings));
        result.put("sources", importSources(root, sources));
        return result;
    }

    private static Map<String, Object> importSettings(Map<String, Object> root, SettingsSink settings) {
        List<String> applied = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        boolean secretsImported = false;
        Map<String, Object> node = mapOf(root.get("settings"));
        if (node != null) {
            Map<String, Object> ai = mapOf(node.get("ai"));
            if (ai != null && !ai.isEmpty()) {
                try {
                    settings.applyAi(ai);
                    applied.add("ai");
                    // Only a secret that actually landed counts: a rejected section applies none
                    // of its fields, so claiming the key was imported would be a lie.
                    secretsImported |= !str(ai.get("apiKey")).isBlank();
                } catch (RuntimeException e) {
                    errors.add(sectionError("ai", e));
                }
            }
            Map<String, Object> gitlab = mapOf(node.get("gitlab"));
            // An export without secrets carries an empty gitlab section. Applying it would be a
            // no-op that still rewrites this machine's settings file with whatever token it
            // currently holds - turning a shared, secret-free file into a local snapshot that
            // overrides application.yml on the next start. Nothing to apply means nothing to do.
            if (gitlab != null && !gitlab.isEmpty()) {
                try {
                    settings.applyGitLab(gitlab);
                    applied.add("gitlab");
                    secretsImported |= !str(gitlab.get("token")).isBlank();
                } catch (RuntimeException e) {
                    errors.add(sectionError("gitlab", e));
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applied", applied);
        out.put("errors", errors);
        out.put("secretsImported", secretsImported);
        return out;
    }

    private static Map<String, Object> importSources(Map<String, Object> root, SourceTransfer.Sink sink) {
        Object rows = root.get("sources");
        if (!(rows instanceof List<?>)) {
            // A settings-only file is valid; the registry section simply has nothing to do. The
            // shape stays the same so the UI can render both cases without a null guard.
            return emptySourceResult();
        }
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("sources", rows);
        return SourceTransfer.importPayload(wrapper, sink);
    }

    /**
     * Validates the envelope before anything is applied.
     *
     * <p>Accepts the full configuration file, a sources-only export (the existing
     * {@code code-association.sources} file) and a bare {@code {sources:[...]}} object: the three
     * are what a user can plausibly drop on the import button, and rejecting two of them would
     * send them looking for a different file for no reason.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> rootOf(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("导入文件格式不正确：应为导出生成的 JSON 对象");
        }
        Map<String, Object> root = (Map<String, Object>) map;
        String kind = str(root.get("kind"));
        boolean config = KIND.equals(kind);
        boolean sourcesOnly = SourceTransfer.KIND.equals(kind);
        if (!config && !sourcesOnly && !(root.get("sources") instanceof List<?>)) {
            throw new IllegalArgumentException("不是本工具导出的配置文件"
                    + (kind.isBlank() ? "（缺少 kind）" : "（kind=" + kind + "）"));
        }
        int version = number(root.get("version"), 1);
        if (version > PAYLOAD_VERSION) {
            throw new IllegalArgumentException("配置文件来自更新的版本（version=" + version
                    + "，本机支持到 " + PAYLOAD_VERSION + "），请先升级本程序再导入");
        }
        return root;
    }

    private static Map<String, Object> emptySourceResult() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", 0);
        out.put("created", 0);
        out.put("updated", 0);
        out.put("skipped", 0);
        out.put("errors", List.of());
        return out;
    }

    private static Map<String, Object> sectionError(String section, RuntimeException e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("section", section);
        row.put("reason", e.getMessage() == null ? e.toString() : e.getMessage());
        return row;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().strip();
    }

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
}
