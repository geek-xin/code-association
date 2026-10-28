package com.geek.association.store;

import com.geek.association.config.AppProperties;
import com.geek.association.model.Analysis;
import com.geek.association.model.RepoSource;
import com.geek.association.util.Json;
import com.geek.association.util.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite persistence for sources and analysis runs.
 *
 * The code index itself is not persisted per revision: it is rebuilt from the snapshot when a run
 * starts, which keeps the schema small and avoids stale indexes. Only the source registry and the
 * finished reports (plus the raw summary JSON) are stored.
 */
@Component
public class Store {

    private static final Logger log = LoggerFactory.getLogger(Store.class);

    private final AppProperties properties;
    private volatile boolean initialized;

    public Store(AppProperties properties) {
        this.properties = properties;
    }

    public Path dataDir() {
        Path dir = properties.dataPath();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create data dir " + dir, e);
        }
        return dir;
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dataDir().resolve("code-association.db"));
    }

    public synchronized void init() {
        if (initialized) {
            return;
        }
        long started = System.currentTimeMillis();
        try (Connection c = connection(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS repo_source (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        location TEXT NOT NULL,
                        default_ref TEXT,
                        credential_env TEXT,
                        local_path TEXT,
                        status TEXT,
                        message TEXT,
                        created_at TEXT,
                        last_synced_at TEXT,
                        tags TEXT
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS analysis (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        source_id INTEGER NOT NULL,
                        source_name TEXT,
                        source_type TEXT,
                        mode TEXT,
                        base_ref TEXT,
                        target_ref TEXT,
                        base_commit TEXT,
                        head_commit TEXT,
                        status TEXT,
                        message TEXT,
                        created_at TEXT,
                        finished_at TEXT,
                        duration_ms INTEGER DEFAULT 0,
                        summary_json TEXT,
                        report_json TEXT,
                        progress_stage TEXT,
                        progress_percent INTEGER DEFAULT 0,
                        progress_label TEXT,
                        progress_detail TEXT,
                        progress_completed TEXT,
                        progress_skipped TEXT
                    )
                    """);
            migrate(st);
            initialized = true;
            // init() 被每个 public 方法调用，但只有真正建表/迁移的这一次才值得一行 INFO：
            // 它同时回答"数据库在哪"（绝对路径，日志目录之外唯一需要人肉找的文件）和
            // "启动时 schema 工作花了多久"。
            log.info(LogEvents.of("store.init",
                    "db", dataDir().resolve("code-association.db").toAbsolutePath(),
                    "durationMs", LogEvents.msSince(started)));
        } catch (SQLException e) {
            throw new IllegalStateException("sqlite init failed", e);
        }
    }

    /**
     * Brings an existing database up to the current schema.
     *
     * Columns are added in place rather than by recreating tables: users have analysis history in
     * these rows, and "upgrade the app" must never mean "lose last month's reports".
     */
    private void migrate(Statement st) throws SQLException {
        // Existing databases predate the progress columns.
        List<String> analysisColumns = columnsOf(st, "analysis");
        addColumnIfMissing(st, "analysis", analysisColumns, "progress_stage", "TEXT");
        addColumnIfMissing(st, "analysis", analysisColumns, "progress_percent", "INTEGER DEFAULT 0");
        addColumnIfMissing(st, "analysis", analysisColumns, "progress_label", "TEXT");
        addColumnIfMissing(st, "analysis", analysisColumns, "progress_detail", "TEXT");
        addColumnIfMissing(st, "analysis", analysisColumns, "progress_completed", "TEXT");
        addColumnIfMissing(st, "analysis", analysisColumns, "progress_skipped", "TEXT");

        // Tag classification arrived with 0.5.0; older registries have no column for it.
        List<String> sourceColumns = columnsOf(st, "repo_source");
        addColumnIfMissing(st, "repo_source", sourceColumns, "tags", "TEXT");

        // analysesOfSource() filters on source_id and orders by id, and the UI runs it on every
        // source click (plus once per program per scheduled tick). Without an index SQLite scans
        // the whole analysis table - reading every row, including its summary_json - to answer a
        // question about one program. Indexing (source_id, id) also satisfies the ORDER BY id DESC
        // from the index itself, so the sort disappears too. Idempotent, like the columns above.
        if (!indexExists(st, "idx_analysis_source_id")) {
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_analysis_source_id ON analysis(source_id, id)");
            log.info(LogEvents.of("store.init",
                    "migration", "create index idx_analysis_source_id"));
        }
    }

    /**
     * Whether a named index already exists.
     *
     * Needed only so the migration line above is logged on the one startup that actually creates
     * it: {@code CREATE INDEX IF NOT EXISTS} succeeds silently either way, so its update count
     * cannot tell the two cases apart.
     */
    private boolean indexExists(Statement st, String name) throws SQLException {
        try (ResultSet rs = st.executeQuery(
                "SELECT 1 FROM sqlite_master WHERE type='index' AND name='" + name + "'")) {
            return rs.next();
        }
    }

    private List<String> columnsOf(Statement st, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        return columns;
    }

    private void addColumnIfMissing(Statement st, String table, List<String> columns, String name,
                                    String type) throws SQLException {
        if (!columns.contains(name)) {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + name + " " + type);
            // 一次性事件：升级后的第一次启动才会走这里，记下来才能解释"为什么这次启动比平时慢"。
            log.info(LogEvents.of("store.init",
                    "migration", "add column " + table + "." + name));
        }
    }

    // ------------------------------------------------------------------ sources

    public RepoSource saveSource(RepoSource source) {
        init();
        try (Connection c = connection()) {
            if (source.id == 0) {
                source.createdAt = RepoSource.now();
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO repo_source (name, type, location, default_ref, credential_env, local_path,
                                                 status, message, created_at, last_synced_at, tags)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, source.name);
                    ps.setString(2, source.type);
                    ps.setString(3, source.location);
                    ps.setString(4, source.defaultRef);
                    ps.setString(5, source.credentialEnv);
                    ps.setString(6, source.localPath);
                    ps.setString(7, source.status);
                    ps.setString(8, source.message);
                    ps.setString(9, source.createdAt);
                    ps.setString(10, source.lastSyncedAt);
                    ps.setString(11, Json.write(tagsOf(source)));
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) {
                            source.id = keys.getLong(1);
                        }
                    }
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE repo_source SET name=?, type=?, location=?, default_ref=?, credential_env=?,
                        local_path=?, status=?, message=?, last_synced_at=?, tags=? WHERE id=?
                        """)) {
                    ps.setString(1, source.name);
                    ps.setString(2, source.type);
                    ps.setString(3, source.location);
                    ps.setString(4, source.defaultRef);
                    ps.setString(5, source.credentialEnv);
                    ps.setString(6, source.localPath);
                    ps.setString(7, source.status);
                    ps.setString(8, source.message);
                    ps.setString(9, source.lastSyncedAt);
                    ps.setString(10, Json.write(tagsOf(source)));
                    ps.setLong(11, source.id);
                    ps.executeUpdate();
                }
            }
            return source;
        } catch (SQLException e) {
            throw new IllegalStateException("save source failed", e);
        }
    }

    /**
     * Writes back only what a finished analysis learned about its source.
     *
     * The run holds the copy read at submit time, and an analysis takes minutes: the user may
     * rename the program, retag it or point it at another branch meanwhile. Handing that copy to
     * {@link #saveSource} would rewrite every column and silently revert those edits. So only the
     * three fields this run actually produces are set, and every other column keeps whatever the
     * user last wrote.
     */
    public void updateSourceSyncState(long id, String status, String message, String lastSyncedAt) {
        init();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("""
                UPDATE repo_source SET status=?, message=?, last_synced_at=? WHERE id=?
                """)) {
            ps.setString(1, status);
            ps.setString(2, message);
            ps.setString(3, lastSyncedAt);
            ps.setLong(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("update source sync state failed", e);
        }
    }

    public List<RepoSource> sources() {
        init();
        List<RepoSource> out = new ArrayList<>();
        try (Connection c = connection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM repo_source ORDER BY id DESC")) {
            while (rs.next()) {
                out.add(readSource(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("list sources failed", e);
        }
        return out;
    }

    public RepoSource source(long id) {
        init();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("SELECT * FROM repo_source WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readSource(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("read source failed", e);
        }
    }

    public void deleteSource(long id) {
        init();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("DELETE FROM repo_source WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("delete source failed", e);
        }
    }

    /** Analyses that still belong to a source, newest first. */
    public List<Analysis> analysesOfSource(long sourceId) {
        init();
        List<Analysis> out = new ArrayList<>();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT " + ANALYSIS_COLUMNS + " FROM analysis WHERE source_id=? ORDER BY id DESC")) {
            ps.setLong(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readAnalysis(rs, false));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("list analyses of source failed", e);
        }
        return out;
    }

    /**
     * Removes every analysis of a source and returns how many rows were deleted.
     *
     * Analyses carry a snapshot of the source name, so leaving them behind would keep reporting
     * on a code base the user has explicitly removed.
     */
    public int deleteAnalysesOfSource(long sourceId) {
        init();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM analysis WHERE source_id=?")) {
            ps.setLong(1, sourceId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("delete analyses of source failed", e);
        }
    }

    private RepoSource readSource(ResultSet rs) throws SQLException {
        RepoSource s = new RepoSource();
        s.id = rs.getLong("id");
        s.name = rs.getString("name");
        s.type = rs.getString("type");
        s.location = rs.getString("location");
        s.defaultRef = rs.getString("default_ref");
        s.credentialEnv = rs.getString("credential_env");
        s.localPath = rs.getString("local_path");
        s.status = rs.getString("status");
        s.message = rs.getString("message");
        s.createdAt = rs.getString("created_at");
        s.lastSyncedAt = rs.getString("last_synced_at");
        s.tags = readTags(rs.getString("tags"));
        return s;
    }

    /**
     * Every tag currently in use, with how many sources carry it, most used first.
     *
     * This backs the autocomplete and the sidebar grouping. It is computed in memory because the
     * registry is a hand-managed list of programs, not a table that grows without bound; a SQL
     * JSON query would buy nothing here and would tie the schema to SQLite's JSON extension.
     */
    public List<Map<String, Object>> tagCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> spelling = new LinkedHashMap<>();
        for (RepoSource source : sources()) {
            for (String tag : tagsOf(source)) {
                String key = tag.toLowerCase(java.util.Locale.ROOT);
                counts.merge(key, 1, Integer::sum);
                spelling.putIfAbsent(key, tag);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        counts.entrySet().stream()
                .sorted((a, b) -> {
                    int byCount = b.getValue() - a.getValue();
                    return byCount != 0 ? byCount : spelling.get(a.getKey()).compareTo(spelling.get(b.getKey()));
                })
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("tag", spelling.get(e.getKey()));
                    row.put("count", e.getValue());
                    out.add(row);
                });
        return out;
    }

    /**
     * Finds a source by type and location, ignoring the differences a human would ignore.
     *
     * Import uses this to decide "update" from "create". The comparison happens in memory because
     * the location needs normalisation (trailing slash, surrounding whitespace) that a plain SQL
     * equality would treat as a different source, producing a duplicate on every re-import.
     */
    public RepoSource findByTypeAndLocation(String type, String location) {
        String wantedType = type == null ? "" : type.strip().toUpperCase(java.util.Locale.ROOT);
        String wantedLocation = com.geek.association.transfer.SourceTransfer.normalizeLocation(location);
        for (RepoSource source : sources()) {
            String sourceType = source.type == null ? "" : source.type.strip().toUpperCase(java.util.Locale.ROOT);
            if (!sourceType.equals(wantedType)) {
                continue;
            }
            if (com.geek.association.transfer.SourceTransfer.normalizeLocation(source.location)
                    .equals(wantedLocation)) {
                return source;
            }
        }
        return null;
    }

    private List<String> tagsOf(RepoSource source) {
        return source == null || source.tags == null ? List.of() : source.tags;
    }

    /**
     * Tags are stored as a JSON array in one column. A malformed value degrades to "no tags"
     * rather than failing the read: losing a filing label is recoverable, losing the source is not.
     */
    private List<String> readTags(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return Json.read(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // ----------------------------------------------------------------- analyses

    /**
     * Every column except the report blob.
     *
     * Spelled out instead of {@code SELECT *} because report_json is megabytes on a large review
     * queue, and a reader that only wants status or progress must not pay for streaming it. Every
     * analysis query goes through this list; only {@link #analysisWithReport} adds the blob.
     */
    private static final String ANALYSIS_COLUMNS =
            "id, source_id, source_name, source_type, mode, base_ref, target_ref, "
                    + "base_commit, head_commit, status, message, created_at, finished_at, duration_ms, "
                    + "progress_stage, progress_percent, progress_label, progress_detail, "
                    + "progress_completed, progress_skipped, summary_json";

    public Analysis createAnalysis(Analysis analysis) {
        init();
        analysis.createdAt = Instant.now().toString();
        analysis.status = Analysis.RUNNING;
        analysis.progressStage = "PENDING";
        analysis.progressPercent = 0;
        analysis.progressLabel = "等待分析";
        analysis.progressDetail = "任务已创建";
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO analysis (source_id, source_name, source_type, mode, base_ref, target_ref,
                                      base_commit, head_commit, status, message, created_at,
                                      progress_stage, progress_percent, progress_label, progress_detail,
                                      progress_completed, progress_skipped)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, analysis.sourceId);
            ps.setString(2, analysis.sourceName);
            ps.setString(3, analysis.sourceType);
            ps.setString(4, analysis.mode);
            ps.setString(5, analysis.baseRef);
            ps.setString(6, analysis.targetRef);
            ps.setString(7, analysis.baseCommit);
            ps.setString(8, analysis.headCommit);
            ps.setString(9, analysis.status);
            ps.setString(10, analysis.message);
            ps.setString(11, analysis.createdAt);
            ps.setString(12, analysis.progressStage);
            ps.setInt(13, analysis.progressPercent);
            ps.setString(14, analysis.progressLabel);
            ps.setString(15, analysis.progressDetail);
            ps.setString(16, Json.write(analysis.progressCompleted));
            ps.setString(17, Json.write(analysis.progressSkipped));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    analysis.id = keys.getLong(1);
                }
            }
            return analysis;
        } catch (SQLException e) {
            throw new IllegalStateException("create analysis failed", e);
        }
    }

    /**
     * Writes only the live progress fields. The report and summary stay untouched, so the status
     * endpoint can poll this cheaply while the worker is still running.
     */
    public void updateProgress(long id, String stage, int percent, String label, String detail,
                               List<String> completed, List<String> skipped) {
        init();
        int clamped = Math.max(0, Math.min(100, percent));
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("""
                UPDATE analysis SET progress_stage=?, progress_percent=?, progress_label=?,
                    progress_detail=?, progress_completed=?, progress_skipped=?
                WHERE id=?
                """)) {
            ps.setString(1, stage);
            ps.setInt(2, clamped);
            ps.setString(3, label);
            ps.setString(4, detail);
            ps.setString(5, Json.write(completed == null ? List.of() : completed));
            ps.setString(6, Json.write(skipped == null ? List.of() : skipped));
            ps.setLong(7, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("update analysis progress failed", e);
        }
    }

    public void finishAnalysis(long id, String status, String message, long durationMs,
                               Object summary, Object report) {
        finishAnalysis(id, status, message, durationMs, summary, report, null, null);
    }

    /**
     * Finishes a run and stores the commits it actually compared.
     *
     * The commits are resolved while the run is in flight; without persisting them the daily scan
     * cannot tell "already analysed this commit" from "never analysed", and would re-run every
     * program on every tick.
     */
    public void finishAnalysis(long id, String status, String message, long durationMs,
                               Object summary, Object report, String baseCommit, String headCommit) {
        init();
        Analysis current = analysis(id);
        List<String> completed = current == null ? new ArrayList<>() : current.progressCompleted;
        List<String> skipped = current == null ? new ArrayList<>() : current.progressSkipped;
        if (Analysis.DONE.equals(status)) {
            for (String stage : List.of("PREPARE", "DIFF", "INDEX_HEAD", "INDEX_BASE",
                    "DETECT", "IMPACT", "REPORT")) {
                if (!completed.contains(stage)) {
                    completed.add(stage);
                }
            }
        }
        String stage = Analysis.DONE.equals(status) ? "DONE" : Analysis.FAILED;
        int percent = Analysis.DONE.equals(status) ? 100 : 0;
        String label = Analysis.DONE.equals(status) ? "分析完成" : "分析失败";
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("""
                UPDATE analysis SET status=?, message=?, finished_at=?, duration_ms=?, summary_json=?, report_json=?,
                    progress_stage=?, progress_percent=?, progress_label=?, progress_detail=?,
                    progress_completed=?, progress_skipped=?,
                    base_commit=COALESCE(?, base_commit), head_commit=COALESCE(?, head_commit)
                WHERE id=?
                """)) {
            ps.setString(1, status);
            ps.setString(2, message);
            ps.setString(3, Instant.now().toString());
            ps.setLong(4, durationMs);
            ps.setString(5, summary == null ? null : Json.write(summary));
            ps.setString(6, report == null ? null : Json.write(report));
            ps.setString(7, stage);
            ps.setInt(8, percent);
            ps.setString(9, label);
            ps.setString(10, message == null ? "" : message);
            ps.setString(11, Json.write(completed));
            ps.setString(12, Json.write(skipped));
            ps.setString(13, baseCommit);
            ps.setString(14, headCommit);
            ps.setLong(15, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("finish analysis failed", e);
        }
    }

    /**
     * Rewrites only the report blob of a finished analysis.
     *
     * Exists for the AI补生成 endpoint: {@link #finishAnalysis} takes the status, the timestamps and
     * the progress lists as well, so using it to attach one optional section would re-stamp a run
     * that finished hours ago ("分析完成" again, a new duration) just to add a paragraph. Touching a
     * single column is the narrow write, and the caller is responsible for the matching cache
     * invalidation.
     *
     * @return false when the row does not exist
     */
    public boolean updateReportJson(long id, Object report) {
        init();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE analysis SET report_json=? WHERE id=?")) {
            ps.setString(1, report == null ? null : Json.write(report));
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("update analysis report failed", e);
        }
    }

    public List<Analysis> analyses(int limit) {
        init();
        List<Analysis> out = new ArrayList<>();
        // Explicit columns: report_json can be megabytes, and SELECT * would stream every blob
        // even though the list view never reads it.
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT " + ANALYSIS_COLUMNS + " FROM analysis ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readAnalysis(rs, false));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("list analyses failed", e);
        }
        return out;
    }

    /**
     * Marks analyses that never finished as failed.
     *
     * A run lives only in a worker thread. If the process is killed mid-run (or a stage hangs
     * forever), the row stays RUNNING and the scheduler keeps treating that program as busy,
     * blocking every future scan. Startup recovery is what makes the schedule self-healing.
     *
     * @param olderThanMinutes only touch rows older than this, so a live run is never disturbed
     * @return how many rows were recovered
     */
    public int recoverStaleAnalyses(int olderThanMinutes) {
        init();
        // A caller that just started knows no run can be alive, and passes 0 to mean "all of
        // them". Treating 0 as "one minute ago" would silently leave fresh rows behind.
        boolean useCutoff = olderThanMinutes > 0;
        String cutoff = useCutoff ? Instant.now().minusSeconds(olderThanMinutes * 60L).toString() : null;
        String sql = "UPDATE analysis SET status=?, message=?, finished_at=? WHERE status IN (?,?)"
                + (useCutoff ? " AND (created_at IS NULL OR created_at < ?)" : "");
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, Analysis.FAILED);
            ps.setString(2, "分析进程在完成前中断（服务重启或超时），已标记为失败");
            ps.setString(3, Instant.now().toString());
            ps.setString(4, Analysis.RUNNING);
            ps.setString(5, Analysis.PENDING);
            if (useCutoff) {
                ps.setString(6, cutoff);
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("recover stale analyses failed", e);
        }
    }

    /**
     * One analysis row, <em>without</em> its report.
     *
     * This is the default read on purpose: status polling, delete checks and the detail page all
     * need the row but not the megabytes of report_json behind it, and {@code SELECT *} made every
     * one of them stream the whole blob out of SQLite. Callers that really need the report must say
     * so by calling {@link #analysisWithReport}.
     */
    public Analysis analysis(long id) {
        init();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT " + ANALYSIS_COLUMNS + " FROM analysis WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readAnalysis(rs, false) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("read analysis failed", e);
        }
    }

    /**
     * One analysis row together with its stored report JSON, for the callers that serve the report.
     *
     * Kept as a separate method rather than a flag on {@link #analysis}: a boolean parameter at
     * every call site is exactly how the blob started being read by readers that never used it.
     */
    public Analysis analysisWithReport(long id) {
        init();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT " + ANALYSIS_COLUMNS + ", report_json FROM analysis WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readAnalysis(rs, true) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("read analysis failed", e);
        }
    }

    private Analysis readAnalysis(ResultSet rs, boolean withReport) throws SQLException {
        Analysis a = new Analysis();
        a.id = rs.getLong("id");
        a.sourceId = rs.getLong("source_id");
        a.sourceName = rs.getString("source_name");
        a.sourceType = rs.getString("source_type");
        a.mode = rs.getString("mode");
        a.baseRef = rs.getString("base_ref");
        a.targetRef = rs.getString("target_ref");
        a.baseCommit = rs.getString("base_commit");
        a.headCommit = rs.getString("head_commit");
        a.status = rs.getString("status");
        a.message = rs.getString("message");
        a.createdAt = rs.getString("created_at");
        a.finishedAt = rs.getString("finished_at");
        a.durationMs = rs.getLong("duration_ms");
        a.progressStage = rs.getString("progress_stage");
        a.progressPercent = rs.getInt("progress_percent");
        a.progressLabel = rs.getString("progress_label");
        a.progressDetail = rs.getString("progress_detail");
        a.progressCompleted = readStringList(rs.getString("progress_completed"));
        a.progressSkipped = readStringList(rs.getString("progress_skipped"));
        a.summaryJson = rs.getString("summary_json");
        if (withReport) {
            a.reportJson = rs.getString("report_json");
        }
        return a;
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return Json.read(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

}
