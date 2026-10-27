package com.geek.association.transfer;

import com.geek.association.model.RepoSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Export and import of the source registry, so a workspace can be moved between machines or
 * shared with a teammate.
 *
 * <p>Two decisions shape this class:
 *
 * <ul>
 *   <li><b>Runtime state is not exported.</b> A source row carries an id, a sync status and
 *       timestamps that only mean something on the machine that produced them. Exporting them
 *       would let an import overwrite a healthy source with a stale "MISSING" flag.</li>
 *   <li><b>Import merges rather than duplicates.</b> Two installations usually share most of
 *       their registry, so an import that appended blindly would double every entry. A source is
 *       identified by its type plus location; a match updates the existing row and unions the
 *       tags.</li>
 * </ul>
 *
 * <p>Nothing here touches Spring or the database: the caller supplies a {@link Sink}, which keeps
 * the merge rules unit-testable without a running context.
 */
public final class SourceTransfer {

    /** Marker written into the payload so a wrong file is rejected instead of half-imported. */
    public static final String KIND = "code-association.sources";

    /** Bumped when the payload shape changes in a way an older import could not read. */
    public static final int PAYLOAD_VERSION = 1;

    private static final Set<String> KNOWN_TYPES =
            Set.of("LOCAL_DIR", "LOCAL_GIT", "GITHUB", "GITLAB", "GIT");

    private SourceTransfer() {
        // static only
    }

    /** Where an import reads and writes sources. Implemented by {@code Store} in production. */
    public interface Sink {
        /** Existing source with this type and normalised location, or null. */
        RepoSource find(String type, String location);

        /** Persists and returns the source. */
        RepoSource save(RepoSource source);
    }

    // ------------------------------------------------------------------ export

    /**
     * Builds the export document.
     *
     * Field order is fixed so a diff between two exports of the same registry is empty; that is
     * what makes the file reviewable before it is imported somewhere else.
     */
    public static Map<String, Object> exportPayload(List<RepoSource> sources, Instant now) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("kind", KIND);
        root.put("version", PAYLOAD_VERSION);
        root.put("exportedAt", (now == null ? Instant.now() : now).toString());

        List<Map<String, Object>> rows = new ArrayList<>();
        if (sources != null) {
            for (RepoSource source : sources) {
                if (source == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", source.name);
                row.put("type", source.type);
                row.put("location", source.location);
                row.put("defaultRef", source.defaultRef);
                row.put("credentialEnv", source.credentialEnv);
                // The environment variable NAME is exported, never a secret: the value itself
                // lives in the operator's environment and is not in the database at all.
                row.put("tags", SourceTags.normalize(source.tags));
                rows.add(row);
            }
        }
        root.put("sources", rows);
        return root;
    }

    // ------------------------------------------------------------------ import

    /**
     * Imports a payload into {@code sink}.
     *
     * One bad row must not sink the whole file: a registry exported from a newer version may
     * carry a type this build does not know, and refusing the entire import would make the
     * feature useless exactly when it is needed. Failures are collected per row instead.
     *
     * @param raw  the parsed JSON: the export object, a bare array, or {@code {sources:[...]}}
     * @param sink where to read and write sources
     * @return totals plus the per-row errors, ready to be returned as the API response
     */
    public static Map<String, Object> importPayload(Object raw, Sink sink) {
        List<Map<String, Object>> entries = entriesOf(raw);
        int created = 0;
        int updated = 0;
        List<Map<String, Object>> errors = new ArrayList<>();

        for (int index = 0; index < entries.size(); index++) {
            Map<String, Object> entry = entries.get(index);
            String reason = validate(entry);
            if (reason != null) {
                errors.add(error(index, entry, reason));
                continue;
            }
            try {
                if (importOne(entry, sink)) {
                    updated++;
                } else {
                    created++;
                }
            } catch (RuntimeException e) {
                // A single row failing (bad encoding, unreadable path) is reported, not thrown:
                // the caller needs the counts of what did work.
                errors.add(error(index, entry, e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", entries.size());
        result.put("created", created);
        result.put("updated", updated);
        result.put("skipped", errors.size());
        result.put("errors", errors);
        return result;
    }

    /** @return true when an existing source was updated, false when a new one was created. */
    private static boolean importOne(Map<String, Object> entry, Sink sink) {
        String type = str(entry.get("type")).toUpperCase(Locale.ROOT);
        String location = normalizeLocation(str(entry.get("location")));

        RepoSource existing = sink.find(type, location);
        boolean isUpdate = existing != null;
        RepoSource target = isUpdate ? existing : new RepoSource();

        target.type = type;
        target.location = location;
        if (isUpdate) {
            // Name is a label: an import may rename it, but an empty name must not erase a name
            // the user chose deliberately.
            String name = str(entry.get("name"));
            if (!name.isBlank()) {
                target.name = name;
            }
        } else {
            target.name = str(entry.get("name"));
        }

        // Only a non-blank incoming value overwrites: an export taken before someone configured a
        // branch or a token must not wipe the configuration that exists here.
        String defaultRef = str(entry.get("defaultRef"));
        if (!defaultRef.isBlank()) {
            target.defaultRef = defaultRef;
        }
        String credentialEnv = str(entry.get("credentialEnv"));
        if (!credentialEnv.isBlank()) {
            target.credentialEnv = credentialEnv;
        }

        // Union, not replace: the tags on this installation are someone's curation, and an
        // import from another machine is an addition to it.
        List<String> incoming = SourceTags.normalize(entry.get("tags"));
        target.tags = isUpdate ? SourceTags.merge(target.tags, incoming) : incoming;

        if (!isUpdate) {
            target.createdAt = RepoSource.now();
            markAvailability(target, "导入时");
        }
        sink.save(target);
        return isUpdate;
    }

    /**
     * Records whether a source's location is usable right now.
     *
     * The whole point of an export is to carry a registry to another machine, where the paths
     * will not exist yet. Failing the import would defeat that; silently claiming OK would leave
     * the user staring at a source that cannot be analysed with no explanation. So the row is
     * stored with an explicit status the list view can render.
     *
     * The same judgement is needed when a source is edited: a row imported on this machine and
     * since pointed at the right directory still carries the verdict of the import, and the list
     * would keep calling it 目录缺失. Both callers share this method so the two paths cannot drift
     * into disagreeing about what counts as usable.
     *
     * @param moment when the check happened ("导入时", "编辑时"). It is a parameter rather than a
     *               constant because the stored sentence is shown to the user, and one that names
     *               an import on an edit describes an event that never took place.
     */
    public static void markAvailability(RepoSource source, String moment) {
        boolean local = "LOCAL_DIR".equals(source.type) || "LOCAL_GIT".equals(source.type);
        if (!local) {
            source.status = "OK";
            source.message = null;
            return;
        }
        try {
            java.nio.file.Path path = com.geek.association.source.PathBrowser.resolve(source.location);
            if (java.nio.file.Files.isDirectory(path)) {
                source.status = "OK";
                source.message = null;
            } else {
                source.status = "MISSING";
                source.message = moment + "目录不存在：" + path;
            }
        } catch (RuntimeException e) {
            source.status = "MISSING";
            source.message = moment + "目录不可用：" + source.location;
        }
    }

    /**
     * Compares locations the way a human would: a trailing slash is the same directory, and so is
     * leading or trailing whitespace. Without this, re-importing a hand-edited file would create
     * a duplicate of every source whose path was touched.
     */
    public static String normalizeLocation(String location) {
        String value = location == null ? "" : location.strip();
        while (value.length() > 1 && value.endsWith("/") && !value.endsWith("://")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** @return a human-readable reason when the row cannot be imported, or null when it can. */
    public static String validate(Map<String, Object> entry) {
        if (entry == null) {
            return "条目为空";
        }
        String type = str(entry.get("type")).toUpperCase(Locale.ROOT);
        if (type.isBlank()) {
            return "type 不能为空";
        }
        if (!KNOWN_TYPES.contains(type)) {
            return "不支持的 type：" + type;
        }
        if (normalizeLocation(str(entry.get("location"))).isBlank()) {
            return "location 不能为空";
        }
        return null;
    }

    // ----------------------------------------------------------------- helpers

    private static Map<String, Object> error(int index, Map<String, Object> entry, String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("index", index);
        row.put("name", entry == null ? null : str(entry.get("name")));
        row.put("reason", reason);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entriesOf(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add((Map<String, Object>) map);
                }
            }
            return out;
        }
        if (raw instanceof Map<?, ?> map) {
            Object sources = map.get("sources");
            if (sources instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> row) {
                        out.add((Map<String, Object>) row);
                    }
                }
                return out;
            }
            // A single source object is accepted too: it is what someone gets when they export
            // one row by hand or copy a block out of a bigger file.
            if (map.containsKey("location")) {
                out.add((Map<String, Object>) map);
            }
            return out;
        }
        throw new IllegalArgumentException("导入文件格式不正确：应为导出生成的 JSON 对象或数组");
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().strip();
    }
}
