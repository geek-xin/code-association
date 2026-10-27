package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.update.GitHubVersionSource;
import com.geek.association.update.ReleaseInfo;
import com.geek.association.update.UpdateException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading tags alongside releases, and merging the two.
 *
 * The merge is the part worth pinning down: releases and tags describe overlapping versions, and
 * which side survives decides whether the history keeps its notes and assets. The loopback server
 * covers the request shape (both endpoints, same headers) without ever reaching GitHub - a test
 * against the real API would be flaky, rate limited and unable to reproduce a 500 on just one of
 * the two calls.
 */
class UpdateTagSourceTest {

    private static final String REPO = "geek-xin/code-association";

    private static final String RELEASES_JSON = """
            [
              {
                "tag_name": "v0.3.0",
                "name": "code-association 0.3.0",
                "published_at": "2026-10-26T03:23:56Z",
                "prerelease": false,
                "body": "## 变更\\n- 新增自动更新",
                "html_url": "https://github.com/geek-xin/code-association/releases/tag/v0.3.0",
                "assets": [
                  {"name": "code-association-0.3.0.jar", "size": 40860414,
                   "browser_download_url": "https://example.test/code-association-0.3.0.jar"}
                ]
              },
              {
                "tag_name": "v0.2.0",
                "name": "code-association 0.2.0",
                "published_at": "2026-09-01T10:00:00Z",
                "prerelease": false,
                "body": "",
                "assets": []
              }
            ]
            """;

    private static final String TAGS_JSON = """
            [
              {"name": "v0.3.0", "commit": {"sha": "aaa", "url": "https://api.test/c/aaa"}},
              {"name": "v0.2.0", "commit": {"sha": "bbb", "url": "https://api.test/c/bbb"}},
              {"name": "v0.1.0", "commit": {"sha": "ccc", "url": "https://api.test/c/ccc"}}
            ]
            """;

    // ------------------------------------------------------------- parseTags

    @Test
    void parsesTagsNewestFirst() {
        List<ReleaseInfo> tags = GitHubVersionSource.parseTags(TAGS_JSON, REPO);

        assertEquals(List.of("0.3.0", "0.2.0", "0.1.0"),
                tags.stream().map(tag -> tag.version).toList());
    }

    @Test
    void aTagCarriesOnlyWhatTheEndpointActuallyKnows() {
        ReleaseInfo tag = GitHubVersionSource.parseTags(TAGS_JSON, REPO).get(2);

        assertEquals("0.1.0", tag.version);
        assertEquals("v0.1.0", tag.tag);
        assertEquals("v0.1.0", tag.name, "a tag has no separate title, so its name is the tag");
        assertTrue(tag.fromTag, "the UI needs to tell a bare tag from a full release");
        assertEquals("", tag.notes, "no notes rather than null: the UI never has to handle null");
        assertNull(tag.publishedAt, "the tags API reports no publish time; the commit date is not one");
        assertTrue(tag.assets.isEmpty());
        assertEquals(GitHubVersionSource.releasePageUrl(REPO, "v0.1.0"), tag.url);
        assertFalse(tag.prerelease);
    }

    @Test
    void aPreReleaseTagIsMarkedAsSuch() {
        List<ReleaseInfo> tags = GitHubVersionSource.parseTags("[{\"name\": \"v0.4.0-rc1\"}]", REPO);

        assertEquals(1, tags.size());
        assertTrue(tags.get(0).prerelease,
                "a tag carries no prerelease flag, so the version suffix is the only signal");
    }

    @Test
    void skipsUnusableTagNamesInsteadOfFailing() {
        String json = """
                [
                  {"name": "v0.3.0"},
                  {"name": "nightly"},
                  {"name": "latest"},
                  {"name": ""},
                  {"name": null},
                  {"commit": {"sha": "ddd"}},
                  "a string where an object belongs",
                  {"name": "v0.2.0"}
                ]
                """;

        List<ReleaseInfo> tags = GitHubVersionSource.parseTags(json, REPO);

        assertEquals(List.of("0.3.0", "0.2.0"), tags.stream().map(tag -> tag.version).toList(),
                "only version-shaped names survive; one odd tag must not hide the rest");
    }

    @Test
    void acceptsATagNameInItsRefForm() {
        List<ReleaseInfo> tags = GitHubVersionSource.parseTags(
                "[{\"ref\": \"refs/tags/v1.2.3\"}]", REPO);

        assertEquals(1, tags.size());
        assertEquals("1.2.3", tags.get(0).version);
        assertEquals("v1.2.3", tags.get(0).tag, "the ref prefix is not part of the tag");
    }

    @Test
    void emptyTagsArrayIsAnEmptyList() {
        assertEquals(0, GitHubVersionSource.parseTags("[]", REPO).size());
    }

    @Test
    void aNonArrayTagsResponseIsRejected() {
        UpdateException object = assertThrows(UpdateException.class,
                () -> GitHubVersionSource.parseTags("{\"message\":\"Not Found\"}", REPO));
        assertTrue(object.getMessage().contains("数组"), object.getMessage());

        assertThrows(UpdateException.class, () -> GitHubVersionSource.parseTags("not json", REPO));
        assertThrows(UpdateException.class, () -> GitHubVersionSource.parseTags("", REPO));
        assertThrows(UpdateException.class, () -> GitHubVersionSource.parseTags(null, REPO));
    }

    // ---------------------------------------------------------------- merge

    @Test
    void theReleaseWinsWhenBothSidesHaveTheVersion() {
        List<ReleaseInfo> releases = GitHubVersionSource.parseReleases(RELEASES_JSON, REPO);
        List<ReleaseInfo> tags = GitHubVersionSource.parseTags(TAGS_JSON, REPO);

        List<ReleaseInfo> merged = GitHubVersionSource.merge(releases, tags);

        assertEquals(List.of("0.3.0", "0.2.0", "0.1.0"),
                merged.stream().map(entry -> entry.version).toList());
        ReleaseInfo newest = merged.get(0);
        assertFalse(newest.fromTag, "0.3.0 has a release, so it is not a bare tag");
        assertTrue(newest.notes.contains("自动更新"), "the notes only exist on the release side");
        assertEquals(1, newest.assets.size(), "the assets only exist on the release side");
        assertEquals("2026-10-26T03:23:56Z", newest.publishedAt);
        assertEquals("code-association 0.3.0", newest.name);
    }

    @Test
    void versionsThatOnlyExistAsTagsAreKept() {
        List<ReleaseInfo> merged = GitHubVersionSource.merge(
                GitHubVersionSource.parseReleases(RELEASES_JSON, REPO),
                GitHubVersionSource.parseTags(TAGS_JSON, REPO));

        ReleaseInfo onlyTag = merged.get(2);
        assertEquals("0.1.0", onlyTag.version);
        assertTrue(onlyTag.fromTag);
        assertEquals("", onlyTag.notes);
        assertTrue(onlyTag.assets.isEmpty());
    }

    @Test
    void theMergeIsSortedAcrossBothSources() {
        List<ReleaseInfo> releases = GitHubVersionSource.parseReleases(
                "[{\"tag_name\": \"v0.2.0\"}]", REPO);
        List<ReleaseInfo> tags = GitHubVersionSource.parseTags(
                "[{\"name\": \"v0.10.0\"}, {\"name\": \"v0.9.0\"}, {\"name\": \"v0.2.0\"}]",
                REPO);

        List<ReleaseInfo> merged = GitHubVersionSource.merge(releases, tags);

        assertEquals(List.of("0.10.0", "0.9.0", "0.2.0"),
                merged.stream().map(entry -> entry.version).toList(),
                "0.10.0 belongs above 0.9.0 even though it came from the tags side");
    }

    @Test
    void draftsAreNeverOfferedAsAnUpdate() {
        String json = """
                [
                  {"tag_name": "v0.9.0", "draft": true, "name": "unfinished"},
                  {"tag_name": "v0.8.0", "draft": false},
                  {"tag_name": "v0.7.0"}
                ]
                """;

        List<ReleaseInfo> releases = GitHubVersionSource.parseReleases(json, REPO);

        assertEquals(List.of("0.8.0", "0.7.0"),
                releases.stream().map(release -> release.version).toList(),
                "a draft is unpublished; the list endpoint normally hides it, a token may not");
    }

    // ------------------------------------------------------------ fetch()

    @Test
    void fetchMergesReleasesAndTagsWithTheSameHeaders() throws Exception {
        try (FakeGitHub github = FakeGitHub.start(200, RELEASES_JSON, 200, TAGS_JSON)) {
            GitHubVersionSource source = source(github, true);

            List<ReleaseInfo> releases = source.fetch();

            assertEquals(List.of("0.3.0", "0.2.0", "0.1.0"),
                    releases.stream().map(release -> release.version).toList());
            assertFalse(releases.get(0).fromTag);
            assertTrue(releases.get(2).fromTag);
            assertEquals(List.of(), source.warnings(), "a successful fetch has nothing to warn about");

            Map<String, List<Map<String, String>>> seen = github.requests();
            assertEquals(1, seen.get("releases").size());
            assertEquals(1, seen.get("tags").size());
            assertEquals(seen.get("releases").get(0), seen.get("tags").get(0),
                    "both requests must carry the same User-Agent, Accept and token headers");
            assertEquals("Bearer test-token", seen.get("tags").get(0).get("authorization"));
        }
    }

    @Test
    void aFailingTagsRequestOnlyCostsAWarning() throws Exception {
        try (FakeGitHub github = FakeGitHub.start(200, RELEASES_JSON, 500, "{\"message\":\"boom\"}")) {
            GitHubVersionSource source = source(github, true);

            List<ReleaseInfo> releases = source.fetch();

            assertEquals(2, releases.size(), "the releases that were already fetched must survive");
            assertEquals("0.3.0", releases.get(0).version);
            assertEquals(1, source.warnings().size(), "exactly one line, not one per missing version");
            String warning = source.warnings().get(0);
            assertTrue(warning.contains("标签"), warning);
            assertTrue(warning.contains("500"), warning);
        }
    }

    @Test
    void aMalformedTagsPayloadIsAlsoOnlyAWarning() throws Exception {
        try (FakeGitHub github = FakeGitHub.start(200, RELEASES_JSON, 200, "not json at all")) {
            GitHubVersionSource source = source(github, true);

            assertEquals(2, source.fetch().size());
            assertEquals(1, source.warnings().size());
        }
    }

    @Test
    void disablingTagsSkipsTheRequestAndTheWarning() throws Exception {
        try (FakeGitHub github = FakeGitHub.start(200, RELEASES_JSON, 500, "{\"message\":\"boom\"}")) {
            GitHubVersionSource source = source(github, false);

            List<ReleaseInfo> releases = source.fetch();

            assertEquals(2, releases.size());
            assertEquals(List.of(), source.warnings(),
                    "tags were never requested, so nothing can be reported as missing");
            assertNull(github.requests().get("tags"), "the tags endpoint must not be called at all");
        }
    }

    @Test
    void aFailingReleasesRequestStillFails() throws Exception {
        try (FakeGitHub github = FakeGitHub.start(404, "{\"message\":\"Not Found\"}", 200, TAGS_JSON)) {
            GitHubVersionSource source = source(github, true);

            UpdateException error = assertThrows(UpdateException.class, source::fetch);
            assertTrue(error.getMessage().contains("仓库不存在"), error.getMessage());
            assertNull(github.requests().get("tags"),
                    "there is no point asking for tags when the repository itself is unreadable");
        }
    }

    @Test
    void anEmptyTagsListLeavesTheReleasesUntouched() throws Exception {
        try (FakeGitHub github = FakeGitHub.start(200, RELEASES_JSON, 200, "[]")) {
            GitHubVersionSource source = source(github, true);

            assertEquals(2, source.fetch().size());
            assertEquals(List.of(), source.warnings(), "an empty list is an answer, not a failure");
        }
    }

    // ---------------------------------------------------------------- setup

    private static GitHubVersionSource source(FakeGitHub github, boolean includeTags) {
        AppProperties properties = new AppProperties();
        properties.getUpdate().setRepo("o/r");
        properties.getUpdate().setToken("test-token");
        properties.getUpdate().setIncludeTags(includeTags);
        properties.getUpdate().setMaxReleases(20);
        return new GitHubVersionSource(properties, HttpClient.newHttpClient(), github.baseUrl());
    }

    /**
     * A loopback stand-in for the two GitHub endpoints.
     *
     * Each endpoint has its own status and body so "releases fine, tags broken" is a one-liner,
     * and every request is recorded with its headers: the two calls share one HttpClient and one
     * token, which is only observable by looking at what actually arrived.
     */
    private static final class FakeGitHub implements AutoCloseable {

        private final HttpServer server;
        private final Map<String, List<Map<String, String>>> requests = new ConcurrentHashMap<>();

        private FakeGitHub(HttpServer server) {
            this.server = server;
        }

        static FakeGitHub start(int releasesStatus, String releasesBody,
                                int tagsStatus, String tagsBody) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeGitHub github = new FakeGitHub(server);
            github.handle("/repos/o/r/releases", releasesStatus, releasesBody);
            github.handle("/repos/o/r/tags", tagsStatus, tagsBody);
            server.start();
            return github;
        }

        private void handle(String path, int status, String body) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            server.createContext(path, exchange -> {
                requests.computeIfAbsent(name, key -> new ArrayList<>()).add(headersOf(exchange));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
        }

        private static Map<String, String> headersOf(HttpExchange exchange) {
            Map<String, String> headers = new ConcurrentHashMap<>();
            for (String name : List.of("User-Agent", "Accept", "Authorization")) {
                String value = exchange.getRequestHeaders().getFirst(name);
                if (value != null) {
                    headers.put(name.toLowerCase(java.util.Locale.ROOT), value);
                }
            }
            return headers;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        Map<String, List<Map<String, String>>> requests() {
            return requests;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
