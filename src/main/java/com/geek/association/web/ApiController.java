package com.geek.association.web;

import com.geek.association.config.AiSettings;
import com.geek.association.config.AppProperties;
import com.geek.association.model.Analysis;
import com.geek.association.model.RepoSource;
import com.geek.association.report.AiExplainer;
import com.geek.association.report.ImpactReport;
import com.geek.association.schedule.ScheduledScanService;
import com.geek.association.service.AnalysisService;
import com.geek.association.source.GitSupport;
import com.geek.association.source.PathBrowser;
import com.geek.association.source.RepositoryPreparer;
import com.geek.association.source.SourceNames;
import com.geek.association.store.Store;
import com.geek.association.transfer.SourceTags;
import com.geek.association.transfer.SourceTransfer;
import com.geek.association.update.UpdateService;
import com.geek.association.util.Json;
import com.geek.association.util.LogEvents;
import com.geek.association.util.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final Store store;
    private final AnalysisService analysisService;
    private final RepositoryPreparer preparer;
    private final AppProperties properties;
    private final PathBrowser pathBrowser;
    private final ScheduledScanService scheduledScan;
    private final UpdateService updateService;
    private final AiSettings aiSettings;
    private final AiExplainer aiExplainer;

    /**
     * The last two parameters were added with the AI settings endpoints.
     *
     * Nullable on purpose: {@code AnalysisProgressTest} constructs this controller directly with
     * the original five collaborators to pin the progress payload, and the AI endpoints are simply
     * not exercised there. Every AI handler therefore guards on {@code aiSettings == null} rather
     * than the test having to know about a feature it does not test.
     */
    public ApiController(Store store, AnalysisService analysisService, RepositoryPreparer preparer,
                         AppProperties properties, PathBrowser pathBrowser,
                         ScheduledScanService scheduledScan, UpdateService updateService,
                         AiSettings aiSettings, AiExplainer aiExplainer) {
        this.store = store;
        this.analysisService = analysisService;
        this.preparer = preparer;
        this.properties = properties;
        this.pathBrowser = pathBrowser;
        this.scheduledScan = scheduledScan;
        this.updateService = updateService;
        this.aiSettings = aiSettings;
        this.aiExplainer = aiExplainer;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("dataDir", properties.dataPath().toString());
        out.put("maxDepth", properties.getMaxDepth());
        out.put("maxFiles", properties.getMaxFiles());
        out.put("maxFileMb", properties.getMaxFileMb());
        // Kept for the clients that already read it, and now equal to "ready": a switch that is on
        // but has no endpoint or model cannot produce anything, and reporting it as enabled was the
        // reason the old value was misleading. aiReady is the same value under a name that says so.
        boolean aiReady = properties.getAi().ready();
        out.put("aiEnabled", aiReady);
        out.put("aiReady", aiReady);
        return out;
    }

    // ----------------------------------------------------------------------- ai

    /**
     * The AI settings as the settings card renders them: everything except the key itself.
     *
     * Returns the same shape as {@link #saveAi(Map)} so the card can re-render from whichever
     * response it got last, and so "saved" and "loaded" can never disagree about a field name.
     */
    @GetMapping("/ai")
    public Map<String, Object> ai() {
        return aiSettings.view();
    }

    /**
     * Applies and persists the settings, without a restart.
     *
     * A PUT rather than a POST because it replaces the configuration as a set. Validation failures
     * are {@link IllegalArgumentException}s, which the existing advice turns into a 400 with the
     * Chinese sentence the form shows in its toast.
     */
    @PutMapping("/ai")
    public Map<String, Object> saveAi(@RequestBody(required = false) Map<String, Object> body) {
        return aiSettings.update(body);
    }

    /**
     * Proves the endpoint, key and model work together, using the form's unsaved draft.
     *
     * Always answers 200 and puts the outcome in {@code ok}: this is the result of a test the user
     * asked for, and a 4xx/5xx would make the card's error path indistinguishable from "the test
     * itself failed". The body may override any of the three fields, so the user can test before
     * saving - and a blank apiKey falls back to the stored one, because the form only holds a mask.
     */
    @PostMapping("/ai/test")
    public Map<String, Object> testAi(@RequestBody(required = false) Map<String, Object> body) {
        Object baseUrl = body == null ? null : body.get("baseUrl");
        Object model = body == null ? null : body.get("model");
        Object apiKey = body == null ? null : body.get("apiKey");
        return aiExplainer.testConnection(
                baseUrl == null ? null : baseUrl.toString(),
                model == null ? null : model.toString(),
                apiKey == null ? null : apiKey.toString());
    }

    /**
     * Generates the AI section of an existing report and stores it.
     *
     * Three distinct answers on purpose: 404 when the analysis does not exist (the caller's id is
     * wrong), 409 when it exists but has no report yet (the run has not finished - retry later), and
     * a 200 carrying {@code generated:false} when the model call itself failed, because that is a
     * content-level outcome the page must render rather than an HTTP error it must catch.
     */
    @PostMapping("/analyses/{id}/ai-review")
    public ResponseEntity<Map<String, Object>> generateAiReview(@PathVariable long id) {
        // Readiness first, in the order the contract lists the failures: "not configured" is a
        // property of this installation, so it explains the refusal better than the id does.
        if (aiExplainer != null && !aiExplainer.ready()) {
            throw new IllegalArgumentException("AI 审核未启用或未配置完成：" + aiExplainer.notReadyReason());
        }
        if (store.analysis(id) == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "分析不存在：" + id));
        }
        Map<String, Object> ai = analysisService.saveAiReview(id);
        if (ai == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "该分析还没有可用的报告"));
        }
        return ResponseEntity.ok(ai);
    }

    // --------------------------------------------------------------- schedule

    /**
     * Status of the daily proactive scan: when it last ran, what it decided per program, and the
     * cron it will run on next. Lets an operator confirm the schedule is alive without reading logs.
     */
    @GetMapping("/schedule")
    public Map<String, Object> schedule() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", properties.getSchedule().isEnabled());
        out.put("cron", properties.getSchedule().getCron());
        out.put("runOnStartup", properties.getSchedule().isRunOnStartup());
        out.put("maxProgramsPerRun", properties.getSchedule().getMaxProgramsPerRun());
        out.put("directoryRescanMinutes", properties.getSchedule().getDirectoryRescanMinutes());
        out.put("running", scheduledScan.isRunning());
        out.put("last", scheduledScan.lastResult());
        return out;
    }

    /**
     * Runs the scan immediately, with the same rules as the scheduled tick.
     *
     * Returns as soon as the run is accepted: a full scan fetches remotes and indexes every changed
     * program, which outlives any sensible HTTP timeout. The caller polls {@code GET /api/schedule}
     * for the per-program outcome, which is also what makes the schedule testable without waiting
     * until 02:30.
     */
    @PostMapping("/schedule/run")
    public ScheduledScanService.ScanResult runScheduleNow() {
        return scheduledScan.runAsync("manual");
    }

    // ----------------------------------------------------------------- update

    /**
     * Current version, newest release and the release history.
     *
     * Served from a cache when the last successful check is still fresh, so opening the settings
     * page repeatedly does not burn GitHub's 60-requests-per-hour unauthenticated quota. The
     * {@code source} field says where the answer came from, which is what makes a stale-but-served
     * result honest instead of silently misleading.
     */
    @GetMapping("/update")
    public Map<String, Object> update() {
        return updateService.status();
    }

    /** Ignores the cache and asks GitHub again. Backs the "check now" button. */
    @PostMapping("/update/check")
    public Map<String, Object> checkForUpdate() {
        return updateService.checkNow();
    }

    /**
     * Downloads one release's jar into the data directory and verifies it.
     *
     * Deliberately separate from applying: the download is the slow, failure-prone half, and a user
     * should be able to fetch an artifact without yet committing to replacing a running service.
     */
    @PostMapping("/update/download")
    public Map<String, Object> downloadUpdate(@RequestBody(required = false) Map<String, Object> body) {
        Object version = body == null ? null : body.get("version");
        if (version == null || version.toString().isBlank()) {
            throw new IllegalArgumentException("缺少 version 参数（要下载的版本号，例如 0.4.0）");
        }
        return updateService.download(version.toString());
    }

    /**
     * Replaces the running jar with the downloaded one, after backing the current jar up.
     *
     * The process is not restarted here on purpose: the frontend needs this response to tell the
     * user what happened, and when a service goes down should stay the operator's decision.
     */
    @PostMapping("/update/apply")
    public Map<String, Object> applyUpdate() {
        return updateService.apply();
    }

    /** Restores the most recent backup made by {@link #applyUpdate()}. */
    @PostMapping("/update/rollback")
    public Map<String, Object> rollbackUpdate() {
        return updateService.rollback();
    }

    /**
     * Every version this installation has seen, downloaded, applied or rolled back.
     *
     * Served from the persisted ledger rather than the release list, so it still answers when the
     * update check is disabled, GitHub is unreachable or the process has just restarted.
     */
    @GetMapping("/update/history")
    public Map<String, Object> updateHistory() {
        return updateService.history();
    }

    /**
     * Turns the automatic behaviours on or off without a restart.
     *
     * A PUT rather than a POST because it replaces the three toggles as a set, and it returns the
     * full status payload rather than the settings alone: the card re-renders from one response,
     * which is also what keeps "the switch moved" and "the banner says otherwise" from disagreeing.
     */
    @PutMapping("/update/settings")
    public Map<String, Object> updateSettings(@RequestBody(required = false) Map<String, Object> body) {
        return updateService.saveSettings(body);
    }

    /**
     * Status of the supervised restart, for a client that only wants that part.
     *
     * The same block is embedded in {@code GET /api/update}; this endpoint exists so the
     * post-restart poll can be one small request instead of the whole release list.
     */
    @GetMapping("/update/restart")
    public Map<String, Object> restartStatus() {
        return updateService.restartState();
    }

    /**
     * Progress of the running download/apply pipeline, for the poll the card runs while busy.
     *
     * Separate from {@code GET /api/update} for the same reason as the restart block: a background
     * download lasts tens of seconds and the card wants a byte count twice a second, while the full
     * payload lists the downloads directory and re-derives every release flag. Polling the heavy
     * one at that rate would turn "watching a progress bar" into load on the machine doing the
     * analysis.
     */
    @GetMapping("/update/progress")
    public Map<String, Object> updateProgress() {
        return updateService.progressState();
    }

    // ------------------------------------------------------------- filesystem

    /**
     * Folder listing for the local-directory picker.
     *
     * A browser file input hands back relative names only, so the picker has to be driven by the
     * local service. Only directory names are returned - no file contents are read.
     */
    @GetMapping("/fs/dirs")
    public Map<String, Object> listDirs(@RequestParam(required = false) String path,
                                        @RequestParam(defaultValue = "false") boolean hidden) {
        return pathBrowser.browse(path, hidden);
    }

    // ------------------------------------------------------------------ sources

    @GetMapping("/sources")
    public List<RepoSource> sources() {
        return store.sources();
    }

    /**
     * Tags currently in use across the registry, with counts.
     *
     * Backs both the sidebar grouping and the autocomplete in the source form, so it answers with
     * the spelling that is actually stored rather than a client-side guess.
     */
    @GetMapping("/tags")
    public List<Map<String, Object>> tags() {
        return store.tagCounts();
    }

    /**
     * Downloads the whole registry as a JSON file.
     *
     * The filename carries a timestamp because the usual destination is a shared folder or a chat
     * message, where two exports of the same day must not overwrite each other.
     */
    @GetMapping("/sources/export")
    public ResponseEntity<Map<String, Object>> exportSources() {
        Map<String, Object> payload = SourceTransfer.exportPayload(store.sources(), java.time.Instant.now());
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now());
        String filename = "code-association-sources-" + stamp + ".json";
        // The registry file itself is the payload, so only its size is logged: it is the answer to
        // "what was in the file I sent" without writing a list of locations into the log.
        log.info(LogEvents.of("source.export", "exported", exportedCount(payload), "file", filename));
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(payload);
    }

    /**
     * Imports a previously exported registry.
     *
     * Merges by type+location: an entry that already exists keeps its row, gains the incoming tags
     * and takes the incoming branch/token when those are present. Local paths are not required to
     * exist, because carrying a registry to another machine is the main use of the feature; a
     * missing path is recorded as a MISSING status instead of failing the import.
     */
    @PostMapping("/sources/import")
    public Map<String, Object> importSources(@RequestBody Object body) {
        SourceTransfer.Sink sink = new SourceTransfer.Sink() {
            @Override
            public RepoSource find(String type, String location) {
                return store.findByTypeAndLocation(type, location);
            }

            @Override
            public RepoSource save(RepoSource source) {
                return store.saveSource(source);
            }
        };
        Map<String, Object> result = SourceTransfer.importPayload(body, sink);
        // One line per import: the counts are what tells a merge that quietly updated everything
        // apart from an import that created new rows, and "skipped" is where the per-row errors are.
        log.info(LogEvents.of("source.import",
                "imported", number(result.get("created")) + number(result.get("updated")),
                "created", number(result.get("created")),
                "updated", number(result.get("updated")),
                "skipped", number(result.get("skipped")),
                "total", number(result.get("total"))));
        return result;
    }

    /** Size of the exported registry, without assuming the payload is still shaped like a list. */
    private static int exportedCount(Map<String, Object> payload) {
        Object sources = payload.get("sources");
        return sources instanceof List<?> list ? list.size() : 0;
    }

    /** Lenient integer read for the counters in an import result map. */
    private static int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    @PostMapping("/sources")
    public RepoSource createSource(@RequestBody RepoSource source) {
        if (source.type == null || source.type.isBlank()) {
            source.type = "LOCAL_DIR";
        }
        if (source.location == null || source.location.isBlank()) {
            throw new IllegalArgumentException("location is required");
        }
        source.type = source.type.toUpperCase();
        source.tags = SourceTags.normalize(source.tags);
        requireLocalDirectory(source);
        // The name is only a label: derive it from the location instead of asking for it.
        SourceNames.withDerivedName(source);
        RepoSource saved = store.saveSource(source);
        // Credentials in a location URL are masked, and credentialEnv is only ever the name of an
        // environment variable - the value lives in the operator's environment, never here.
        log.info(LogEvents.of("source.create",
                "sourceId", saved.id, "source", saved.name, "type", saved.type,
                "location", GitSupport.maskCredentials(saved.location),
                "credentialEnv", saved.credentialEnv));
        return saved;
    }

    @PutMapping("/sources/{id}")
    public RepoSource updateSource(@PathVariable long id, @RequestBody RepoSource source) {
        RepoSource existing = store.source(id);
        if (existing == null) {
            throw new IllegalArgumentException("unknown source: " + id);
        }
        if (source.name != null && !source.name.isBlank()) {
            existing.name = source.name.strip();
        }
        existing.type = source.type == null ? existing.type : source.type.toUpperCase();
        existing.location = source.location == null ? existing.location : source.location;
        existing.defaultRef = source.defaultRef;
        existing.credentialEnv = source.credentialEnv;
        // A PUT is a full replacement, so an empty list is how the UI clears the tags. A null
        // field means "this client does not manage tags" and must leave the stored list alone.
        if (source.tags != null) {
            existing.tags = SourceTags.normalize(source.tags);
        }
        requireLocalDirectory(existing);
        SourceNames.withDerivedName(existing);
        // requireLocalDirectory() rejects an unusable local path, so this mostly confirms what it
        // just checked. The row still has to be re-judged explicitly: a source imported on this
        // machine while its directory was absent carries that import's MISSING verdict, and without
        // this the list would keep showing 目录缺失 - with the import's explanation - for a source
        // the user has just pointed at the right place. Judging again instead of assuming OK also
        // covers the directory disappearing between the two checks.
        SourceTransfer.markAvailability(existing, "编辑时");
        RepoSource saved = store.saveSource(existing);
        log.info(LogEvents.of("source.update",
                "sourceId", saved.id, "source", saved.name, "type", saved.type,
                "location", GitSupport.maskCredentials(saved.location),
                "credentialEnv", saved.credentialEnv));
        return saved;
    }

    /**
     * Local sources must point at a directory that exists right now: saving a typo silently would
     * only surface as a failed analysis later. Remote sources are validated on clone instead.
     */
    private void requireLocalDirectory(RepoSource source) {
        if (!"LOCAL_DIR".equals(source.type) && !"LOCAL_GIT".equals(source.type)) {
            return;
        }
        java.nio.file.Path path = PathBrowser.resolve(source.location);
        if (!java.nio.file.Files.exists(path)) {
            throw new IllegalArgumentException("目录不存在：" + path);
        }
        if (!java.nio.file.Files.isDirectory(path)) {
            throw new IllegalArgumentException("不是文件夹：" + path);
        }
        if ("LOCAL_GIT".equals(source.type) && !java.nio.file.Files.isDirectory(path.resolve(".git"))) {
            throw new IllegalArgumentException("不是 Git 仓库（缺少 .git）：" + path);
        }
        source.location = path.toString();
    }

    /**
     * Deletes a source together with everything derived from it.
     *
     * Analyses are removed as well: they snapshot the source name, so keeping them would leave
     * reports on a code base the user has just removed. For remote sources the cached clone is
     * dropped too; the configured location itself is never touched.
     *
     * A source with a run in flight is refused, because the worker would write its result back
     * after the rows are gone.
     */
    @DeleteMapping("/sources/{id}")
    public Map<String, Object> deleteSource(@PathVariable long id) {
        RepoSource source = store.source(id);
        if (source == null) {
            throw new IllegalArgumentException("unknown source: " + id);
        }
        boolean running = store.analysesOfSource(id).stream()
                .anyMatch(a -> Analysis.RUNNING.equals(a.status) || Analysis.PENDING.equals(a.status));
        if (running) {
            throw new IllegalArgumentException("该代码源还有分析正在进行，请等待完成后再删除");
        }
        int analyses = store.deleteAnalysesOfSource(id);
        store.deleteSource(id);
        boolean cacheDeleted = false;
        try {
            cacheDeleted = preparer.deleteCache(source);
        } catch (java.io.IOException e) {
            // The registry row is already gone; a leftover cache directory is harmless.
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", true);
        out.put("analyses", analyses);
        out.put("cacheDeleted", cacheDeleted);
        // Deleting a program removes its analyses too, so the count belongs in the log: it is how
        // "the reports for X disappeared" gets explained months later.
        log.info(LogEvents.of("source.delete",
                "sourceId", id, "source", source.name, "type", source.type,
                "analyses", analyses, "cacheDeleted", cacheDeleted));
        return out;
    }

    /**
     * Branches of a location that is not saved yet, so the source form can offer a dropdown as
     * soon as a path or URL is entered instead of asking for a branch name by hand.
     */
    @GetMapping("/refs")
    public Map<String, Object> refsForLocation(@RequestParam(required = false) String type,
                                               @RequestParam(required = false) String location,
                                               @RequestParam(required = false) String credentialEnv) {
        return preparer.refsForLocation(type, location, credentialEnv);
    }

    /**
     * Commits a source can be compared over, so the run form offers a picker instead of asking for
     * two SHAs by hand.
     *
     * Two shapes from one endpoint on purpose: with only {@code ref} it answers "the newest commits
     * of this branch" (what the dropdown lists), and with {@code base} + {@code target} it answers
     * "the commits this range would cover" (what the preview shows). The response shape is the same
     * either way, so the client has one thing to render.
     *
     * A repository problem never becomes an HTTP error here: the caller is a form field, and an
     * empty list plus a readable {@code note} keeps the rest of the form usable.
     */
    @GetMapping("/sources/{id}/commits")
    public Map<String, Object> commits(@PathVariable long id,
                                       @RequestParam(required = false) String ref,
                                       @RequestParam(required = false) String base,
                                       @RequestParam(required = false) String target,
                                       @RequestParam(defaultValue = "30") int limit) {
        RepoSource source = store.source(id);
        if (source == null) {
            throw new IllegalArgumentException("unknown source: " + id);
        }
        // target wins over ref when both are given: a range request always names its end, and the
        // branch is then only the fallback for the "which branch am I looking at" case.
        String headRef = Texts.isBlank(target) ? ref : target;
        return preparer.commits(source, base, headRef, limit);
    }

    /** Branches and tags of a source, so the UI can offer real refs instead of free text. */
    @GetMapping("/sources/{id}/refs")
    public Map<String, Object> refs(@PathVariable long id) {
        RepoSource source = store.source(id);
        if (source == null) {
            throw new IllegalArgumentException("unknown source: " + id);
        }
        try {
            return preparer.refs(source);
        } catch (Exception e) {
            return Map.of("branches", List.of(), "tags", List.of(), "defaultRef", "",
                    "note", "cannot read refs: " + e.getMessage());
        }
    }

    // --------------------------------------------------------------- analyses

    @PostMapping("/analyses")
    public Analysis analyze(@RequestBody AnalysisService.RunRequest request) {
        if (request.sourceId <= 0) {
            throw new IllegalArgumentException("sourceId is required");
        }
        return analysisService.submit(request);
    }

    /**
     * Programs that can be compared against: every other registered source.
     *
     * Kept separate from /sources so the run form can ask for exactly this list, including the
     * flag that says whether a source can be indexed at all.
     */
    @GetMapping("/peers")
    public List<Map<String, Object>> peers(@RequestParam(required = false) Long exclude) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RepoSource source : store.sources()) {
            if (exclude != null && source.id == exclude) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", source.id);
            row.put("name", source.name);
            row.put("type", source.type);
            row.put("location", source.location);
            out.add(row);
        }
        return out;
    }

    /**
     * 摘要列的解析。
     *
     * 列表接口一次返回 30 行，原来每行都新建一个匿名 TypeReference；类型信息是常量，
     * 复用同一个实例即可，行为完全一致。
     */
    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> SUMMARY_TYPE =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    private Map<String, Object> summaryOf(Analysis a) {
        return a.summaryJson == null ? null : Json.read(a.summaryJson, SUMMARY_TYPE);
    }

    @GetMapping("/analyses")
    public List<Map<String, Object>> analyses(@RequestParam(defaultValue = "30") int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Analysis a : store.analyses(limit)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.id);
            row.put("sourceId", a.sourceId);
            row.put("sourceName", a.sourceName);
            row.put("sourceType", a.sourceType);
            row.put("mode", a.mode);
            row.put("baseRef", a.baseRef);
            row.put("targetRef", a.targetRef);
            row.put("baseCommit", a.baseCommit);
            row.put("headCommit", a.headCommit);
            row.put("status", a.status);
            row.put("message", a.message);
            row.put("createdAt", a.createdAt);
            row.put("finishedAt", a.finishedAt);
            row.put("durationMs", a.durationMs);
            row.put("progressStage", a.progressStage);
            row.put("progressPercent", a.progressPercent);
            row.put("progressLabel", a.progressLabel);
            row.put("progressDetail", a.progressDetail);
            row.put("progressCompleted", a.progressCompleted);
            row.put("progressSkipped", a.progressSkipped);
            row.put("summary", summaryOf(a));
            out.add(row);
        }
        return out;
    }

    @GetMapping("/analyses/{id}")
    public ResponseEntity<Map<String, Object>> analysis(@PathVariable long id,
                                                        @RequestParam(defaultValue = "false") boolean withReport) {
        // report_json 是 MB 级的 blob：只有调用方明确要报告时才把它读出来，
        // 否则一次状态查询会把整份报告从 SQLite 里流出来。
        Analysis a = withReport ? store.analysisWithReport(id) : store.analysis(id);
        if (a == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not found"));
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", a.id);
        row.put("sourceName", a.sourceName);
        row.put("sourceType", a.sourceType);
        row.put("mode", a.mode);
        row.put("baseRef", a.baseRef);
        row.put("targetRef", a.targetRef);
        row.put("baseCommit", a.baseCommit);
        row.put("headCommit", a.headCommit);
        row.put("status", a.status);
        row.put("message", a.message);
        row.put("createdAt", a.createdAt);
        row.put("finishedAt", a.finishedAt);
        row.put("durationMs", a.durationMs);
        row.put("progressStage", a.progressStage);
        row.put("progressPercent", a.progressPercent);
        row.put("progressLabel", a.progressLabel);
        row.put("progressDetail", a.progressDetail);
        row.put("progressCompleted", a.progressCompleted);
        row.put("progressSkipped", a.progressSkipped);
        row.put("summary", summaryOf(a));
        if (withReport && a.reportJson != null) {
            row.put("report", Json.readMap(a.reportJson));
        }
        return ResponseEntity.ok(row);
    }

    @GetMapping("/analyses/{id}/report")
    public ResponseEntity<ImpactReport> report(@PathVariable long id) {
        ImpactReport report = analysisService.report(id);
        if (report == null) {
            Analysis a = store.analysis(id);
            if (a == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            return ResponseEntity.status(HttpStatus.ACCEPTED).build();
        }
        return ResponseEntity.ok(report);
    }
}