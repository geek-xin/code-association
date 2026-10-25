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