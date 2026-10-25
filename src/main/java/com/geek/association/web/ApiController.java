package com.geek.association.web;

import com.geek.association.config.AppProperties;
import com.geek.association.model.Analysis;
import com.geek.association.model.RepoSource;
import com.geek.association.report.ImpactReport;
import com.geek.association.service.AnalysisService;
import com.geek.association.source.PathBrowser;
import com.geek.association.source.RepositoryPreparer;
import com.geek.association.source.SourceNames;
import com.geek.association.store.Store;
import com.geek.association.util.Json;
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

    private final Store store;
    private final AnalysisService analysisService;
    private final RepositoryPreparer preparer;
    private final AppProperties properties;
    private final PathBrowser pathBrowser;

    public ApiController(Store store, AnalysisService analysisService, RepositoryPreparer preparer,
                         AppProperties properties, PathBrowser pathBrowser) {
        this.store = store;
        this.analysisService = analysisService;
        this.preparer = preparer;
        this.properties = properties;
        this.pathBrowser = pathBrowser;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("dataDir", properties.dataPath().toString());
        out.put("maxDepth", properties.getMaxDepth());
        out.put("maxFiles", properties.getMaxFiles());
        out.put("aiEnabled", properties.getAi().isEnabled());
        return out;
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

    @PostMapping("/sources")
    public RepoSource createSource(@RequestBody RepoSource source) {
        if (source.type == null || source.type.isBlank()) {
            source.type = "LOCAL_DIR";
        }
        if (source.location == null || source.location.isBlank()) {
            throw new IllegalArgumentException("location is required");
        }
        source.type = source.type.toUpperCase();
        requireLocalDirectory(source);
        // The name is only a label: derive it from the location instead of asking for it.
        SourceNames.withDerivedName(source);
        return store.saveSource(source);
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
        requireLocalDirectory(existing);
        SourceNames.withDerivedName(existing);
        return store.saveSource(existing);
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
            row.put("summary", a.summaryJson == null ? null : Json.readMap(a.summaryJson));
            out.add(row);
        }
        return out;
    }

    @GetMapping("/analyses/{id}")
    public ResponseEntity<Map<String, Object>> analysis(@PathVariable long id,
                                                        @RequestParam(defaultValue = "false") boolean withReport) {
        Analysis a = store.analysis(id);
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
        row.put("summary", a.summaryJson == null ? null : Json.readMap(a.summaryJson));
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
