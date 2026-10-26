package com.geek.association.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.geek.association.config.AppProperties;
import com.geek.association.util.Json;
import com.geek.association.util.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * What this installation has seen, downloaded, applied and rolled back, kept in
 * {@code {dataDir}/update-history.json}.
 *
 * The release list from GitHub is the outside world's view of the project; it says nothing about
 * this machine. A restart, a network outage or a rate-limited hour all leave the ledger readable, so
 * the settings page can still answer "which versions has this installation been through".
 *
 * Three properties are deliberate:
 *
 * <ul>
 *   <li><b>The ledger never throws.</b> It is bookkeeping, not a feature: a corrupt file, a full disk
 *       or a read-only data directory degrades to an empty (or in-memory) ledger and is logged.
 *       Failing the update check because a convenience file could not be written would invert the
 *       priorities - checking for updates is what must keep working;</li>
 *   <li><b>Writes are atomic.</b> The new content goes to a {@code .tmp} file that is then renamed
 *       over the ledger, so a crash mid-write cannot leave half a JSON document behind for the next
 *       start to choke on;</li>
 *   <li><b>Every public method is {@code synchronized}.</b> The ledger holds at most a few hundred
 *       entries and is written once per check, so one coarse lock costs nothing measurable and
 *       removes any chance of a reader observing a half-applied update.</li>
 * </ul>
 */
public class UpdateHistory {

    private static final Logger log = LoggerFactory.getLogger(UpdateHistory.class);

    /** File name under the data directory; also what {@code GET /api/update/history} reports. */
    public static final String FILE_NAME = "update-history.json";

    /**
     * Upper bound of retained versions.
     *
     * A release list is unbounded over the years and every entry carries its release notes, so an
     * unlimited ledger would grow without anyone noticing. Two hundred versions is far more history
     * than a UI scrolls through, and the newest ones are the ones anybody looks for.
     */
    public static final int MAX_ENTRIES = 200;

    private final Path file;

    /**
     * The ledger, keyed by normalised version. Loaded on first use and replaced as a whole; the
     * field is only touched while holding the monitor.
     */
    private Map<String, Entry> entries;
    /** Time of the last write, as an ISO-8601 instant, or null when nothing was ever recorded. */
    private String lastUpdatedAt;

    /** The production form: the ledger lives beside the database and the downloads. */
    public UpdateHistory(AppProperties properties) {
        this(properties.dataPath().resolve(FILE_NAME));
    }

    /** Visible so a test can point the ledger at a temporary file instead of a data directory. */
    public UpdateHistory(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    /** Where the ledger is written; exposed because the API reports it to the UI. */
    public Path file() {
        return file;
    }

    /**
     * Records a successful check: one entry per release, inserted or refreshed.
     *
     * {@code firstSeenAt} is written once and then left alone - it is the only fact the ledger knows
     * that nobody else can reconstruct later. Everything else describes the release as it is today
     * (notes get edited, a tag can be re-pointed), so refreshing is the honest thing to do.
     */
    public synchronized void record(List<ReleaseInfo> releases, Instant at) {
        if (releases == null || releases.isEmpty()) {
            return;
        }
        Instant now = at == null ? Instant.now() : at;
        Map<String, Entry> current = load();
        for (ReleaseInfo release : releases) {
            Version version = Version.tryParse(release.version);
            if (version == null) {
                // One malformed entry must not hide the other nineteen.
                continue;
            }
            Entry entry = current.get(version.toString());
            if (entry == null) {
                entry = new Entry();
                entry.version = version.toString();
                entry.firstSeenAt = now.toString();
                current.put(entry.version, entry);
            }
            entry.tag = release.tag;
            entry.name = release.name;
            entry.publishedAt = release.publishedAt;
            entry.prerelease = release.prerelease;
            entry.fromTag = release.fromTag;
            entry.notes = release.notes == null ? "" : release.notes;
            entry.url = release.url;
            entry.lastSeenAt = now.toString();
        }
        persist(current, now);
    }

    /** Remembers that this version's jar is in the data directory. */
    public synchronized void markDownloaded(String versionText, Instant at) {
        stamp(versionText, at, (entry, time) -> entry.downloadedAt = time);
    }

    /** Remembers that this version was copied over the running jar. */
    public synchronized void markApplied(String versionText, Instant at) {
        stamp(versionText, at, (entry, time) -> entry.appliedAt = time);
    }

    /** Remembers that the installation was rolled back, and to which version. */
    public synchronized void markRolledBack(String versionText, Instant at) {
        stamp(versionText, at, (entry, time) -> entry.rolledBackAt = time);
    }

    /**
     * The ledger as the API returns it: versions newest first plus the summary the UI shows.
     *
     * A plain {@code Map} rather than a record type because this is a wire shape: keys are added
     * here and the frontend reads them by name.
     */
    public synchronized Map<String, Object> snapshot() {
        List<Entry> ordered = ordered(load());
        List<Map<String, Object>> versions = new ArrayList<>(ordered.size());
        Instant firstSeen = null;
        for (Entry entry : ordered) {
            versions.add(entry.toMap());
            Instant seen = instantOf(entry.firstSeenAt);
            if (seen != null && (firstSeen == null || seen.isBefore(firstSeen))) {
                firstSeen = seen;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("versions", versions);
        out.put("count", versions.size());
        out.put("firstSeenAt", firstSeen == null ? null : firstSeen.toString());
        out.put("lastUpdatedAt", lastUpdatedAt);
        out.put("file", file.toString());
        return out;
    }

    /** How many versions the ledger knows; the status payload reports this as historyTracked. */
    public synchronized int size() {
        return load().size();
    }

    // ---------------------------------------------------------------- internals

    private void stamp(String versionText, Instant at, BiConsumer<Entry, String> setter) {
        Version version = Version.tryParse(versionText);
        if (version == null) {
            log.debug("ignoring a history mark for an unusable version: {}", versionText);
            return;
        }
        Instant now = at == null ? Instant.now() : at;
        Map<String, Entry> current = load();
        Entry entry = current.get(version.toString());
        if (entry == null) {
            // A version can be marked without ever having been recorded (a jar downloaded before
            // this feature existed, or a ledger that was cleaned up). The mark alone is still worth
            // keeping: it is a fact about this installation.
            entry = new Entry();
            entry.version = version.toString();
            entry.firstSeenAt = now.toString();
            entry.lastSeenAt = now.toString();
            current.put(entry.version, entry);
        }
        setter.accept(entry, now.toString());
        persist(current, now);
    }

    private void persist(Map<String, Entry> current, Instant at) {
        lastUpdatedAt = at.toString();
        prune(current);
        store(current);
    }

    /** Reads the ledger once, then keeps it; every later call is served from memory. */
    private Map<String, Entry> load() {
        if (entries != null) {
            return entries;
        }
        Map<String, Entry> loaded = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            try {
                JsonNode root = Json.mapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
                JsonNode versions = root == null ? null : root.path("versions");
                if (versions != null && versions.isArray()) {
                    for (JsonNode node : versions) {
                        Entry entry = Entry.from(node);
                        if (entry != null) {
                            loaded.put(entry.version, entry);
                        }
                    }
                    lastUpdatedAt = text(root, "lastUpdatedAt");
                } else {
                    log.warn("update history has no versions array, starting empty: {}", file);
                }
            } catch (IOException | RuntimeException e) {
                // Losing the history is survivable; losing the ability to check for updates is not.
                log.warn("could not read update history {}: {}", file, e.toString());
                loaded = new LinkedHashMap<>();
                lastUpdatedAt = null;
            }
        }
        entries = loaded;
        return entries;
    }

    private void store(Map<String, Entry> current) {
        List<Map<String, Object>> versions = new ArrayList<>(current.size());
        for (Entry entry : ordered(current)) {
            versions.add(entry.toMap());
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("lastUpdatedAt", lastUpdatedAt);
        root.put("versions", versions);

        Path directory = file.getParent();
        // Same directory, so the rename below stays on one filesystem and is therefore atomic.
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (directory != null) {
                Files.createDirectories(directory);
            }
            Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            // An unwritable data directory only costs persistence: the in-memory ledger stays
            // correct for the life of this process, which is better than failing the caller.
            log.warn("could not write update history {}: {}", file, e.toString());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // A leftover .tmp is harmless; the next successful write replaces it.
            }
        }
    }

    /** Drops the oldest versions once the ledger exceeds {@link #MAX_ENTRIES}. */
    private static void prune(Map<String, Entry> current) {
        if (current.size() <= MAX_ENTRIES) {
            return;
        }
        List<Entry> ordered = ordered(current);
        for (int i = MAX_ENTRIES; i < ordered.size(); i++) {
            current.remove(ordered.get(i).version);
        }
    }

    /**
     * Newest version first, which is the order the history list is read in.
     *
     * Sorting on the parsed {@link Version} rather than the string is what keeps {@code 1.2.10}
     * above {@code 1.2.9}; entries the parser rejects (only possible in a hand-edited file) sink to
     * the end so they are the first to be pruned instead of disturbing the rest.
     */
    private static List<Entry> ordered(Map<String, Entry> current) {
        List<Entry> out = new ArrayList<>(current.values());
        out.sort(Comparator.comparing((Entry entry) -> Version.tryParse(entry.version),
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .reversed());
        return out;
    }

    private static Instant instantOf(String text) {
        if (Texts.isBlank(text)) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return Texts.isBlank(text) ? null : text.strip();
    }

    /** One version as the ledger stores it. Mutable on purpose: the marks update single fields. */
    private static final class Entry {
        String version;
        String tag;
        String name;
        String publishedAt;
        boolean prerelease;
        boolean fromTag;
        String firstSeenAt;
        String lastSeenAt;
        String downloadedAt;
        String appliedAt;
        String rolledBackAt;
        String notes = "";
        String url;

        static Entry from(JsonNode node) {
            String raw = text(node, "version");
            Version parsed = raw == null ? null : Version.tryParse(raw);
            if (parsed == null) {
                log.debug("skipping a history entry without a usable version: {}", raw);
                return null;
            }
            Entry entry = new Entry();
            entry.version = parsed.toString();
            entry.tag = text(node, "tag");
            entry.name = text(node, "name");
            entry.publishedAt = text(node, "publishedAt");
            entry.prerelease = node.path("prerelease").asBoolean(false);
            entry.fromTag = node.path("fromTag").asBoolean(false);
            entry.firstSeenAt = text(node, "firstSeenAt");
            entry.lastSeenAt = text(node, "lastSeenAt");
            entry.downloadedAt = text(node, "downloadedAt");
            entry.appliedAt = text(node, "appliedAt");
            entry.rolledBackAt = text(node, "rolledBackAt");
            String notes = text(node, "notes");
            entry.notes = notes == null ? "" : notes;
            entry.url = text(node, "url");
            return entry;
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("version", version);
            out.put("tag", tag);
            out.put("name", name);
            out.put("publishedAt", publishedAt);
            out.put("prerelease", prerelease);
            out.put("fromTag", fromTag);
            out.put("firstSeenAt", firstSeenAt);
            out.put("lastSeenAt", lastSeenAt);
            out.put("downloadedAt", downloadedAt);
            out.put("appliedAt", appliedAt);
            out.put("rolledBackAt", rolledBackAt);
            out.put("notes", notes);
            out.put("url", url);
            return out;
        }
    }
}
