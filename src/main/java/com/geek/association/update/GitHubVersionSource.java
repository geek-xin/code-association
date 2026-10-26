package com.geek.association.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.geek.association.config.AppProperties;
import com.geek.association.util.Json;
import com.geek.association.util.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Reads the releases of this project from the GitHub REST API.
 *
 * Unauthenticated GitHub allows 60 requests per hour per IP; with a token it is 5000. The token is
 * therefore optional and, when present, only ever raises the ceiling.
 *
 * Every failure becomes an {@link UpdateException} whose message is written for the user, because
 * the update card in the UI shows it verbatim. Nothing here decides whether an update is available:
 * that needs the running version and belongs to {@link UpdateService}.
 */
@Component
public class GitHubVersionSource implements VersionSource {

    private static final Logger log = LoggerFactory.getLogger(GitHubVersionSource.class);

    /** Connecting to GitHub is a user-facing action; fail fast instead of hanging the request. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private static final DateTimeFormatter RESET_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private final AppProperties properties;
    private final HttpClient client;

    /**
     * The production constructor. Marked {@code @Autowired} because the class has a second one for
     * tests: with more than one constructor Spring does not guess, it looks for a no-arg one and
     * fails to start the context when there is none.
     */
    @Autowired
    public GitHubVersionSource(AppProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    /** Visible for tests that need to assert the request shape without reaching GitHub. */
    GitHubVersionSource(AppProperties properties, HttpClient client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public List<ReleaseInfo> fetch() {
        AppProperties.Update update = properties.getUpdate();
        String repo = Texts.trim(update.getRepo());
        if (Texts.isBlank(repo)) {
            throw new UpdateException("未配置更新仓库（code-association.update.repo）");
        }
        int perPage = Math.max(1, Math.min(100, update.getMaxReleases()));
        URI uri = URI.create("https://api.github.com/repos/" + repo + "/releases?per_page=" + perPage);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                // GitHub answers 403 without a User-Agent, so this header is mandatory rather
                // than cosmetic.
                .header("User-Agent", "code-association/" + Version.running())
                .GET();
        if (!Texts.isBlank(update.getToken())) {
            builder.header("Authorization", "Bearer " + update.getToken().strip());
        }
        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new UpdateException("无法连接 GitHub（" + CONNECT_TIMEOUT.toSeconds() + " 秒超时）", e);
        } catch (IOException e) {
            throw new UpdateException("无法连接 GitHub：" + describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException("版本检查被中断", e);
        }
        checkStatus(response, repo, update);
        return select(parseReleases(response.body(), repo), update);
    }

    /**
     * Maps the HTTP answer to a user-readable reason.
     *
     * The 403 branch is the one that matters in practice: the anonymous quota is small enough that
     * a busy day exhausts it, and "HTTP 403" alone would look like a permission bug to the user.
     */
    private void checkStatus(HttpResponse<String> response, String repo, AppProperties.Update update) {
        int status = response.statusCode();
        if (status < 300) {
            return;
        }
        if (status == 404) {
            throw new UpdateException("仓库不存在或未公开：" + repo);
        }
        if (status == 401) {
            throw new UpdateException("GitHub token 无效或已过期，请检查 GITHUB_TOKEN");
        }
        if (status == 403) {
            String remaining = header(response, "x-ratelimit-remaining");
            if ("0".equals(remaining)) {
                throw new UpdateException(rateLimitMessage(response, update));
            }
            throw new UpdateException("GitHub 拒绝访问（HTTP 403），可能是 token 权限不足：" + repo);
        }
        if (status == 429) {
            throw new UpdateException("GitHub 请求过于频繁（HTTP 429），请稍后再试");
        }
        throw new UpdateException("GitHub 返回 HTTP " + status + "：" + Texts.truncate(response.body(), 200));
    }

    private String rateLimitMessage(HttpResponse<String> response, AppProperties.Update update) {
        StringBuilder message = new StringBuilder("GitHub API 已限流（每小时 60 次，当前已用尽）");
        if (Texts.isBlank(update.getToken())) {
            message.append("，配置 token 可提高额度至每小时 5000 次");
        }
        String reset = header(response, "x-ratelimit-reset");
        if (!Texts.isBlank(reset)) {
            try {
                Instant at = Instant.ofEpochSecond(Long.parseLong(reset.strip()));
                message.append("；额度将在 ")
                        .append(RESET_FORMAT.format(at.atZone(ZoneId.systemDefault())))
                        .append(" 重置");
            } catch (NumberFormatException e) {
                // A malformed header only costs the exact reset time; the message stays useful.
                log.debug("unreadable X-RateLimit-Reset: {}", reset, e);
            }
        }
        return message.toString();
    }

    private static String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /**
     * Applies the configured visibility rules.
     *
     * Pre-releases are hidden by default: they are built for testing and offering them as "the
     * latest version" would push an unfinished build onto every installation.
     */
    private List<ReleaseInfo> select(List<ReleaseInfo> releases, AppProperties.Update update) {
        List<ReleaseInfo> out = new ArrayList<>();
        for (ReleaseInfo release : releases) {
            if (release.prerelease && !update.isIncludePrerelease()) {
                continue;
            }
            if (out.size() >= Math.max(1, update.getMaxReleases())) {
                break;
            }
            out.add(release);
        }
        return out;
    }

    /**
     * Turns a GitHub releases payload into normalised entries, newest first.
     *
     * Static and public on purpose: the tests feed it inline JSON, which is the only way to cover
     * the malformed-field cases without touching the network. A single unusable entry is skipped
     * rather than failing the whole list - one bad release must not hide the other nine.
     */
    public static List<ReleaseInfo> parseReleases(String json, String repo) {
        if (Texts.isBlank(json)) {
            throw new UpdateException("GitHub 返回了空响应");
        }
        JsonNode root;
        try {
            root = Json.mapper().readTree(json);
        } catch (IOException e) {
            throw new UpdateException("GitHub 返回的版本列表不是合法 JSON", e);
        }
        if (root == null || !root.isArray()) {
            throw new UpdateException("GitHub 返回的版本列表格式不正确（应为数组）");
        }
        List<ReleaseInfo> out = new ArrayList<>();
        for (JsonNode node : root) {
            if (!node.isObject()) {
                continue;
            }
            ReleaseInfo release = toRelease(node, repo);
            if (release != null) {
                out.add(release);
            }
        }
        // Newest first: the UI shows a history list and the first entry is what "latest" means.
        out.sort(Comparator.comparing((ReleaseInfo release) -> Version.tryParse(release.version),
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .reversed());
        return out;
    }

    private static ReleaseInfo toRelease(JsonNode node, String repo) {
        String tag = text(node, "tag_name");
        Version version = tag == null ? null : Version.tryParse(tag);
        if (version == null) {
            log.debug("skipping a release without a usable tag_name: {}", tag);
            return null;
        }
        ReleaseInfo release = new ReleaseInfo();
        release.tag = tag;
        release.version = version.toString();
        String name = text(node, "name");
        // GitHub allows an untitled release; falling back to the tag keeps the list readable.
        release.name = name == null ? tag : name;
        release.publishedAt = text(node, "published_at");
        release.prerelease = node.path("prerelease").asBoolean(false);
        release.notes = text(node, "body") == null ? "" : text(node, "body");
        String htmlUrl = text(node, "html_url");
        release.url = htmlUrl != null ? htmlUrl : releasePageUrl(repo, tag);
        JsonNode assets = node.path("assets");
        if (assets.isArray()) {
            for (JsonNode asset : assets) {
                if (!asset.isObject()) {
                    continue;
                }
                String assetName = text(asset, "name");
                if (assetName == null) {
                    continue;
                }
                // browser_download_url is a plain CDN link: it needs no token and does not consume
                // the API quota, which matters for a 40 MB jar. The API url is the fallback.
                String downloadUrl = text(asset, "browser_download_url");
                if (downloadUrl == null) {
                    downloadUrl = text(asset, "url");
                }
                release.assets.add(new ReleaseInfo.Asset(assetName,
                        asset.path("size").asLong(0L), downloadUrl));
            }
        }
        return release;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return Texts.isBlank(text) ? null : text.strip();
    }

    public static String releasePageUrl(String repo, String tag) {
        return "https://github.com/" + repo + "/releases/tag/" + tag;
    }
}
