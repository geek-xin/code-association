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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the releases of this project from the GitHub REST API.
 *
 * Unauthenticated GitHub allows 60 requests per hour per IP; with a token it is 5000. The token is
 * therefore optional and, when present, only ever raises the ceiling.
 *
 * Every failure becomes an {@link UpdateException} whose message is written for the user, because
 * the update card in the UI shows it verbatim. Nothing here decides whether an update is available:
 * that needs the running version and belongs to {@link UpdateService}.
 *
 * Two GitHub endpoints are read: releases, and optionally bare tags. A release is a tag plus notes
 * and assets, and a maintainer sometimes pushes the tag first and writes the release later (or
 * never), so releases alone silently hide part of the history. Only the releases request is
 * load-bearing - see {@link #withTags} for why a tags failure is downgraded to a warning.
 *
 * <h2>Why there is more than one host</h2>
 *
 * {@code api.github.com} is not reachable from every network this application runs in, and the
 * failure is indistinguishable from "the project has no releases" unless something retries. The
 * configured hosts are tried in order, and a host that fails at the transport level or answers 5xx
 * hands over to the next one. What must <i>not</i> happen is a fallback on a 404 or a 401: those
 * mean "this repository does not exist" and "this token is wrong", which no mirror can fix, and
 * silently asking another host would only hide the real problem.
 */
@Component
public class GitHubVersionSource implements VersionSource {

    private static final Logger log = LoggerFactory.getLogger(GitHubVersionSource.class);

    /** Connecting to GitHub is a user-facing action; fail fast instead of hanging the request. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private static final DateTimeFormatter RESET_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    /** GitHub's public API; overridable so a test can point the source at a loopback server. */
    private static final String DEFAULT_API_BASE = "https://api.github.com";

    /**
     * Newest version first: the UI shows a history list and the first entry is what "latest" means.
     * An unparsable version sinks to the end instead of throwing, because it is already an outlier
     * by the time anything reaches this list.
     */
    private static final Comparator<ReleaseInfo> NEWEST_FIRST =
            Comparator.comparing((ReleaseInfo release) -> Version.tryParse(release.version),
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .reversed();

    private final AppProperties properties;
    private final HttpClient client;
    /** REST bases without a trailing slash, in the order they are tried. */
    private final List<String> apiBases;
    /** Shared "we are out of quota" latch; see {@link RateLimitGate}. */
    private final RateLimitGate rateLimitGate;

    /**
     * Problems of the last {@link #fetch()} that did not make its answer unusable.
     *
     * Replaced as a whole, never mutated in place, so a reader never observes a half-updated list.
     */
    private volatile List<String> warnings = List.of();

    /** The base that answered the last successful fetch, for the status payload. */
    private volatile String activeApiBase;

    /**
     * The last host that failed, or null when the last attempt succeeded.
     *
     * Reported separately from the error text because the UI wants to say <i>where</i> it failed
     * next to <i>why</i>, and parsing a host back out of a localised message would be a bug waiting
     * for the first message edit.
     */
    private volatile String lastFailureHost;

    /**
     * The production constructor. Marked {@code @Autowired} because the class has other
     * constructors for tests: with more than one constructor Spring does not guess, it looks for a
     * no-arg one and fails to start the context when there is none.
     */
    @Autowired
    public GitHubVersionSource(AppProperties properties) {
        this(properties, GitHubNetwork.client(properties.getUpdate()),
                properties.getUpdate().getApiBaseUrls(), new RateLimitGate());
    }

    /** Visible for tests that need to assert the request shape without reaching GitHub. */
    GitHubVersionSource(AppProperties properties, HttpClient client) {
        this(properties, client, List.of(DEFAULT_API_BASE), new RateLimitGate());
    }

    /**
     * Full form: a pinned client, an explicit list of hosts and a shared gate.
     *
     * The host list is a parameter rather than a constant because the request building (headers,
     * token, timeout, status handling) is worth testing against a loopback server, and because a
     * GitHub Enterprise installation or a mirror would serve exactly the same paths under another
     * host.
     */
    public GitHubVersionSource(AppProperties properties, HttpClient client, String apiBase) {
        this(properties, client, List.of(apiBase), new RateLimitGate());
    }

    /**
     * The full form used by the production constructor and by tests that need a shared gate.
     *
     * Blank entries are dropped and duplicates collapsed: a settings file that repeats the default
     * host would otherwise cost a second round trip for the same answer, and an empty string would
     * turn every path into a relative URI that cannot be requested at all.
     */
    public GitHubVersionSource(AppProperties properties, HttpClient client, List<String> apiBaseUrls,
                               RateLimitGate rateLimitGate) {
        this.properties = properties;
        this.client = client;
        this.rateLimitGate = rateLimitGate == null ? new RateLimitGate() : rateLimitGate;
        LinkedHashSet<String> bases = new LinkedHashSet<>();
        if (apiBaseUrls != null) {
            for (String base : apiBaseUrls) {
                String trimmed = Texts.trim(base);
                if (!trimmed.isEmpty()) {
                    bases.add(stripTrailingSlash(trimmed));
                }
            }
        }
        this.apiBases = List.copyOf(bases.isEmpty() ? List.of(DEFAULT_API_BASE) : bases);
        this.activeApiBase = this.apiBases.get(0);
    }

    @Override
    public List<ReleaseInfo> fetch() {
        AppProperties.Update update = properties.getUpdate();
        String repo = Texts.trim(update.getRepo());
        if (Texts.isBlank(repo)) {
            throw new UpdateException("未配置更新仓库（code-association.update.repo）");
        }
        int perPage = Math.max(1, Math.min(100, update.getMaxReleases()));
        // Warnings describe this attempt only; a stale line from the previous check would be read
        // as if the current one had the same problem.
        warnings = List.of();
        return fetchFromAnyBase(repo, perPage, update);
    }

    /**
     * Tries each configured host until one answers.
     *
     * The last failure is what the user sees, because it is the one that describes why every host
     * failed; earlier ones are folded into a warning so the fallback stays visible without burying
     * the final reason. A host-level failure (transport error, 5xx, exhausted quota) moves on; a
     * content-level one (404, 401) ends the search, because the next host would answer the same.
     */
    private List<ReleaseInfo> fetchFromAnyBase(String repo, int perPage, AppProperties.Update update) {
        List<String> failures = new ArrayList<>();
        UpdateException last = null;
        for (String base : apiBases) {
            try {
                List<ReleaseInfo> releases = fetchFrom(base, repo, perPage, update);
                activeApiBase = base;
                lastFailureHost = null;
                if (!failures.isEmpty()) {
                    warnings = appendWarning("主 API 源不可用，已改用 " + base + "：" + failures.get(0));
                }
                return releases;
            } catch (UpdateException e) {
                if (!isHostLevelFailure(e)) {
                    // A missing repository or a rejected token is the same answer everywhere.
                    throw e;
                }
                log.warn("update source {} failed: {}", base, e.getMessage());
                failures.add(base + " → " + e.getMessage());
                lastFailureHost = base;
                last = e;
            }
        }
        // Every host failed, so the per-host trail is the only thing that explains what was tried.
        // It goes into warnings rather than into the message: the message stays one actionable
        // sentence, and the card already renders warnings as a list under it.
        if (!failures.isEmpty()) {
            warnings = List.copyOf(failures);
        }
        StringBuilder message = new StringBuilder();
        if (last != null) {
            message.append(last.getMessage());
        }
        if (apiBases.size() > 1) {
            message.append("（已尝试 ").append(apiBases.size()).append(" 个 API 源）");
        }
        throw new UpdateException(message.toString(), last);
    }

    /**
     * Whether a failure says something about <i>this host</i> rather than about the repository.
     *
     * The distinction is carried by the message because {@link UpdateException} is the one type the
     * source contract allows, and it is written for the user rather than tagged for the caller. The
     * two content-level cases are spelled out positively so a new message can never silently turn
     * into a fallback.
     */
    private static boolean isHostLevelFailure(UpdateException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        return !(message.startsWith("仓库不存在") || message.startsWith("GitHub token 无效"));
    }

    private List<ReleaseInfo> fetchFrom(String base, String repo, int perPage, AppProperties.Update update) {
        // The releases request carries what an update needs (notes, assets), so its failure stays
        // fatal: a half-known answer here would be reported as "no update" and hide a real one.
        List<ReleaseInfo> releases = parseReleases(get(base, releasesPath(repo, perPage), repo, update), repo);
        if (!update.isIncludeTags()) {
            // Tags were never asked for, so there is nothing that could be missing.
            return select(releases, update);
        }
        return withTags(base, releases, repo, perPage, update);
    }

    @Override
    public List<String> warnings() {
        return warnings;
    }

    /** The base that answered last, or the first configured one before any success. */
    public String activeApiBase() {
        return activeApiBase;
    }

    /** The hosts this source will try, in order; reported to the UI so a fallback is explainable. */
    public List<String> apiBases() {
        return apiBases;
    }

    /** The host that failed last, or null when the last fetch succeeded. */
    public String lastFailureHost() {
        return lastFailureHost;
    }

    /** The shared quota latch, so the download path consults the same deadline as the check. */
    public RateLimitGate rateLimitGate() {
        return rateLimitGate;
    }

    private List<String> appendWarning(String warning) {
        List<String> out = new ArrayList<>(warnings);
        out.add(warning);
        return List.copyOf(out);
    }

    /**
     * Adds the versions that exist only as tags, and turns a tags failure into a warning.
     *
     * The whole point of the tags request is completeness, so losing it must not cost the answer
     * that was already fetched: the user still gets every release, plus one line saying the history
     * may be incomplete. A nested UpdateException would instead blank the card and, worse, reset
     * "is there an update" to false.
     */
    private List<ReleaseInfo> withTags(String base, List<ReleaseInfo> releases, String repo, int perPage,
                                       AppProperties.Update update) {
        List<ReleaseInfo> tags;
        try {
            tags = parseTags(get(base, tagsPath(repo, perPage), repo, update), repo);
        } catch (UpdateException e) {
            if (Thread.currentThread().isInterrupted()) {
                // An interrupt is a request to stop, not a degraded answer: it must end the fetch.
                throw e;
            }
            log.warn("tags request failed, serving releases only: {}", e.getMessage());
            warnings = appendWarning("读取标签列表失败，本次仅显示 releases：" + e.getMessage());
            return select(releases, update);
        }
        return select(merge(releases, tags), update);
    }

    private static String releasesPath(String repo, int perPage) {
        return "/repos/" + repo + "/releases?per_page=" + perPage;
    }

    private static String tagsPath(String repo, int perPage) {
        return "/repos/" + repo + "/tags?per_page=" + perPage;
    }

    /**
     * One GET against the REST API, with the headers and the failure mapping shared by both calls.
     *
     * Both endpoints are authenticated the same way; building the request twice would let the token
     * (or the mandatory User-Agent) drift out of one of them unnoticed.
     *
     * Retries live here rather than in the caller because they are a property of "how to reach this
     * host", not of what the answer is used for. A 401 or a 404 is returned immediately: retrying a
     * rejection only spends quota.
     */
    private String get(String base, String path, String repo, AppProperties.Update update) {
        URI uri = URI.create(base + path);
        // One attempt per host while the quota is known to be gone, and the configured number
        // otherwise. The single attempt is deliberate: a mirror may answer from a different IP with
        // its own quota, so refusing to ask it would disable the fallback in precisely the case it
        // exists for. What is never done is a retry loop against a host that just said "no quota".
        int attempts = rateLimitGate.isBlocked()
                ? 1 : Math.max(1, 1 + Math.max(0, update.getRetries()));
        UpdateException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            HttpRequest request = request(uri, update);
            HttpResponse<String> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException e) {
                last = new UpdateException("无法连接 GitHub（" + CONNECT_TIMEOUT.toSeconds() + " 秒超时）", e);
                if (attempt < attempts) {
                    sleepBeforeRetry(attempt, update);
                    continue;
                }
                throw last;
            } catch (IOException e) {
                last = new UpdateException("无法连接 GitHub：" + GitHubNetwork.describe(e), e);
                if (attempt < attempts) {
                    sleepBeforeRetry(attempt, update);
                    continue;
                }
                throw last;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpdateException("版本检查被中断", e);
            }
            int status = response.statusCode();
            if (status < 300) {
                rateLimitGate.noteSuccess();
                return response.body();
            }
            noteRateLimit(response, update);
            // Retrying is pointless once this host has published a quota deadline: every further
            // request pushes the reset time out. Falling through to checkStatus() turns the answer
            // into an UpdateException the caller treats as a host-level failure, which is what
            // hands the request to the next configured host instead of ending the fetch.
            if (GitHubNetwork.isRetryableStatus(status) && attempt < attempts && !rateLimitGate.isBlocked()) {
                log.debug("retrying {} after HTTP {}", uri, status);
                sleepBeforeRetry(attempt, update);
                continue;
            }
            checkStatus(response, repo, update);
        }
        // The loop either returns or throws; reaching here would mean attempts < 1.
        throw last == null ? new UpdateException("检查更新失败") : last;
    }

    private HttpRequest request(URI uri, AppProperties.Update update) {
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
        return builder.build();
    }

    /**
     * Waits out the backoff, restoring the interrupt flag instead of swallowing it.
     *
     * A thread that is interrupted during the pause is about to be asked to stop; continuing the
     * retry loop would ignore that, so the flag is set and the next gate check ends the request.
     */
    private static void sleepBeforeRetry(int attempt, AppProperties.Update update) {
        long delay = GitHubNetwork.backoffMillis(attempt, update.getRetryBaseDelayMs());
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Records the quota deadline when this response says the quota is gone.
     *
     * Both the 403 and the 429 case carry it, and reading the header once here is what lets the
     * caller, the scheduler and the download path share one deadline instead of three guesses.
     */
    private void noteRateLimit(HttpResponse<String> response, AppProperties.Update update) {
        if (!update.isRespectRateLimit()) {
            return;
        }
        int status = response.statusCode();
        if (status != 403 && status != 429) {
            return;
        }
        String remaining = header(response, "x-ratelimit-remaining");
        if (status == 429 || "0".equals(remaining)) {
            rateLimitGate.noteRateLimited(GitHubNetwork.rateLimitReset(response));
        }
    }

    private String blockedMessage() {
        Instant until = rateLimitGate.blockedUntil();
        StringBuilder message = new StringBuilder("GitHub API 已限流，暂时不再请求");
        if (until != null) {
            message.append("，将于 ")
                    .append(RESET_FORMAT.format(until.atZone(ZoneId.systemDefault())))
                    .append(" 恢复");
        }
        message.append("；配置 token 可提高额度至每小时 5000 次");
        return message.toString();
    }

    /** Drops trailing slashes without eating the "//" of the scheme itself. */
    private static String stripTrailingSlash(String text) {
        int scheme = text.indexOf("://");
        int floor = scheme < 0 ? 1 : scheme + 3;
        int end = text.length();
        while (end > floor && text.charAt(end - 1) == '/') {
            end--;
        }
        return text.substring(0, end);
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
        Instant reset = GitHubNetwork.rateLimitReset(response);
        if (reset != null) {
            message.append("；额度将在 ")
                    .append(RESET_FORMAT.format(reset.atZone(ZoneId.systemDefault())))
                    .append(" 重置");
        }
        return message.toString();
    }

    private static String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElse(null);
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
            // A draft is a release nobody has published yet. The list endpoint omits drafts for
            // anonymous callers, but that is a server-side default, not a guarantee: a token with
            // push access does receive them, and offering an unpublished build as an update would
            // be the one mistake this feature cannot take back.
            if (node.path("draft").asBoolean(false)) {
                continue;
            }
            ReleaseInfo release = toRelease(node, repo);
            if (release != null) {
                out.add(release);
            }
        }
        out.sort(NEWEST_FIRST);
        return out;
    }

    /**
     * Turns a GitHub tags payload into normalised entries, newest first.
     *
     * The tags endpoint is much poorer than releases: no notes, no assets, no timestamp, only a
     * name and a commit. Everything it cannot answer is left empty rather than invented - in
     * particular {@code publishedAt} stays null instead of borrowing the commit date, which is a
     * different fact.
     */
    public static List<ReleaseInfo> parseTags(String json, String repo) {
        if (Texts.isBlank(json)) {
            throw new UpdateException("GitHub 返回了空响应");
        }
        JsonNode root;
        try {
            root = Json.mapper().readTree(json);
        } catch (IOException e) {
            throw new UpdateException("GitHub 返回的标签列表不是合法 JSON", e);
        }
        if (root == null || !root.isArray()) {
            throw new UpdateException("GitHub 返回的标签列表格式不正确（应为数组）");
        }
        List<ReleaseInfo> out = new ArrayList<>();
        for (JsonNode node : root) {
            if (!node.isObject()) {
                continue;
            }
            // "name" is the shape /tags returns. A "refs/tags/x" form also turns up in GitHub's
            // other tag-shaped payloads and costs one strip to accept, so the parser survives a
            // switch between them.
            String tag = text(node, "name");
            if (tag == null) {
                String ref = text(node, "ref");
                tag = ref == null ? null : (ref.startsWith("refs/tags/")
                        ? ref.substring("refs/tags/".length()) : ref);
            }
            // An unparsable tag is one entry lost, not the whole list: repositories carry
            // non-version tags ("nightly", "latest") all the time.
            Version version = tag == null ? null : Version.tryParse(tag);
            if (version == null) {
                log.debug("skipping an unusable tag: {}", tag);
                continue;
            }
            ReleaseInfo release = new ReleaseInfo();
            release.tag = tag;
            release.version = version.toString();
            release.name = tag;
            release.fromTag = true;
            release.prerelease = version.isPreRelease();
            release.publishedAt = null;
            release.notes = "";
            release.url = releasePageUrl(repo, tag);
            out.add(release);
        }
        out.sort(NEWEST_FIRST);
        return out;
    }

    /**
     * Unions releases and tags, with the release winning every collision.
     *
     * A release and the tag it was cut from describe the same version, and the release carries the
     * only notes and assets either of them has, so keying on the normalised version and keeping the
     * release is what makes the merge lossless. Same version from both sides is the common case,
     * not an edge case: every released version is tagged.
     *
     * Public for the same reason as {@link #parseReleases}: the rule is the interesting part and
     * is testable from inline JSON without a server.
     */
    public static List<ReleaseInfo> merge(List<ReleaseInfo> releases, List<ReleaseInfo> tags) {
        Map<String, ReleaseInfo> byVersion = new LinkedHashMap<>();
        for (ReleaseInfo release : releases) {
            byVersion.putIfAbsent(release.version, release);
        }
        for (ReleaseInfo tag : tags) {
            byVersion.putIfAbsent(tag.version, tag);
        }
        List<ReleaseInfo> out = new ArrayList<>(byVersion.values());
        out.sort(NEWEST_FIRST);
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