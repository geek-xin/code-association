package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.update.GitHubVersionSource;
import com.geek.association.update.RateLimitGate;
import com.geek.association.update.ReleaseInfo;
import com.geek.association.update.UpdateException;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the release check behaves when GitHub is not simply reachable.
 *
 * A network problem is the normal case for this feature, not an exception: the API host is blocked
 * in some networks, the anonymous quota runs out on a busy day, and a flaky link drops a request
 * mid-flight. Each of those has a different correct answer - fall back, stop, retry - and getting
 * them wrong turns a working installation into one that either hammers a dead endpoint or reports
 * "no update" while an update exists.
 *
 * Everything here runs against loopback servers, so the fallback order and the retry count are
 * observed rather than assumed.
 */
class UpdateNetworkResilienceTest {

    private static final String RELEASES_JSON = """
            [
              {"tag_name": "v0.9.0", "name": "0.9.0", "published_at": "2026-10-01T00:00:00Z",
               "prerelease": false, "body": "notes",
               "assets": [{"name": "code-association-0.9.0.jar", "size": 1024,
                           "browser_download_url": "https://example.test/code-association-0.9.0.jar"}]}
            ]
            """;

    private static AppProperties props(List<String> bases, int retries) {
        AppProperties properties = new AppProperties();
        properties.getUpdate().setRepo("o/r");
        properties.getUpdate().setIncludeTags(false);
        properties.getUpdate().setMaxReleases(20);
        properties.getUpdate().setApiBaseUrls(bases);
        properties.getUpdate().setRetries(retries);
        properties.getUpdate().setRetryBaseDelayMs(1);
        properties.getUpdate().setRespectRateLimit(true);
        return properties;
    }

    @Test
    void aBlockedPrimaryFallsBackToTheMirror() throws Exception {
        try (FakeGitHub mirror = FakeGitHub.start(200, RELEASES_JSON)) {
            // The primary host does not exist at all: a DNS-level failure is what a blocked host
            // looks like to java.net.http, and it must hand over rather than end the check.
            AppProperties properties = props(List.of("http://127.0.0.1:1", mirror.baseUrl()), 0);
            GitHubVersionSource source = new GitHubVersionSource(
                    properties, HttpClient.newHttpClient(), properties.getUpdate().getApiBaseUrls(),
                    new RateLimitGate());

            List<ReleaseInfo> releases = source.fetch();

            assertEquals(1, releases.size());
            assertEquals(mirror.baseUrl(), source.activeApiBase());
            assertNull(source.lastFailureHost(), "the last attempt succeeded, so no host failed");
            assertFalse(source.warnings().isEmpty(), "the fallback must stay visible to the user");
            assertTrue(source.warnings().get(0).contains("已改用"), source.warnings().get(0));
        }
    }

    @Test
    void aMissingRepositoryIsNotRetriedOnAnotherHost() throws Exception {
        try (FakeGitHub primary = FakeGitHub.start(404, "{\"message\":\"Not Found\"}");
             FakeGitHub mirror = FakeGitHub.start(200, RELEASES_JSON)) {
            AppProperties properties = props(List.of(primary.baseUrl(), mirror.baseUrl()), 2);
            GitHubVersionSource source = new GitHubVersionSource(
                    properties, HttpClient.newHttpClient(), properties.getUpdate().getApiBaseUrls(),
                    new RateLimitGate());

            UpdateException error = assertThrows(UpdateException.class, source::fetch);

            assertTrue(error.getMessage().contains("仓库不存在"), error.getMessage());
            assertEquals(0, mirror.hits.get(), "a mirror cannot make a missing repository appear");
        }
    }

    @Test
    void aServerErrorIsRetriedBeforeTheHostIsGivenUp() throws Exception {
        try (FakeGitHub github = FakeGitHub.startSequence(500, 500, 200)) {
            AppProperties properties = props(List.of(github.baseUrl()), 3);
            GitHubVersionSource source = new GitHubVersionSource(
                    properties, HttpClient.newHttpClient(), properties.getUpdate().getApiBaseUrls(),
                    new RateLimitGate());

            assertEquals(1, source.fetch().size());
            assertEquals(3, github.hits.get(), "two failures and one success");
        }
    }

    @Test
    void aRejectedTokenIsNotRetried() throws Exception {
        try (FakeGitHub github = FakeGitHub.start(401, "{\"message\":\"Bad credentials\"}")) {
            AppProperties properties = props(List.of(github.baseUrl()), 3);
            GitHubVersionSource source = new GitHubVersionSource(
                    properties, HttpClient.newHttpClient(), properties.getUpdate().getApiBaseUrls(),
                    new RateLimitGate());

            UpdateException error = assertThrows(UpdateException.class, source::fetch);

            assertTrue(error.getMessage().contains("token 无效"), error.getMessage());
            assertEquals(1, github.hits.get(), "retrying a rejected token only spends quota");
        }
    }

    @Test
    void anExhaustedQuotaStopsTheRetryLoop() throws Exception {
        try (FakeGitHub github = FakeGitHub.startRateLimited()) {
            AppProperties properties = props(List.of(github.baseUrl()), 3);
            RateLimitGate gate = new RateLimitGate();
            GitHubVersionSource source = new GitHubVersionSource(
                    properties, HttpClient.newHttpClient(), properties.getUpdate().getApiBaseUrls(), gate);

            UpdateException error = assertThrows(UpdateException.class, source::fetch);

            assertTrue(error.getMessage().contains("限流"), error.getMessage());
            assertEquals(1, github.hits.get(),
                    "once the reset time is known, another attempt only pushes it further out");
            assertNotNull(gate.blockedUntil(), "the deadline is shared with the download path");
        }
    }

    @Test
    void anExhaustedQuotaStillLetsTheMirrorBeAsked() throws Exception {
        try (FakeGitHub primary = FakeGitHub.startRateLimited();
             FakeGitHub mirror = FakeGitHub.start(200, RELEASES_JSON)) {
            AppProperties properties = props(List.of(primary.baseUrl(), mirror.baseUrl()), 2);
            RateLimitGate gate = new RateLimitGate();
            GitHubVersionSource source = new GitHubVersionSource(
                    properties, HttpClient.newHttpClient(), properties.getUpdate().getApiBaseUrls(), gate);

            List<ReleaseInfo> releases = source.fetch();

            assertEquals(1, releases.size());
            assertEquals(1, mirror.hits.get(),
                    "a mirror may answer from another IP with its own quota, so it must be asked");
            assertEquals(mirror.baseUrl(), source.activeApiBase());
        }
    }

    @Test
    void everyHostFailingLeavesATrailInsteadOfASilentEmptyList() throws Exception {
        try (FakeGitHub one = FakeGitHub.start(500, "boom");
             FakeGitHub two = FakeGitHub.start(500, "boom")) {
            AppProperties properties = props(List.of(one.baseUrl(), two.baseUrl()), 0);
            GitHubVersionSource source = new GitHubVersionSource(
                    properties, HttpClient.newHttpClient(), properties.getUpdate().getApiBaseUrls(),
                    new RateLimitGate());

            assertThrows(UpdateException.class, source::fetch);

            assertEquals(2, source.warnings().size(), "one line per host that was tried");
            assertNotNull(source.lastFailureHost());
        }
    }

    @Test
    void duplicateAndBlankHostsAreCollapsed() {
        AppProperties properties = props(List.of("", "  ", "https://api.github.com", "https://api.github.com/"), 0);
        GitHubVersionSource source = new GitHubVersionSource(
                properties, HttpClient.newHttpClient(), properties.getUpdate().getApiBaseUrls(),
                new RateLimitGate());

        assertEquals(List.of("https://api.github.com"), source.apiBases(),
                "a repeated host would cost a second round trip for the same answer");
    }

    // ---------------------------------------------------------------- fixture

    /** A loopback stand-in for the releases endpoint, counting what actually arrived. */
    private static final class FakeGitHub implements AutoCloseable {

        private final HttpServer server;
        private final AtomicInteger hits = new AtomicInteger();
        private final List<Integer> scripted = new ArrayList<>();
        private final Map<String, String> headers = new ConcurrentHashMap<>();

        private FakeGitHub(HttpServer server) {
            this.server = server;
        }

        static FakeGitHub start(int status, String body) throws IOException {
            FakeGitHub github = new FakeGitHub(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
            github.handle(exchange -> {
                github.record(exchange);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            return github;
        }

        /** Answers with each status in turn, so a retry can be observed as more than one request. */
        static FakeGitHub startSequence(int... statuses) throws IOException {
            FakeGitHub github = new FakeGitHub(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
            for (int status : statuses) {
                github.scripted.add(status);
            }
            github.handle(exchange -> {
                int index = github.record(exchange) - 1;
                int status = index < github.scripted.size()
                        ? github.scripted.get(index) : github.scripted.get(github.scripted.size() - 1);
                String body = status == 200 ? RELEASES_JSON : "{\"message\":\"boom\"}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            return github;
        }

        /** A 403 with the quota headers GitHub sends once the anonymous allowance is gone. */
        static FakeGitHub startRateLimited() throws IOException {
            FakeGitHub github = new FakeGitHub(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
            github.handle(exchange -> {
                github.record(exchange);
                byte[] bytes = "{\"message\":\"API rate limit exceeded\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("x-ratelimit-remaining", "0");
                exchange.getResponseHeaders().add("x-ratelimit-reset",
                        Long.toString(java.time.Instant.now().plusSeconds(600).getEpochSecond()));
                exchange.sendResponseHeaders(403, bytes.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            return github;
        }

        private void handle(com.sun.net.httpserver.HttpHandler handler) {
            server.createContext("/repos/o/r/releases", handler);
            server.start();
        }

        private int record(com.sun.net.httpserver.HttpExchange exchange) {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null) {
                headers.put("authorization", authorization);
            }
            return hits.incrementAndGet();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
