package com.geek.association.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "code-association")
public class AppProperties {

    /** Directory that stores the SQLite database, cloned repositories and snapshots. */
    private String dataDir = "./.code-association";

    /** Maximum files parsed per repository (safety valve). */
    private int maxFiles = 20000;

    /** Maximum call graph traversal depth. */
    private int maxDepth = 8;

    /** Optional LLM explanation. Disabled by default: every fact comes from static analysis. */
    private Ai ai = new Ai();

    /** Daily proactive scan: analyse programs that received new commits. */
    private Schedule schedule = new Schedule();

    /** Self-update from the project's GitHub releases. */
    private Update update = new Update();


    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public int getMaxFiles() {
        return maxFiles;
    }

    public void setMaxFiles(int maxFiles) {
        this.maxFiles = maxFiles;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
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

    public Path dataPath() {
        return Path.of(dataDir).toAbsolutePath().normalize();
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
    }

    public static class Ai {
        private boolean enabled = false;
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";

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
    }
}