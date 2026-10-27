package com.geek.association;

import com.geek.association.model.RepoSource;
import com.geek.association.transfer.SourceTags;
import com.geek.association.transfer.SourceTransfer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Export shape and import merge rules.
 *
 * The sink is an in-memory fake: importing is a pure decision about which row to create versus
 * which to update, and that decision is exactly what needs to be pinned down here.
 */
class SourceTransferTest {

    /** Minimal in-memory sink, mirroring how Store matches and saves rows. */
    private static final class FakeSink implements SourceTransfer.Sink {
        final List<RepoSource> rows = new ArrayList<>();
        long nextId = 1;

        @Override
        public RepoSource find(String type, String location) {
            for (RepoSource row : rows) {
                if (row.type.equalsIgnoreCase(type)
                        && SourceTransfer.normalizeLocation(row.location)
                        .equals(SourceTransfer.normalizeLocation(location))) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public RepoSource save(RepoSource source) {
            RepoSource existing = source.id == 0 ? null : source;
            if (existing != null && rows.contains(existing)) {
                return existing;
            }
            source.id = nextId++;
            rows.add(source);
            return source;
        }
    }

    private static RepoSource source(String name, String type, String location, String... tags) {
        RepoSource s = new RepoSource();
        s.name = name;
        s.type = type;
        s.location = location;
        s.defaultRef = "main";
        s.tags = new ArrayList<>(List.of(tags));
        return s;
    }

    private static Map<String, Object> entry(String name, String type, String location, Object tags) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("type", type);
        row.put("location", location);
        if (tags != null) {
            row.put("tags", tags);
        }
        return row;
    }

    // ------------------------------------------------------------------ export

    @Test
    void exportCarriesTheMarkerAndVersion() {
        Map<String, Object> payload = SourceTransfer.exportPayload(List.of(), Instant.parse("2026-10-26T06:30:00Z"));

        assertEquals(SourceTransfer.KIND, payload.get("kind"));
        assertEquals(SourceTransfer.PAYLOAD_VERSION, payload.get("version"));
        assertEquals("2026-10-26T06:30:00Z", payload.get("exportedAt"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportOmitsRuntimeFieldsAndKeepsConfiguration() {
        RepoSource s = source("order", "LOCAL_GIT", "/work/order", "payment", "legacy");
        s.id = 42;
        s.status = "MISSING";
        s.message = "gone";
        s.createdAt = "2020-01-01T00:00:00Z";
        s.lastSyncedAt = "2020-01-02T00:00:00Z";
        s.credentialEnv = "GITHUB_TOKEN";

        Map<String, Object> payload = SourceTransfer.exportPayload(List.of(s), Instant.now());
        Map<String, Object> row = ((List<Map<String, Object>>) payload.get("sources")).get(0);

        // Runtime state belongs to the machine that produced it; exporting it would let an
        // import overwrite a healthy source with a stale status.
        assertFalse(row.containsKey("id"), "id must not travel");
        assertFalse(row.containsKey("status"), "status must not travel");
        assertFalse(row.containsKey("message"));
        assertFalse(row.containsKey("createdAt"));
        assertFalse(row.containsKey("lastSyncedAt"));
        // Configuration does travel: it is what the other machine needs to reproduce the setup.
        assertEquals("main", row.get("defaultRef"));
        assertEquals("GITHUB_TOKEN", row.get("credentialEnv"));
        assertEquals(List.of("payment", "legacy"), row.get("tags"));
    }

    @Test
    void exportNormalisesTagsSoTheFileIsStable() {
        RepoSource s = source("order", "LOCAL_DIR", "/w", "b", "a", "b");
        Map<String, Object> payload = SourceTransfer.exportPayload(List.of(s), Instant.now());
        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((List<Map<String, Object>>) payload.get("sources")).get(0);
        assertEquals(List.of("b", "a"), row.get("tags"));
    }

    @Test
    void exportGivesEveryRowATagArrayEvenWhenNull() {
        RepoSource s = source("order", "LOCAL_DIR", "/w");
        s.tags = null;
        Map<String, Object> payload = SourceTransfer.exportPayload(List.of(s), Instant.now());
        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((List<Map<String, Object>>) payload.get("sources")).get(0);
        assertEquals(List.of(), row.get("tags"), "consumers must never see null");
    }

    // ------------------------------------------------------------------ import

    @Test
    void importCreatesUnknownSources() {
        FakeSink sink = new FakeSink();
        Map<String, Object> payload = new HashMap<>();
        payload.put("sources", List.of(entry("order", "LOCAL_DIR", "/work/order", List.of("payment"))));

        Map<String, Object> result = SourceTransfer.importPayload(payload, sink);

        assertEquals(1, result.get("created"));
        assertEquals(0, result.get("updated"));
        assertEquals(1, sink.rows.size());
        assertEquals(List.of("payment"), sink.rows.get(0).tags);
    }

    @Test
    void importMergesTagsIntoAnExistingSource() {
        FakeSink sink = new FakeSink();
        sink.save(source("order", "LOCAL_DIR", "/work/order", "payment"));

        Map<String, Object> payload = new HashMap<>();
        payload.put("sources", List.of(entry("order", "LOCAL_DIR", "/work/order", List.of("legacy", "payment"))));

        Map<String, Object> result = SourceTransfer.importPayload(payload, sink);

        assertEquals(1, result.get("updated"));
        assertEquals(0, result.get("created"));
        assertEquals(1, sink.rows.size(), "an import must not duplicate an existing source");
        // Union, not replace: the local curation stays and the incoming tag is added.
        assertEquals(List.of("payment", "legacy"), sink.rows.get(0).tags);
    }

    @Test
    void importTreatsTrailingSlashAndCaseAsTheSameSource() {
        FakeSink sink = new FakeSink();
        sink.save(source("order", "local_dir", "/work/order/"));

        Map<String, Object> payload = new HashMap<>();
        payload.put("sources", List.of(entry("order", "LOCAL_DIR", "/work/order", null)));

        Map<String, Object> result = SourceTransfer.importPayload(payload, sink);

        assertEquals(1, result.get("updated"), "a hand-edited path must not create a duplicate");
        assertEquals(1, sink.rows.size());
    }

    @Test
    void importOnlyOverwritesConfigurationWhenTheIncomingValueIsPresent() {
        FakeSink sink = new FakeSink();
        RepoSource existing = source("order", "LOCAL_DIR", "/work/order");
        existing.defaultRef = "develop";
        existing.credentialEnv = "MY_TOKEN";
        sink.save(existing);

        Map<String, Object> blank = new LinkedHashMap<>();
        blank.put("type", "LOCAL_DIR");
        blank.put("location", "/work/order");
        blank.put("defaultRef", "");
        blank.put("credentialEnv", "");
        Map<String, Object> payload = new HashMap<>();
        payload.put("sources", List.of(blank));
        SourceTransfer.importPayload(payload, sink);

        // An export taken before a branch was configured must not wipe it.
        assertEquals("develop", sink.rows.get(0).defaultRef);
        assertEquals("MY_TOKEN", sink.rows.get(0).credentialEnv);
    }

    @Test
    void importOverwritesConfigurationWhenTheIncomingValueIsSet() {
        FakeSink sink = new FakeSink();
        sink.save(source("order", "LOCAL_DIR", "/work/order"));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", "LOCAL_DIR");
        row.put("location", "/work/order");
        row.put("defaultRef", "release");
        Map<String, Object> payload = new HashMap<>();
        payload.put("sources", List.of(row));
        SourceTransfer.importPayload(payload, sink);

        assertEquals("release", sink.rows.get(0).defaultRef);
    }

    @Test
    void badRowsAreReportedWithoutAbortingTheRest() {
        FakeSink sink = new FakeSink();
        Map<String, Object> payload = new HashMap<>();
        payload.put("sources", List.of(
                entry("good", "LOCAL_DIR", "/work/good", List.of("a")),
                entry("bad", "NONSENSE", "/work/bad", null),
                entry("blank", "LOCAL_DIR", "   ", null),
                entry("also-good", "GITHUB", "https://github.com/x/y.git", List.of("b"))
        ));

        Map<String, Object> result = SourceTransfer.importPayload(payload, sink);

        assertEquals(2, result.get("created"));
        assertEquals(2, result.get("skipped"));
        assertEquals(2, sink.rows.size(), "valid rows still land");
        assertEquals(2, ((List<?>) result.get("errors")).size());
    }

    @Test
    void errorEntriesCarryIndexAndReason() {
        FakeSink sink = new FakeSink();
        Map<String, Object> payload = new HashMap<>();
        payload.put("sources", List.of(entry("bad", "LOCAL_DIR", "", null)));

        Map<String, Object> result = SourceTransfer.importPayload(payload, sink);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");

        assertEquals(0, errors.get(0).get("index"));
        assertNotNull(errors.get(0).get("reason"));
    }

    @Test
    void importAcceptsABareArray() {
        FakeSink sink = new FakeSink();
        List<Map<String, Object>> payload = List.of(entry("a", "LOCAL_DIR", "/w/a", List.of("x")));

        Map<String, Object> result = SourceTransfer.importPayload(payload, sink);

        assertEquals(1, result.get("created"));
        assertEquals(List.of("x"), sink.rows.get(0).tags);
    }

    @Test
    void importAcceptsASingleSourceObject() {
        FakeSink sink = new FakeSink();
        Map<String, Object> payload = entry("solo", "GITHUB", "https://github.com/a/b.git", List.of("t"));

        Map<String, Object> result = SourceTransfer.importPayload(payload, sink);

        assertEquals(1, result.get("created"), "a block copied out of a bigger file still imports");
    }

    @Test
    void importedLocalPathThatDoesNotExistIsMarkedMissing(@TempDir Path tmp) {
        FakeSink sink = new FakeSink();
        Path missing = tmp.resolve("not-here");
        Map<String, Object> payload = new HashMap<>();
        payload.put("sources", List.of(entry("gone", "LOCAL_DIR", missing.toString(), null)));

        SourceTransfer.importPayload(payload, sink);

        // Carrying a registry to another machine is the point of the feature, so a missing path
        // must be recorded rather than refused.
        assertEquals("MISSING", sink.rows.get(0).status);
        assertTrue(sink.rows.get(0).message.contains("不存在"));
    }

    @Test
    void importedLocalPathThatExistsIsMarkedOk(@TempDir Path tmp) {
        FakeSink sink = new FakeSink();
        Map<String, Object> payload = new HashMap<>();
        payload.put("sources", List.of(entry("here", "LOCAL_DIR", tmp.toString(), null)));

        SourceTransfer.importPayload(payload, sink);

        assertEquals("OK", sink.rows.get(0).status);
    }

    @Test
    void importDerivesNothingButKeepsTheGivenName() {
        FakeSink sink = new FakeSink();
        Map<String, Object> payload = new HashMap<>();
        payload.put("sources", List.of(entry("Custom Name", "LOCAL_DIR", "/w/x", null)));

        SourceTransfer.importPayload(payload, sink);

        assertEquals("Custom Name", sink.rows.get(0).name);
    }

    @Test
    void roundTripIsIdempotent() {
        FakeSink sink = new FakeSink();
        sink.save(source("order", "LOCAL_DIR", "/work/order", "payment"));
        Map<String, Object> exported = SourceTransfer.exportPayload(sink.rows, Instant.now());

        SourceTransfer.importPayload(exported, sink);

        assertEquals(1, sink.rows.size(), "importing your own export changes nothing");
        assertEquals(List.of("payment"), sink.rows.get(0).tags);
    }

    @Test
    void normalizeLocationDropsTrailingSlashButNeverEatsTheScheme() {
        assertEquals("/work/order", SourceTransfer.normalizeLocation("/work/order/"));
        assertEquals("/work/order", SourceTransfer.normalizeLocation("  /work/order  "));
        assertEquals("https://github.com", SourceTransfer.normalizeLocation("https://github.com/"));
        // A bare scheme must survive: reducing "https://" to "https:" would make two unreadable
        // entries compare equal for the wrong reason.
        assertEquals("https://", SourceTransfer.normalizeLocation("https://"));
        assertEquals("/", SourceTransfer.normalizeLocation("/"));
    }
}
