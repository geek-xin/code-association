package com.geek.association.update;

import com.geek.association.config.AppProperties;
import com.geek.association.util.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * The network policy shared by the two places that talk to GitHub: the release check and the jar
 * download.
 *
 * Without a shared layer each call site grows its own client, its own timeout and its own idea of
 * what a 403 means, so "GitHub is unreachable" ends up reported three different ways and fixed in
 * one place at a time. What actually goes wrong on a real installation is always one of four things
 * - the API host is blocked, the release CDN is blocked, a proxy is required, or the anonymous quota
 * ran out - so the answers live here, once, as pure functions. No request is made from this class
 * and every rule below is testable without a network.
 *
 * The split is deliberate: {@link #client} decides <i>how</i> to connect, {@link #mirrorDownloadUrl}
 * decides <i>where</i> to download from, and the rest decide <i>whether to try again</i> and
 * <i>what to tell the user</i>. Retry loops stay in the callers, because only they know which
 * requests are idempotent and which failures are worth another 40 MB.
 */
public final class GitHubNetwork {

    private static final Logger log = LoggerFactory.getLogger(GitHubNetwork.class);

    /**
     * Fail fast: every request here is either a user action or a poll behind a UI card, and a client
     * that hangs for a minute on a black-holed host looks exactly like a frozen page.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Ceiling of the exponential backoff.
     *
     * The retries exist to ride out a hiccup, not to keep a background poll alive: past half a
     * minute the honest answer is "this is down, report it", and unbounded doubling would park a
     * download thread for hours on a flaky network.
     */
    public static final long MAX_BACKOFF_MS = 30_000L;

    /**
     * Port assumed when the proxy setting omits one.
     *
     * 8080 rather than 80: a bare {@code proxy.corp} in the settings file is a guess either way, and
     * 8080 is where a corporate HTTP proxy is listening far more often than not.
     */
    static final int DEFAULT_PROXY_PORT = 8080;

    /** GitHub's quota header, in epoch seconds; the only place the reset time is published. */
    private static final String RATE_LIMIT_RESET_HEADER = "x-ratelimit-reset";

    private GitHubNetwork() {
    }

    // ------------------------------------------------------------------ client

    /**
     * Builds the HTTP client used for update traffic, with the configured proxy applied.
     *
     * A blank proxy setting deliberately leaves the builder's own selector alone: that is what keeps
     * {@code -Dhttps.proxyHost} and {@code HTTPS_PROXY} working, and installing a do-nothing selector
     * instead would silently disable a proxy the operator configured at the JVM level. A malformed
     * value is logged and ignored for the same reason - one typo in an optional setting must not be
     * able to break the update feature outright.
     */
    public static HttpClient client(AppProperties.Update update) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // GitHub answers release downloads with a 302 to its CDN; without NORMAL every
                // download would stop at the redirect and be reported as "HTTP 302".
                .followRedirects(HttpClient.Redirect.NORMAL);
        ProxySetting proxy = ProxySetting.parse(update == null ? null : update.getProxy());
        if (proxy != null) {
            builder.proxy(proxy.selector());
            if (proxy.hasCredentials()) {
                builder.authenticator(proxy.authenticator());
            }
        }
        return builder.build();
    }

    /** The proxy selector the setting describes, or null when it describes nothing usable. */
    static ProxySelector proxySelector(String proxy) {
        ProxySetting parsed = ProxySetting.parse(proxy);
        return parsed == null ? null : parsed.selector();
    }

    // ------------------------------------------------------------------ mirror

    /**
     * Routes a release-asset URL through a download mirror.
     *
     * The convention every ghproxy-style mirror shares is "prefix the absolute URL", so the result
     * is {@code <mirror>/<original url>} with the {@code https://} of the original left intact: the
     * mirror needs it to know which host to fetch from. Blank inputs are returned unchanged rather
     * than turned into a URL that can only fail later - "no mirror configured" is the normal case,
     * not an error.
     *
     * The argument must be the original asset URL. Prefixing an already-prefixed URL would point one
     * mirror at another, so a retry has to apply this to the same source every time.
     */
    public static String mirrorDownloadUrl(String downloadUrl, String mirror) {
        if (Texts.isBlank(downloadUrl) || Texts.isBlank(mirror)) {
            return downloadUrl;
        }
        return stripTrailingSlashes(Texts.trim(mirror)) + "/" + Texts.trim(downloadUrl);
    }

    /** Drops trailing slashes without eating the "//" of the scheme itself. */
    private static String stripTrailingSlashes(String text) {
        int scheme = text.indexOf("://");
        int floor = scheme < 0 ? 1 : scheme + 3;
        int end = text.length();
        while (end > floor && text.charAt(end - 1) == '/') {
            end--;
        }
        return text.substring(0, end);
    }

    // ----------------------------------------------------------------- backoff

    /**
     * How long to wait before retry number {@code attempt}, where 1 is the first retry.
     *
     * Doubling from {@code base} and clamping at {@link #MAX_BACKOFF_MS} is what turns a few quick
     * attempts into "survive a five second blip" while still giving up in seconds rather than
     * minutes. The exponent is clamped too: {@code base << 40} overflows into a negative number, and
     * a negative delay would mean a retry loop with no pause at all - the exact opposite of the
     * intent, and the kind of bug that only appears on the one installation that retried a lot.
     */
    public static long backoffMillis(int attempt, long base) {
        long floor = Math.max(1L, base);
        int exponent = Math.max(0, attempt - 1);
        if (exponent > 20) {
            return MAX_BACKOFF_MS;
        }
        long delay = floor << exponent;
        if (delay <= 0 || delay > MAX_BACKOFF_MS) {
            return MAX_BACKOFF_MS;
        }
        return delay;
    }

    /**
     * Whether an HTTP status is worth another attempt.
     *
     * 5xx, 408 and 429 are the server saying "not now"; every other 4xx is the server saying "no",
     * and retrying a 401 or a 404 only burns quota. The distinction matters most on a rate-limited
     * IP: three blind retries against a 403 are three requests further from getting the quota back.
     */
    public static boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || (status >= 500 && status <= 599);
    }

    /**
     * Turns a network exception into one line the UI can show.
     *
     * The messages are written for whoever runs the installation, not for a stack trace: "Connection
     * refused" and "PKIX path building failed" both mean "your network or your proxy is in the way",
     * and saying so is the difference between a support ticket and a settings change. Anything
     * unrecognised falls through to its own message, so no failure is ever swallowed.
     */
    public static String describe(IOException e) {
        if (e == null) {
            return "未知网络错误";
        }
        String message = e.getMessage();
        String detail = message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
        if (e instanceof UnknownHostException) {
            return "无法解析域名（" + detail + "），请检查网络或 DNS";
        }
        if (e instanceof ConnectException) {
            return "无法建立连接（" + detail + "），可能是网络不通或代理拦截";
        }
        if (e instanceof SSLException) {
            return "TLS 握手失败（" + detail + "），可能是代理或镜像的证书不受信任";
        }
        return detail;
    }

    // --------------------------------------------------------------- rate limit

    /**
     * The instant GitHub says the quota comes back, or null when the response does not say.
     *
     * It is read from the header rather than the body because both endpoints publish it the same way
     * while a 403 body has a different shape from a 200 one. An unparsable value is null instead of
     * an exception: a missing reset time only costs the exact "until" wording in the UI, and a header
     * that cannot be read must never turn into a failed update check.
     */
    public static Instant rateLimitReset(HttpResponse<?> response) {
        if (response == null || response.headers() == null) {
            return null;
        }
        String raw = response.headers().firstValue(RATE_LIMIT_RESET_HEADER).orElse(null);
        if (Texts.isBlank(raw)) {
            return null;
        }
        try {
            long epochSecond = Long.parseLong(raw.strip());
            // A zero or negative value is a placeholder some proxies send; reading it as a time in
            // 1970 would unblock immediately anyway, so "unknown" is the more honest answer.
            return epochSecond > 0 ? Instant.ofEpochSecond(epochSecond) : null;
        } catch (NumberFormatException | ArithmeticException e) {
            log.debug("unreadable {} header: {}", RATE_LIMIT_RESET_HEADER, raw);
            return null;
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * A parsed proxy setting: {@code host:port}, optionally with an {@code http://} scheme and
     * credentials in front of the host.
     *
     * It is a value so the credentials are parsed once and handed only to the client that needs
     * them; re-splitting the raw string at each call site is how the two call sites would drift.
     */
    record ProxySetting(String host, int port, String user, String secret) {

        /** Returns null for a blank or unreadable value; the caller then keeps the JVM default. */
        static ProxySetting parse(String text) {
            String raw = Texts.trim(text);
            if (raw.isEmpty()) {
                return null;
            }
            String rest = raw;
            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://")) {
                rest = raw.substring("http://".length());
            } else if (lower.startsWith("https://")) {
                rest = raw.substring("https://".length());
            }
            String user = null;
            String secret = null;
            // lastIndexOf, not indexOf: a secret may legally contain '@', a host may not.
            int at = rest.lastIndexOf('@');
            if (at >= 0) {
                String userInfo = rest.substring(0, at);
                rest = rest.substring(at + 1);
                int colon = userInfo.indexOf(':');
                user = colon < 0 ? userInfo : userInfo.substring(0, colon);
                secret = colon < 0 ? "" : userInfo.substring(colon + 1);
            }
            String host;
            String portText;
            if (rest.startsWith("[")) {
                // An IPv6 literal carries its own colons, so the port can only follow the ']'.
                int close = rest.indexOf(']');
                if (close < 0) {
                    log.warn("ignoring an unreadable proxy setting (unclosed IPv6 literal): {}", raw);
                    return null;
                }
                host = rest.substring(0, close + 1);
                String tail = rest.substring(close + 1);
                portText = tail.startsWith(":") ? tail.substring(1) : "";
            } else {
                int colon = rest.lastIndexOf(':');
                host = colon > 0 ? rest.substring(0, colon) : rest;
                portText = colon > 0 ? rest.substring(colon + 1) : "";
            }
            int port = DEFAULT_PROXY_PORT;
            if (!portText.isBlank()) {
                try {
                    port = Integer.parseInt(portText.strip());
                } catch (NumberFormatException e) {
                    log.warn("ignoring a proxy setting with an unreadable port: {}", raw);
                    return null;
                }
            }
            if (host.isBlank() || port <= 0 || port > 65535) {
                log.warn("ignoring an unusable proxy setting: {}", raw);
                return null;
            }
            return new ProxySetting(host, port, user, secret);
        }

        boolean hasCredentials() {
            return user != null && !user.isBlank();
        }

        InetSocketAddress address() {
            // Unresolved on purpose: resolving here would turn a DNS failure into a config failure,
            // while an unresolved address lets the connection attempt report it at request time.
            return InetSocketAddress.createUnresolved(host, port);
        }

        ProxySelector selector() {
            return ProxySelector.of(address());
        }

        /**
         * Answers the proxy's 407 challenge, and only that one.
         *
         * One authenticator serves proxies and origin servers alike, so the requestor type is
         * checked: handing proxy credentials to an unrelated 401 would leak them to whatever host
         * the URL happens to point at.
         */
        Authenticator authenticator() {
            return new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() != RequestorType.PROXY) {
                        return null;
                    }
                    return new PasswordAuthentication(user, secret == null ? new char[0] : secret.toCharArray());
                }
            };
        }
    }
}
