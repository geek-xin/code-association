package com.geek.association.update;

import com.geek.association.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules that decide where a request goes and whether it is worth repeating.
 *
 * Everything here is a pure function or a client that talks to a loopback server, because the
 * failures these methods describe - a blocked host, a captive portal, an exhausted quota - are
 * exactly the ones a test cannot reproduce against the real GitHub. Pinning them down here is what
 * lets the retry loops in the service stay short enough to read.
 */
class GitHubNetworkTest {

    private static final String ASSET_URL =
            "https://github.com/geek-xin/code-association/releases/download/v0.6.0/code-association-0.6.0.jar";

    // --------------------------------------------------------- mirrorDownloadUrl

    @Test
    void withoutAMirrorTheAssetUrlIsUntouched() {
        assertEquals(ASSET_URL, GitHubNetwork.mirrorDownloadUrl(ASSET_URL, null));
        assertEquals(ASSET_URL, GitHubNetwork.mirrorDownloadUrl(ASSET_URL, ""));
        assertEquals(ASSET_URL, GitHubNetwork.mirrorDownloadUrl(ASSET_URL, "   "));
    }

    @Test
    void aMirrorPrefixesTheWholeUrlIncludingItsScheme() {
        assertEquals("https://ghfast.top/" + ASSET_URL,
                GitHubNetwork.mirrorDownloadUrl(ASSET_URL, "https://ghfast.top"),
                "the mirror needs the original scheme to know which host to fetch from");
    }

    @Test
    void trailingSlashesOnTheMirrorDoNotDoubleUp() {
        assertEquals("https://ghfast.top/" + ASSET_URL,
                GitHubNetwork.mirrorDownloadUrl(ASSET_URL, "https://ghfast.top/"));
        assertEquals("https://ghfast.top/" + ASSET_URL,
                GitHubNetwork.mirrorDownloadUrl(ASSET_URL, "https://ghfast.top///"));
    }

    @Test
    void aMirrorWithoutASchemeIsAcceptedAsWritten() {
        assertEquals("ghproxy.test/" + ASSET_URL,
                GitHubNetwork.mirrorDownloadUrl(ASSET_URL, "ghproxy.test"),
                "a bare host is what people paste; rejecting it would cost a round trip to the docs");
    }

    @Test
    void aMissingUrlStaysMissing() {
        assertNull(GitHubNetwork.mirrorDownloadUrl(null, "https://ghfast.top"));
        assertEquals("", GitHubNetwork.mirrorDownloadUrl("", "https://ghfast.top"));
    }

    // ------------------------------------------------------------- backoffMillis

    @Test
    void theFirstRetryWaitsTheConfiguredBase() {
        assertEquals(500L, GitHubNetwork.backoffMillis(1, 500L));
    }

    @Test
    void eachRetryDoublesTheWait() {
        assertEquals(500L, GitHubNetwork.backoffMillis(1, 500L));
        assertEquals(1_000L, GitHubNetwork.backoffMillis(2, 500L));
        assertEquals(2_000L, GitHubNetwork.backoffMillis(3, 500L));
        assertEquals(4_000L, GitHubNetwork.backoffMillis(4, 500L));
    }

    @Test
    void theWaitIsCappedInsteadOfGrowingWithoutBound() {
        assertEquals(GitHubNetwork.MAX_BACKOFF_MS, GitHubNetwork.backoffMillis(10, 500L));
        assertEquals(GitHubNetwork.MAX_BACKOFF_MS, GitHubNetwork.backoffMillis(1, 60_000L),
                "a base above the ceiling is clamped too, not honoured");
    }

    @Test
    void aHugeAttemptNumberCannotOverflowIntoANegativeDelay() {
        for (int attempt : new int[]{40, 63, 64, 1_000, Integer.MAX_VALUE}) {
            long delay = GitHubNetwork.backoffMillis(attempt, 500L);
            assertTrue(delay > 0, "attempt " + attempt + " produced " + delay
                    + ", and a negative delay is a retry loop with no pause");
            assertEquals(GitHubNetwork.MAX_BACKOFF_MS, delay);
        }
    }

    @Test
    void nonsenseInputsStillProduceAUsableDelay() {
        assertEquals(1L, GitHubNetwork.backoffMillis(1, 0L), "a zero base would mean no pause at all");
        assertEquals(1L, GitHubNetwork.backoffMillis(1, -500L));
        assertEquals(500L, GitHubNetwork.backoffMillis(0, 500L),
                "attempt 0 does not exist, but it must not shorten the first wait");
        assertEquals(500L, GitHubNetwork.backoffMillis(-3, 500L));
    }

    // --------------------------------------------------------- isRetryableStatus

    @Test
    void serverSideFailuresAreWorthAnotherAttempt() {
        assertTrue(GitHubNetwork.isRetryableStatus(500));
        assertTrue(GitHubNetwork.isRetryableStatus(502));
        assertTrue(GitHubNetwork.isRetryableStatus(503));
        assertTrue(GitHubNetwork.isRetryableStatus(504));
        assertTrue(GitHubNetwork.isRetryableStatus(599));
    }

    @Test
    void timeoutAndTooManyRequestsAreWorthAnotherAttempt() {
        assertTrue(GitHubNetwork.isRetryableStatus(408));
        assertTrue(GitHubNetwork.isRetryableStatus(429));
    }

    @Test
    void aRefusalIsFinalAndRetryingItOnlyBurnsQuota() {
        for (int status : new int[]{400, 401, 403, 404, 405, 409, 410, 422}) {
            assertFalse(GitHubNetwork.isRetryableStatus(status), "HTTP " + status + " means 'no', not 'later'");
        }
        assertFalse(GitHubNetwork.isRetryableStatus(200));
        assertFalse(GitHubNetwork.isRetryableStatus(302));
    }

    // ---------------------------------------------------------------- describe

    @Test
    void aDnsFailureIsReportedAsSuch() {
        String text = GitHubNetwork.describe(new UnknownHostException("api.github.com"));

        assertTrue(text.contains("域名"), text);
        assertTrue(text.contains("api.github.com"), "the host is the one detail that makes it actionable");
    }

    @Test
    void aRefusedConnectionPointsAtTheNetworkOrTheProxy() {
        String text = GitHubNetwork.describe(new ConnectException("Connection refused"));

        assertTrue(text.contains("连接"), text);
        assertTrue(text.contains("代理"), "a blocked network and a wrong proxy look identical from here");
    }

    @Test
    void aTlsFailureMentionsTheCertificate() {
        String text = GitHubNetwork.describe(new SSLHandshakeException("PKIX path building failed"));

        assertTrue(text.contains("TLS"), text);
        assertTrue(text.contains("证书"), text);
    }

    @Test
    void anyOtherIoFailureKeepsItsOwnMessage() {
        assertEquals("something odd", GitHubNetwork.describe(new IOException("something odd")));
    }

    @Test
    void anIoFailureWithoutAMessageStillProducesOne() {
        assertEquals("IOException", GitHubNetwork.describe(new IOException()));
        assertEquals("未知网络错误", GitHubNetwork.describe(null));
    }

    // ------------------------------------------------------------ rateLimitReset

    @Test
    void theResetHeaderBecomesAnInstant() {
        long epochSecond = Instant.parse("2026-10-27T08:00:00Z").getEpochSecond();

        Instant reset = GitHubNetwork.rateLimitReset(response(403, "x-ratelimit-reset", String.valueOf(epochSecond)));

        assertEquals(Instant.ofEpochSecond(epochSecond), reset);
    }

    @Test
    void aResponseWithoutTheHeaderHasNoDeadline() {
        assertNull(GitHubNetwork.rateLimitReset(response(200)));
        assertNull(GitHubNetwork.rateLimitReset(response(200, "content-type", "application/json")));
    }

    @Test
    void anUnreadableHeaderIsUnknownRatherThanFatal() {
        assertNull(GitHubNetwork.rateLimitReset(response(403, "x-ratelimit-reset", "soon")));
        assertNull(GitHubNetwork.rateLimitReset(response(403, "x-ratelimit-reset", "")));
        assertNull(GitHubNetwork.rateLimitReset(response(403, "x-ratelimit-reset", "99999999999999999999")));
        assertNull(GitHubNetwork.rateLimitReset(null));
    }

    @Test
    void aPlaceholderZeroIsNotADeadlineInNineteenSeventy() {
        assertNull(GitHubNetwork.rateLimitReset(response(403, "x-ratelimit-reset", "0")));
        assertNull(GitHubNetwork.rateLimitReset(response(403, "x-ratelimit-reset", "-1")));
    }

    // -------------------------------------------------------------------- client

    @Test
    void aClientIsBuiltWhateverTheProxySettingSays() {
        // An unusable setting is ignored, never fatal: the client still exists and still works.
        for (String proxy : new String[]{null, "", "http://127.0.0.1:3128", "not a proxy at all"}) {
            assertNotNull(GitHubNetwork.client(update(proxy)), "proxy setting: " + proxy);
        }
        assertNotNull(GitHubNetwork.client(null), "a null config must not be a NullPointerException");
    }

    @Test
    void aConfiguredProxyBecomesTheSelector() {
        ProxySelector selector = GitHubNetwork.proxySelector("127.0.0.1:3128");

        List<Proxy> proxies = selector.select(URI.create("https://api.github.com/repos/o/r/releases"));

        assertEquals(1, proxies.size());
        assertEquals(Proxy.Type.HTTP, proxies.get(0).type());
        assertProxy(proxies.get(0), "127.0.0.1", 3128);
    }

    @Test
    void aProxyWrittenWithASchemeAndCredentialsIsUnderstood() {
        String credentials = "user" + ":" + "s3cr3t";
        ProxySelector selector = GitHubNetwork.proxySelector("http://" + credentials + "@proxy.corp:8081");

        Proxy proxy = selector.select(URI.create("https://api.github.com/")).get(0);

        assertProxy(proxy, "proxy.corp", 8081);
    }

    @Test
    void aPortlessProxyFallsBackToTheConventionalOne() {
        Proxy proxy = GitHubNetwork.proxySelector("proxy.corp").select(URI.create("https://api.github.com/")).get(0);

        assertProxy(proxy, "proxy.corp", GitHubNetwork.DEFAULT_PROXY_PORT);
    }

    @Test
    void anUnusableProxySettingYieldsNoSelectorAtAll() {
        assertNull(GitHubNetwork.proxySelector(null));
        assertNull(GitHubNetwork.proxySelector(""));
        assertNull(GitHubNetwork.proxySelector("   "));
        assertNull(GitHubNetwork.proxySelector("proxy.corp:not-a-port"));
        assertNull(GitHubNetwork.proxySelector("proxy.corp:70000"));
        assertNull(GitHubNetwork.proxySelector("[::1"));
    }

    @Test
    void anIpv6ProxyKeepsItsColonsAndStillFindsThePort() {
        Proxy proxy = GitHubNetwork.proxySelector("[::1]:3128").select(URI.create("https://api.github.com/")).get(0);

        assertEquals(3128, ((InetSocketAddress) proxy.address()).getPort());
        assertEquals("::1", ((InetSocketAddress) proxy.address()).getHostString().replace("[", "").replace("]", ""),
                "the brackets are address syntax, not part of the host");
    }

    @Test
    void theBuiltClientFollowsRedirectsToTheReleaseCdn() throws Exception {
        try (RedirectingServer server = RedirectingServer.start()) {
            HttpResponse<String> response = GitHubNetwork.client(update(""))
                    .send(HttpRequest.newBuilder(URI.create(server.baseUrl() + "/asset")).GET().build(),
                            HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(), "a 302 to the CDN is a normal step of a download");
            assertEquals("jar-bytes", response.body());
        }
    }

    // -------------------------------------------------------------------- setup

    /**
     * Compares a proxy by host and port only.
     *
     * The address is deliberately unresolved, so equality against a resolved {@link InetSocketAddress}
     * would fail on the very thing the code is trying to avoid - a DNS lookup at configuration time.
     */
    private static void assertProxy(Proxy proxy, String host, int port) {
        InetSocketAddress address = (InetSocketAddress) proxy.address();
        assertEquals(host, address.getHostString());
        assertEquals(port, address.getPort());
        assertTrue(address.isUnresolved(), "resolving at config time would turn a DNS blip into a startup failure");
    }

    private static AppProperties.Update update(String proxy) {
        AppProperties properties = new AppProperties();
        properties.getUpdate().setProxy(proxy);
        return properties.getUpdate();
    }

    /** A response stub: the header rules are the subject here, so no server is needed to carry them. */
    private static HttpResponse<String> response(int status, String... headers) {
        return new StubResponse(status, headers);
    }

    /**
     * Serves one path with a 302 and the next with the body.
     *
     * It exists to prove {@code Redirect.NORMAL} is actually set: without it a release download would
     * stop at the CDN redirect and be reported as a mysterious "HTTP 302".
     */
    private static final class RedirectingServer implements AutoCloseable {

        private final HttpServer server;

        private RedirectingServer(HttpServer server) {
            this.server = server;
        }

        static RedirectingServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/asset", exchange -> {
                exchange.getResponseHeaders().add("Location", "/cdn/asset");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.createContext("/cdn/asset", exchange -> {
                byte[] bytes = "jar-bytes".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
            return new RedirectingServer(server);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** The minimum of {@link HttpResponse} the header helpers touch. */
    private static final class StubResponse implements HttpResponse<String> {

        private final int status;
        private final java.net.http.HttpHeaders headers;

        StubResponse(int status, String... headers) {
            this.status = status;
            java.util.Map<String, List<String>> map = new java.util.LinkedHashMap<>();
            for (int i = 0; i + 1 < headers.length; i += 2) {
                map.put(headers[i], List.of(headers[i + 1]));
            }
            this.headers = java.net.http.HttpHeaders.of(map, (name, value) -> true);
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public java.net.http.HttpHeaders headers() {
            return headers;
        }

        @Override
        public String body() {
            return "";
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("https://api.github.com/repos/o/r/releases");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
