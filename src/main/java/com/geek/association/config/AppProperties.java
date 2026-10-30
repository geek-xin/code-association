package com.geek.association.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "code-association")
public class AppProperties {

    /** Directory that stores the SQLite database, cloned repositories and snapshots. */
    private String dataDir = "./.code-association";

    /**
     * Directory that receives the log files.
     *
     * Kept separate from {@link #dataDir} on purpose: the data directory holds state that has to be
     * backed up (reports, sources), while the log directory holds diagnostics that can be deleted
     * at any time. The actual rolling/retention lives in {@code logback-spring.xml}, which reads the
     * same property through the Spring environment; this field exists so the startup banner can
     * print the absolute path without knowing how Logback is configured.
     */
    private String logDir = "./logs";

    /** Maximum files parsed per repository (safety valve). */
    private int maxFiles = 20000;

    /**
     * Largest single file the indexer will read, in megabytes.
     *
     * Source files are kilobytes; anything near this limit is a committed SDK, a database dump or
     * a packaged jar under another extension. Reading those costs memory and time without adding
     * anything to the analysis, and the report names each one it left out so the omission stays
     * visible. Zero or negative disables the limit entirely.
     */
    private int maxFileMb = 20;

    /** Maximum call graph traversal depth. */
    private int maxDepth = 8;

    /**
     * How many impact rows one report may carry.
     *
     * <p>A wide delta produces hundreds of thousands of cross-program rows: every changed contract
     * propagates through the peer's call graph on its own, and the totals multiply by the number of
     * compared programs. The report used to be serialised whole into one string, and past Jackson's
     * 2 GiB text-buffer limit the run failed at the very last step - after minutes of analysis -
     * losing the report entirely. The stored list is now a risk-ordered subset: the counts stay
     * exact, the rows the reader sees are the ones worth reviewing, and the report says how many
     * were left out. Zero or negative disables the cap, which is only sensible for tiny programs.</p>
     */
    private int maxReportImpacts = 5000;

    /** Optional LLM explanation. Disabled by default: every fact comes from static analysis. */
    private Ai ai = new Ai();

    /** Daily proactive scan: analyse programs that received new commits. */
    private Schedule schedule = new Schedule();

    /** Self-update from the project's GitHub releases. */
    private Update update = new Update();

    /** Private GitLab repositories: the personal access token shown in the settings page. */
    private GitLab gitlab = new GitLab();


    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public String getLogDir() {
        return logDir;
    }

    public void setLogDir(String logDir) {
        this.logDir = logDir;
    }

    public int getMaxFiles() {
        return maxFiles;
    }

    public void setMaxFiles(int maxFiles) {
        this.maxFiles = maxFiles;
    }

    /** Same limit in bytes, which is the unit the readers compare against. */
    public int maxFileBytes() {
        if (maxFileMb <= 0) {
            return Integer.MAX_VALUE;
        }
        // An int holds up to 2 GiB, and the readers pass this value to JGit, which takes an int.
        return (int) Math.min(Integer.MAX_VALUE, (long) maxFileMb * 1024L * 1024L);
    }

    public int getMaxFileMb() {
        return maxFileMb;
    }

    public void setMaxFileMb(int maxFileMb) {
        this.maxFileMb = maxFileMb;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public int getMaxReportImpacts() {
        return maxReportImpacts;
    }

    public void setMaxReportImpacts(int maxReportImpacts) {
        this.maxReportImpacts = maxReportImpacts;
    }

    public Ai getAi() {
        return ai;
    }

    public void setAi(Ai ai) {
        this.ai = ai;
    }

    public Schedule getSchedule() {
        return schedule;
    }

    public void setSchedule(Schedule schedule) {
        this.schedule = schedule;
    }

    public Update getUpdate() {
        return update;
    }

    public void setUpdate(Update update) {
        this.update = update;
    }

    public GitLab getGitlab() {
        return gitlab;
    }

    public void setGitlab(GitLab gitlab) {
        this.gitlab = gitlab;
    }

    public Path dataPath() {
        return Path.of(dataDir).toAbsolutePath().normalize();
    }

    /** Absolute log directory, which is what a "日志在哪儿" answer needs. */
    public Path logPath() {
        return Path.of(logDir).toAbsolutePath().normalize();
    }

    /**
     * Daily proactive scan settings.
     *
     * The scan exists so a change is noticed without anyone pressing a button: once a day every
     * program is checked, and only those whose commits moved since the last analysis are run.
     */
    public static class Schedule {
        private boolean enabled = true;
        /** Spring cron: second minute hour day-of-month month day-of-week. Default 02:30 daily. */
        private String cron = "0 30 2 * * *";
        /** Run one scan shortly after startup, so a freshly deployed instance catches up. */
        private boolean runOnStartup = false;
        /** Upper bound of programs analysed in one tick, to keep a big registry predictable. */
        private int maxProgramsPerRun = 20;
        /** Minutes to wait before re-scanning a plain directory, which has no commit to compare. */
        private int directoryRescanMinutes = 1440;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public boolean isRunOnStartup() {
            return runOnStartup;
        }

        public void setRunOnStartup(boolean runOnStartup) {
            this.runOnStartup = runOnStartup;
        }

        public int getMaxProgramsPerRun() {
            return maxProgramsPerRun;
        }

        public void setMaxProgramsPerRun(int maxProgramsPerRun) {
            this.maxProgramsPerRun = maxProgramsPerRun;
        }

        public int getDirectoryRescanMinutes() {
            return directoryRescanMinutes;
        }

        public void setDirectoryRescanMinutes(int directoryRescanMinutes) {
            this.directoryRescanMinutes = directoryRescanMinutes;
        }
    }

    /**
     * Self-update settings.
     *
     * The update is fetched from GitHub releases because that is where release.sh already publishes
     * the jars. It is on by default: an installation that never learns about a new version is the
     * situation this feature exists to end. Everything it can reach is read-only until the operator
     * explicitly downloads and applies.
     */
    public static class Update {
        private boolean enabled = true;
        /** GitHub repository in owner/repo form; the release list is read from its tags. */
        private String repo = "geek-xin/code-association";
        /**
         * Optional GitHub token. Anonymous access is capped at 60 requests per hour per IP, which a
         * UI that polls the status endpoint reaches quickly; a token raises it to 5000.
         */
        private String token = "";
        /**
         * How long a successful check is reused before asking GitHub again. Six hours keeps the
         * status cheap while still noticing a release the same working day.
         */
        private int checkIntervalMinutes = 360;
        /** Spring cron: second minute hour day-of-month month day-of-week. Default hourly. */
        private String cron = "0 0 * * * *";
        /** How many releases the history list returns; the API payload grows with this. */
        private int maxReleases = 20;
        /**
         * Whether pre-releases are offered. Off by default: they are built for testing, and a
         * half-finished build must not be presented as "the latest version".
         */
        private boolean includePrerelease = false;
        /**
         * Whether bare git tags are read in addition to releases.
         *
         * A release is a tag plus notes and assets, and a maintainer sometimes pushes the tag and
         * only later writes the release (or never does). Reading tags too is what makes the history
         * list complete instead of hiding every version that was not turned into a release.
         */
        private boolean includeTags = true;
        /**
         * Download a newly found jar without being asked. Off by default: the download is large and
         * the decision to interrupt a service stays with the operator. Applying is never automatic,
         * whatever this is set to.
         */
        private boolean autoDownload = false;
        /**
         * Replace the running jar and bring the process back without anyone running stop.sh/start.sh.
         *
         * On by default because it is the whole point of the feature: an update that ends with
         * "now restart it yourself" is not an update, it is a notification. The supervisor it
         * starts health-checks the new process and puts the previous jar back when the new build
         * does not come up, so the failure mode is a short outage rather than a dead installation.
         * Turning it off restores the old behaviour (apply, then restart by hand).
         */
        private boolean autoRestart = true;
        /**
         * Do the whole thing unattended: download, apply and restart as soon as a newer installable
         * version appears. Off by default - restarting a service is disruptive, and the operator
         * should be the one who decides that nobody is watching. Applies only when a jar asset
         * exists; a tags-only version is never installed automatically.
         */
        private boolean autoApply = false;
        /**
         * How long the supervisor waits for the new process to answer its health check before it
         * restores the backup and starts the old jar again.
         */
        private int restartTimeoutSeconds = 90;
        /**
         * GitHub API hosts tried in order. The first is the canonical one; the rest are mirrors
         * used only when it cannot be reached (a blocked or rate-limited network).
         *
         * A mirror must speak the GitHub REST API under the same paths, so it is a proxy or a
         * self-hosted replica - not a release page.
         */
        private List<String> apiBaseUrls = new ArrayList<>(List.of("https://api.github.com"));
        /**
         * Prefix prepended to asset download URLs to route them through a mirror, e.g.
         * {@code https://ghfast.top}. Empty means "download straight from github.com".
         *
         * Release assets are served from a CDN that is blocked in some networks while the API
         * itself is reachable, so this is separate from {@link #apiBaseUrls}.
         */
        private String downloadMirror = "";
        /**
         * Optional proxy for update traffic, as {@code host:port} or
         * {@code http://user:password@host:port}. Empty means "use the JVM's own proxy settings",
         * which is what {@code -Dhttps.proxyHost} and {@code HTTPS_PROXY} end up as.
         *
         * Read explicitly rather than relying on the JVM alone because a value in application.yml
         * is far easier to keep next to the rest of the update settings than a JVM flag in a
         * start script.
         */
        private String proxy = "";
        /** Extra attempts per request after the first failure; 0 means "try once". */
        private int retries = 2;
        /** Base delay of the exponential backoff between attempts, milliseconds. */
        private long retryBaseDelayMs = 500;
        /**
         * Never retry before GitHub's rate-limit reset once the quota is exhausted.
         *
         * Without it a retry loop turns "wait until 14:05" into "hammer a dead endpoint and stay
         * limited for the rest of the hour".
         */
        private boolean respectRateLimit = true;
        /** Run one check shortly after startup instead of waiting for the first cron boundary. */
        private boolean checkOnStartup = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRepo() {
            return repo;
        }

        public void setRepo(String repo) {
            this.repo = repo;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public int getCheckIntervalMinutes() {
            return checkIntervalMinutes;
        }

        public void setCheckIntervalMinutes(int checkIntervalMinutes) {
            this.checkIntervalMinutes = checkIntervalMinutes;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public int getMaxReleases() {
            return maxReleases;
        }

        public void setMaxReleases(int maxReleases) {
            this.maxReleases = maxReleases;
        }

        public boolean isIncludePrerelease() {
            return includePrerelease;
        }

        public void setIncludePrerelease(boolean includePrerelease) {
            this.includePrerelease = includePrerelease;
        }

        public boolean isIncludeTags() {
            return includeTags;
        }

        public void setIncludeTags(boolean includeTags) {
            this.includeTags = includeTags;
        }

        public boolean isAutoDownload() {
            return autoDownload;
        }

        public void setAutoDownload(boolean autoDownload) {
            this.autoDownload = autoDownload;
        }

        public boolean isAutoRestart() {
            return autoRestart;
        }

        public void setAutoRestart(boolean autoRestart) {
            this.autoRestart = autoRestart;
        }

        public boolean isAutoApply() {
            return autoApply;
        }

        public void setAutoApply(boolean autoApply) {
            this.autoApply = autoApply;
        }

        public int getRestartTimeoutSeconds() {
            return restartTimeoutSeconds;
        }

        public void setRestartTimeoutSeconds(int restartTimeoutSeconds) {
            this.restartTimeoutSeconds = restartTimeoutSeconds;
        }

        /**
         * Configured hosts, always with the canonical API first.
         *
         * A configured list that starts with the canonical host would otherwise make the caller
         * deduplicate; doing it here means "which hosts, in which order" is answered once.
         */
        public List<String> getApiBaseUrls() {
            return apiBaseUrls;
        }

        public void setApiBaseUrls(List<String> apiBaseUrls) {
            this.apiBaseUrls = apiBaseUrls;
        }

        public String getDownloadMirror() {
            return downloadMirror;
        }

        public void setDownloadMirror(String downloadMirror) {
            this.downloadMirror = downloadMirror;
        }

        public String getProxy() {
            return proxy;
        }

        public void setProxy(String proxy) {
            this.proxy = proxy;
        }

        public int getRetries() {
            return retries;
        }

        public void setRetries(int retries) {
            this.retries = retries;
        }

        public long getRetryBaseDelayMs() {
            return retryBaseDelayMs;
        }

        public void setRetryBaseDelayMs(long retryBaseDelayMs) {
            this.retryBaseDelayMs = retryBaseDelayMs;
        }

        public boolean isRespectRateLimit() {
            return respectRateLimit;
        }

        public void setRespectRateLimit(boolean respectRateLimit) {
            this.respectRateLimit = respectRateLimit;
        }

        public boolean isCheckOnStartup() {
            return checkOnStartup;
        }

        public void setCheckOnStartup(boolean checkOnStartup) {
            this.checkOnStartup = checkOnStartup;
        }
    }

    /**
     * Private GitLab access.
     *
     * The token is the baseline: a snapshot saved in the settings page is written to
     * {@code {dataDir}/gitlab-settings.json} and overrides this value on the next startup, the same
     * way the AI settings card overrides {@link Ai}. It is only ever attached to sources whose type
     * is {@code GITLAB} (see {@code RepositoryPreparer}); a source that names a credential
     * environment variable still wins, so an installation can keep per-repository credentials.
     */
    public static class GitLab {
        /**
         * Personal access token used to read private GitLab repositories.
         *
         * Read from {@code GITLAB_TOKEN} by application.yml so a headless install needs no file,
         * but never logged and never returned by the API in plain text.
         */
        private String token = "";

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }

    /**
     * Optional LLM review layer.
     *
     * Off by default and kept to facts the analyser already proved: the model never sees the
     * repository, so an answer can only restate the change, its propagation and the blind spots.
     * The settings page can override every field below through {@code {dataDir}/ai-settings.json},
     * which is why nothing here validates eagerly - a half-written file must not stop startup.
     */
    public static class Ai {
        private boolean enabled = false;
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";
        /**
         * How many impact rows are handed to the model per call.
         *
         * The prompt is what costs money and latency, and the first dozen findings carry almost all
         * of the review value; past that the model repeats itself. Capped at 50 because a larger
         * prompt tends to be truncated by the provider rather than answered.
         */
        private int maxImpacts = 12;
        /**
         * Wall-clock limit of one model request.
         *
         * A reasoning model can legitimately take a minute, but a run must not hang on a dead
         * endpoint: the analysis itself is already finished by the time this request is made.
         */
        private int timeoutSeconds = 60;
        /**
         * Optional explicit wall-clock limit of one AI section; empty means "derive it".
         *
         * <p>A deployment knob rather than a settings-page field: it belongs to the shape of the
         * installation (which endpoint, how slow), not to a per-run experiment, so it lives in
         * {@code application.yml} and is deliberately absent from {@code ai-settings.json} - a
         * snapshot that carried it would silently override the deployment value after the first
         * save from the settings page.
         */
        private Integer totalBudgetSeconds;

        public Integer getTotalBudgetSeconds() {
            return totalBudgetSeconds;
        }

        public void setTotalBudgetSeconds(Integer totalBudgetSeconds) {
            this.totalBudgetSeconds = totalBudgetSeconds;
        }

        /**
         * Wall-clock limit of one AI section: {@link #totalBudgetSeconds} when set, otherwise
         * derived from {@link #timeoutSeconds}.
         *
         * <p>One request is bounded by {@code timeoutSeconds}, but a report with comments makes up
         * to a dozen requests (summary, six review chunks, six re-locations, one reflection). The
         * per-request limit therefore says nothing about how long the analysis sits on its last
         * progress step, which is what the user actually experiences as "卡在 96%".
         *
         * <p>The derived value is three per-request timeouts, floored at 90 and capped at 900
         * seconds: enough for a slow local model to finish several calls, few enough that a dead
         * endpoint cannot hold the run for a quarter of an hour. Derived rather than required so
         * raising the single-request timeout - already tunable on the settings page - raises the
         * section budget with it instead of silently truncating the review.
         */
        public int resolvedTotalBudgetSeconds() {
            if (totalBudgetSeconds != null && totalBudgetSeconds > 0) {
                return totalBudgetSeconds;
            }
            int perCall = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
            return Math.max(90, Math.min(900, perCall * 3));
        }

        /**
         * Whether a new analysis generates the AI review without being asked.
         *
         * Only a default, not a policy: it decides the checkbox state in the run dialog, and each
         * run can still say no. Meaningless while {@link #enabled} is false.
         */
        private boolean defaultForRuns = true;

        /**
         * Whether the AI section also carries line-anchored review comments.
         *
         * A separate switch from {@link #enabled} because the two stages have different privacy
         * costs: the summary sends only derived facts, while comments have to send the changed
         * hunks for the model to review. An installation that accepted the old promise can keep it
         * by leaving this off and still get the proof-carrying summary it always had.
         */
        private boolean comments = true;

        /**
         * Cap on comments in one report.
         *
         * Same shape as {@link #maxImpacts}: the first few comments carry almost all the review
         * value, and a long list is both expensive and harder to act on. 1..50.
         */
        private int maxComments = 12;

        /**
         * Whether the reflection pass removes comments the diff contradicts.
         *
         * The pass costs one extra model call per report, so it is switchable; off means only the
         * deterministic filters run. It never drops a comment merely for being unconvincing - that
         * line is what keeps the filter from turning false positives into false negatives.
         */
        private boolean reflection = true;

        /**
         * Whether this configuration can actually call a model.
         *
         * The single definition of "ready" for the whole backend: the settings page, the health
         * endpoint, the run dialog and the补生成 endpoint all ask this instead of re-deriving the
         * three conditions, so they cannot disagree about what "配置完成" means.
         */
        public boolean ready() {
            return enabled
                    && baseUrl != null && !baseUrl.isBlank()
                    && model != null && !model.isBlank();
        }

        /**
         * Why {@link #ready()} is false, in one sentence for the UI; empty when it is ready.
         *
         * Lives here rather than in the web layer because the same sentence is shown in three
         * places (settings card, 400 body of the补生成 endpoint, CLI hint) and they must match.
         */
        public String notReadyReason() {
            if (!enabled) {
                return "总开关未打开";
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                return "已启用但未填写接口地址";
            }
            if (model == null || model.isBlank()) {
                return "已启用但未填写模型";
            }
            return "";
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getMaxImpacts() {
            return maxImpacts;
        }

        public void setMaxImpacts(int maxImpacts) {
            this.maxImpacts = maxImpacts;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public boolean isDefaultForRuns() {
            return defaultForRuns;
        }

        public void setDefaultForRuns(boolean defaultForRuns) {
            this.defaultForRuns = defaultForRuns;
        }

        public boolean isComments() {
            return comments;
        }

        public void setComments(boolean comments) {
            this.comments = comments;
        }

        public int getMaxComments() {
            return maxComments;
        }

        public void setMaxComments(int maxComments) {
            this.maxComments = maxComments;
        }

        public boolean isReflection() {
            return reflection;
        }

        public void setReflection(boolean reflection) {
            this.reflection = reflection;
        }
    }
}