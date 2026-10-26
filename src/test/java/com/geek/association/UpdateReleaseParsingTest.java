package com.geek.association;

import com.geek.association.update.GitHubVersionSource;
import com.geek.association.update.ReleaseInfo;
import com.geek.association.update.UpdateException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing of the GitHub releases payload, driven by inline JSON.
 *
 * The network is deliberately absent: a test that called GitHub would fail on a plane, leak the
 * rate limit, and be unable to reproduce the malformed responses that matter most here.
 */
class UpdateReleaseParsingTest {

    private static final String REPO = "geek-xin/code-association";

    @Test
    void parsesANormalPayloadNewestFirst() {
        String json = """
                [
                  {
                    "tag_name": "v0.3.0",
                    "name": "code-association 0.3.0",
                    "published_at": "2026-10-26T03:23:56Z",
                    "prerelease": false,
                    "body": "## 变更\\n- 新增自动更新",
                    "html_url": "https://github.com/geek-xin/code-association/releases/tag/v0.3.0",
                    "assets": [
                      {"name": "code-association-0.3.0.zip", "size": 41655748,
                       "browser_download_url": "https://example.test/code-association-0.3.0.zip"},
                      {"name": "code-association.jar", "size": 40860414,
                       "browser_download_url": "https://example.test/code-association.jar"}
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

        List<ReleaseInfo> releases = GitHubVersionSource.parseReleases(json, REPO);

        assertEquals(2, releases.size());
        ReleaseInfo newest = releases.get(0);
        assertEquals("0.3.0", newest.version, "the leading v must be normalised away");
        assertEquals("v0.3.0", newest.tag, "the raw tag is kept for the download lookup");
        assertEquals("code-association 0.3.0", newest.name);
        assertEquals("2026-10-26T03:23:56Z", newest.publishedAt);
        assertFalse(newest.prerelease);
        assertTrue(newest.notes.contains("自动更新"));
        assertEquals("https://github.com/geek-xin/code-association/releases/tag/v0.3.0", newest.url);
        assertEquals(2, newest.assets.size());
        assertEquals("code-association.jar", newest.assets.get(1).name);
        assertEquals(40860414L, newest.assets.get(1).size);
        assertEquals("https://example.test/code-association.jar", newest.assets.get(1).downloadUrl);
        assertTrue(newest.assets.get(1).isJar());
        assertFalse(newest.assets.get(0).isJar());

        ReleaseInfo older = releases.get(1);
        assertEquals("0.2.0", older.version);
        assertTrue(older.assets.isEmpty(), "a release without assets is not an error");
        assertEquals("", older.notes);
        assertEquals("https://github.com/geek-xin/code-association/releases/tag/v0.2.0", older.url,
                "a missing html_url falls back to the release page");
    }

    @Test
    void sortsByVersionNotByResponseOrder() {
        String json = """
                [
                  {"tag_name": "v0.2.0", "assets": []},
                  {"tag_name": "v0.10.0", "assets": []},
                  {"tag_name": "v0.9.0", "assets": []}
                ]
                """;

        List<ReleaseInfo> releases = GitHubVersionSource.parseReleases(json, REPO);

        assertEquals(List.of("0.10.0", "0.9.0", "0.2.0"),
                releases.stream().map(r -> r.version).toList());
    }

    @Test
    void skipsEntriesWithMissingFieldsInsteadOfFailing() {
        String json = """
                [
                  {"tag_name": "v0.4.0"},
                  {"tag_name": "v0.3.0", "name": null, "published_at": null, "assets": null},
                  {"name": "no tag at all", "assets": []},
                  {"tag_name": "not-a-version", "assets": []},
                  "a string where an object belongs"
                ]
                """;

        List<ReleaseInfo> releases = GitHubVersionSource.parseReleases(json, REPO);

        assertEquals(2, releases.size(), "only the entries with a usable tag survive");
        assertEquals("0.4.0", releases.get(0).version);
        assertEquals("v0.4.0", releases.get(0).name, "an untitled release falls back to its tag");
        assertEquals("", releases.get(0).notes);
        assertTrue(releases.get(0).assets.isEmpty());
        assertEquals(null, releases.get(0).publishedAt);
        assertEquals("0.3.0", releases.get(1).version);
    }

    @Test
    void emptyArrayIsAnEmptyList() {
        assertEquals(0, GitHubVersionSource.parseReleases("[]", REPO).size());
    }

    @Test
    void aNonArrayResponseIsRejected() {
        UpdateException object = assertThrows(UpdateException.class,
                () -> GitHubVersionSource.parseReleases("{\"message\":\"Not Found\"}", REPO));
        assertTrue(object.getMessage().contains("数组"), object.getMessage());

        assertThrows(UpdateException.class, () -> GitHubVersionSource.parseReleases("not json", REPO));
        assertThrows(UpdateException.class, () -> GitHubVersionSource.parseReleases("", REPO));
        assertThrows(UpdateException.class, () -> GitHubVersionSource.parseReleases(null, REPO));
    }

    @Test
    void releasePageUrlIsDerivedFromTheTag() {
        assertEquals("https://github.com/geek-xin/code-association/releases/tag/v0.3.0",
                GitHubVersionSource.releasePageUrl(REPO, "v0.3.0"));
    }

    @Test
    void everyParsedEntryKeepsTheFieldsTheUiNeeds() {
        String json = """
                [{"tag_name": "v1.0.0", "prerelease": true, "assets": [
                   {"name": "a.jar", "size": 1, "url": "https://api.test/a.jar"}]}]
                """;

        ReleaseInfo release = GitHubVersionSource.parseReleases(json, REPO).get(0);

        assertTrue(release.prerelease);
        assertNotNull(release.assets.get(0).downloadUrl,
                "the API url is the fallback when browser_download_url is absent");
    }
}
