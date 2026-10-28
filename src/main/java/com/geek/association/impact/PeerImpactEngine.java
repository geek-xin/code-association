package com.geek.association.impact;

import com.geek.association.config.AppProperties;
import com.geek.association.diff.ChangeDetector;
import com.geek.association.parse.DatabaseIdentity;
import com.geek.association.model.ChangeRecord;
import com.geek.association.model.CodeIndex;
import com.geek.association.model.Edge;
import com.geek.association.model.ImpactResult;
import com.geek.association.model.PeerImpact;
import com.geek.association.model.Sym;
import com.geek.association.util.ColumnTypeCompatibility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-program impact: does a change here break something there?
 *
 * Two repositories never share a call graph - they are built separately and only meet at
 * contracts. So instead of trying to merge graphs, this engine matches the contracts that this
 * program changed against the symbols the peer program uses, then runs the ordinary impact
 * propagation <em>inside</em> the peer from those matches.
 *
 * The four contract kinds are exactly the ones the analyzers already model:
 *
 * <ul>
 *   <li>{@code api:} - HTTP endpoints, joined on the normalised path template;</li>
 *   <li>{@code db:table:} / {@code db:column:} - shared schema, joined on the table/column name;</li>
 *   <li>{@code config:} - configuration keys, joined on the key name;</li>
 *   <li>Maven artifacts - the peer declares a dependency on a module of this program.</li>
 * </ul>
 *
 * Only contracts that this program actually changed are considered. A shared endpoint nobody
 * touched is not an impact, which is what keeps the result a delta rather than an inventory.
 */
public class PeerImpactEngine {

    private final AppProperties properties;

    public PeerImpactEngine(AppProperties properties) {
        this.properties = properties;
    }

    /**
     * @param changes  semantic changes detected in this program
     * @param peer     the indexed peer program (head revision)
     * @param peerInfo the peer source record, for labelling
     */
    public PeerImpact analyze(List<ChangeRecord> changes, CodeIndex ownHead, CodeIndex peer, PeerImpact peerInfo) {
        return analyze(changes, ownHead, peer, peerInfo, 1);
    }

    /**
     * Same analysis with a caller-assigned impact id range, so impacts from several peers can be
     * merged into one report without colliding on review keys.
     */
    public PeerImpact analyze(List<ChangeRecord> changes, CodeIndex ownHead, CodeIndex peer,
                              PeerImpact peerInfo, long firstImpactId) {
        PeerImpact out = peerInfo;
        out.indexedFiles = peer.parsedFiles;
        out.indexMillis = peer.buildMillis;

        List<PeerImpact.ContractMatch> matches = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ChangeRecord change : changes) {
            collectApiMatches(change, ownHead, peer, matches, seen);
            collectSqlMatches(change, ownHead, peer, matches, seen, out);
            collectConfigMatches(change, peer, matches, seen);
            collectMessagingMatches(change, ownHead, peer, matches, seen);
        }
        collectArtifactMatches(ownHead, peer, matches, seen);
        out.matches = matches;
        collectColumnBlindSpots(changes, ownHead, peer, out);

        if (matches.isEmpty()) {
            return out;
        }

        // Propagate inside the peer from every matched consumer, reusing the normal engine so a
        // cross-program impact is scored and explained exactly like a local one.
        Map<Long, ChangeRecord> byId = new LinkedHashMap<>();
        for (ChangeRecord change : changes) {
            byId.put(change.id, change);
        }
        List<ChangeRecord> synthetic = new ArrayList<>();
        for (PeerImpact.ContractMatch match : matches) {
            if (match.contractSymbolKey == null) {
                continue; // dependency-only match: reported, but there is nothing to traverse
            }
            ChangeRecord cr = new ChangeRecord();
            // Keep the original change id so the peer impact can be joined back to the report's
            // change list. Several contracts may share the same originating change.
            cr.id = match.changeRecordId;
            // Start at the shared contract node, so the first hop is the peer code that uses it.
            cr.symbolKey = match.contractSymbolKey;
            cr.changeType = match.kind;
            cr.qualifiedName = match.contract;
            cr.semanticSummary = "跨程序契约 " + match.contract + " 发生变化，"
                    + (out.peerSourceName == null ? "对方程序" : out.peerSourceName) + " 正在使用该契约";
            Sym peerConsumer = peer.symbol(match.peerSymbolKey);
            cr.modulePath = peerConsumer != null && peerConsumer.modulePath != null
                    ? peerConsumer.modulePath : match.peerModule;
            cr.filePath = match.peerFile;
            cr.startLine = match.peerLine;
            // Carry the origin change's severity into the peer analysis: a dropped column and an
            // added one are both "a database contract changed", but they are not equally risky.
            ChangeRecord origin = byId.get(match.changeRecordId);
            cr.riskSeed = Math.max(0.5, origin == null ? 0.9 : origin.riskSeed);
            cr.tags.add("CROSS_PROGRAM");
            cr.extra.put("directImpact", "true");
            synthetic.add(cr);
        }
        ImpactGraph graph = new ImpactGraph(peer, peer);
        ImpactEngine.Options options = ImpactEngine.options(properties, false);
        options.maxDepth = Math.max(3, options.maxDepth);
        List<ImpactResult> impacts = new ImpactEngine(properties).analyze(graph, synthetic, options);
        long impactId = firstImpactId;
        for (ImpactResult impact : impacts) {
            impact.id = impactId++;
            impact.peerSourceId = out.peerSourceId;
            impact.peerSourceName = out.peerSourceName;
            impact.peerModule = impact.targetModule;
            impact.targetModule = out.peerSourceName == null || out.peerSourceName.isBlank()
                    ? "其他程序" : out.peerSourceName;
        }
        out.impacts = impacts;

        Set<String> modules = new LinkedHashSet<>();
        for (ImpactResult impact : impacts) {
            modules.add(impact.peerModule == null ? impact.targetModule : impact.peerModule);
            out.maxRisk = Math.max(out.maxRisk, (int) Math.round(impact.riskScore));
            if ("CRITICAL".equals(impact.riskLevel) || "HIGH".equals(impact.riskLevel)) {
                out.highRisk++;
            }
        }
        out.impactedSymbols = impacts.size();
        out.impactedModules = modules.size();
        out.riskLevel = RiskScorer.level(out.maxRisk);
        return out;
    }

    // ---------------------------------------------------------------- contracts

    private void collectApiMatches(ChangeRecord change, CodeIndex ownHead, CodeIndex peer,
                                   List<PeerImpact.ContractMatch> out, Set<String> seen) {
        if (change.symbolKey == null || !change.symbolKey.startsWith("api:")) {
            return;
        }
        String path = change.symbolKey.substring("api:".length());
        // Peer code that calls this endpoint. The peer usually writes the concrete path, so the
        // comparison has to be template-aware rather than a plain string equality.
        for (Sym sym : peer.symbols.values()) {
            if (!"API_ENDPOINT".equals(sym.type)) {
                continue;
            }
            if (!com.geek.association.parse.ApiAnalyzer.pathsMatch(path, sym.meta.getOrDefault("path", ""))) {
                continue;
            }
            for (Edge e : peer.incoming.getOrDefault(sym.key, List.of())) {
                if (!"CALLS_API".equals(e.relation)) {
                    continue;
                }
                add(out, seen, "API", sym.qualifiedName, change, sym.key, e.from,
                        peer, e, e.detail == null ? "HTTP client" : e.detail, 0.9);
            }
        }
    }

    /**
     * Database contracts, matched on the column and not on the table.
     *
     * A shared table is not a contract: two programs routinely read different columns of the same
     * table, and one of them selecting an extra column is invisible to the other. What they do share
     * is a column - its existence, its type and its meaning - so a database delta is expressed as
     * {@code table.column}, and only three kinds of edit produce one:
     *
     * <ul>
     *   <li>a schema change (DDL) touching a column - read and write sides both break;</li>
     *   <li>a change of the set of columns this program <em>writes</em> - only the other side's
     *       readers see different values;</li>
     *   <li>a change of an entity's column mapping - the field itself moved.</li>
     * </ul>
     *
     * Reading an extra column is deliberately absent: how many columns this program selects is its
     * own business, and treating it as a contract change is what used to flood the review queue.
     */
    private void collectSqlMatches(ChangeRecord change, CodeIndex ownHead, CodeIndex peer,
                                   List<PeerImpact.ContractMatch> out, Set<String> seen,
                                   PeerImpact peerInfo) {
        for (DbDelta delta : dbDeltas(change, ownHead, peerInfo)) {
            Sym shared = peer.symbol(delta.symbolKey);
            if (shared == null) {
                continue;
            }
            for (Edge e : peer.incoming.getOrDefault(shared.key, List.of())) {
                String relation = e.relation == null ? "" : e.relation;
                if (!delta.accepts(relation)) {
                    continue;
                }
                Sym consumer = peer.symbol(e.from);
                if (consumer != null && consumer.test) {
                    // A test mapper or test entity is not a production consumer of the contract.
                    continue;
                }
                DbIdentity peerIdentity = peerIdentity(peer, e.from, delta.table);
                if (!DbIdentity.compatible(delta.ownIdentity, peerIdentity)) {
                    peerInfo.dbMismatchSuppressed++;
                    addExample(peerInfo.dbMismatchExamples,
                            delta.contract + "（本程序 " + delta.ownIdentity + "，对方 " + peerIdentity + "）");
                    continue;
                }
                double confidence = delta.confidence;
                boolean uncertainIdentity = !DbIdentity.known(delta.ownIdentity)
                        || !DbIdentity.known(peerIdentity)
                        || delta.ownIdentity.database != peerIdentity.database;
                if (uncertainIdentity) {
                    // Unknown, or a database name compared with a schema name: not a mismatch,
                    // but a weaker match. It must not look as certain as two equal JDBC URLs.
                    peerInfo.dbIdentityUnknownMatches++;
                    confidence *= 0.85;
                }
                if ("MAPS_COLUMN".equals(relation) && e.confidence > 0) {
                    confidence = Math.min(confidence, e.confidence);
                }
                add(out, seen, delta.kind, delta.contract, change, shared.key, e.from, peer, e,
                        delta.usageOf(relation), confidence);
            }
        }
    }

    /** One database delta this program introduced: a column, a table, or a blind-spot table. */
    private static final class DbDelta {
        String symbolKey;
        String contract;
        String kind;
        /** WRITE_SET | ADD_COLUMN | DROP_COLUMN | MODIFY_COLUMN | DROP_TABLE | CREATE_TABLE | MAPPING | SCHEMA. */
        String operation;
        String table;
        DbIdentity ownIdentity;
        boolean hitsReaders = true;
        boolean hitsWriters = true;
        boolean hitsMappings = true;
        double confidence = 0.85;

        boolean accepts(String relation) {
            return switch (relation) {
                case "READS_COLUMN" -> hitsReaders;
                case "WRITES_COLUMN" -> hitsWriters;
                case "MAPS_COLUMN" -> hitsMappings;
                case "READS_TABLE" -> hitsReaders;
                case "WRITES_TABLE" -> hitsWriters;
                case "USES_TABLE" -> hitsMappings;
                default -> false;
            };
        }

        String usageOf(String relation) {
            return switch (relation) {
                case "WRITES_COLUMN", "WRITES_TABLE" -> "writer";
                case "MAPS_COLUMN", "USES_TABLE" -> "mapping";
                default -> "reader";
            };
        }
    }

    /**
     * A database namespace, at the level it was declared.
     *
     * A JDBC URL names a database; a schema qualifier names a schema. They are not comparable
     * across levels: two programs can reach the same schema through a URL and an explicit
     * qualifier respectively. Comparing only same-level identities avoids turning that into a
     * false negative while still suppressing two different declared databases.
     */
    private static final class DbIdentity {
        final String value;
        final boolean database;

        private DbIdentity(String value, boolean database) {
            this.value = value;
            this.database = database;
        }

        static DbIdentity database(String value) {
            return value == null || value.isBlank() ? null : new DbIdentity(value.trim(), true);
        }

        static DbIdentity schema(String value) {
            return value == null || value.isBlank() ? null : new DbIdentity(value.trim(), false);
        }

        static boolean known(DbIdentity identity) {
            return identity != null && identity.value != null && !identity.value.isBlank();
        }

        static boolean compatible(DbIdentity left, DbIdentity right) {
            if (!known(left) || !known(right)) {
                return true;
            }
            if (left.database != right.database) {
                return true;
            }
            return left.value.equalsIgnoreCase(right.value);
        }

        @Override
        public String toString() {
            return (database ? "database:" : "schema:") + value;
        }
    }

    /**
     * The database deltas carried by one change record.
     *
     * A change record points at the SQL statement, never at the column: the columns have to be read
     * back from the statement's own metadata (and from the base metadata, for the "which write
     * columns disappeared" comparison).
     */
    private List<DbDelta> dbDeltas(ChangeRecord change, CodeIndex ownHead, PeerImpact counter) {
        List<DbDelta> deltas = new ArrayList<>();
        Set<String> seenDeltas = new LinkedHashSet<>();
        if (change.symbolKey == null || change.symbolKey.isBlank()) {
            // A removed DDL statement has no head-side symbol - the record describes the base-side
            // statement, which no longer exists - so it carries no key and would be skipped by the
            // dispatch below. It is still a schema change and has to be counted, not dropped.
            if (ChangeDetector.DDL_CHANGED.equals(change.changeType)
                    && "true".equals(str(change.extra.get("ddlRemoved")))) {
                countRemovedMigrationScript(change, counter);
            }
            return deltas;
        }
        if (change.symbolKey.startsWith("db:column:")) {
            addColumnDelta(deltas, seenDeltas, change.symbolKey.substring("db:column:".length()),
                    "SCHEMA", true, true, true, 0.9);
        } else if (change.symbolKey.startsWith("db:table:")) {
            addTableDelta(deltas, seenDeltas, change.symbolKey.substring("db:table:".length()),
                    "SCHEMA", true, true, true, 0.75);
        } else if (ChangeDetector.FIELD_REMOVED.equals(change.changeType)
                || ChangeDetector.FIELD_TYPE_CHANGED.equals(change.changeType)) {
            // An entity mapping is not a schema change. Removing a field changes what this program
            // reads, not what the other program reads; adding one changes nothing at all. Only a
            // peer that maps the same column is worth a low-confidence mapping hint.
            for (String column : mappedColumns(change, ownHead)) {
                addColumnDelta(deltas, seenDeltas, column, "MAPPING", false, false, true, 0.5);
            }
        } else if (ChangeDetector.FIELD_ADDED.equals(change.changeType)) {
            // Adding a mapping is internal; it cannot break a consumer.
        } else if (!change.symbolKey.startsWith("sql:")) {
            return deltas;
        } else if (ChangeDetector.DDL_CHANGED.equals(change.changeType)) {
            dbDeltasForDdl(change, counter, deltas, seenDeltas);
        } else if (!isIncomplete(change)) {
            // A plain statement edit: only the columns this program writes can reach the other side.
            for (String column : changedWriteColumns(change)) {
                addColumnDelta(deltas, seenDeltas, column, "WRITE_SET", true, false, false, 0.85);
            }
            for (String table : changedWriteTables(change)) {
                // The statement started or stopped writing a whole table and named no column: this
                // is a table-level change of the write contract, and readers see new rows. Writers
                // are excluded: two programs writing the same table do not conflict through this.
                addTableDelta(deltas, seenDeltas, table, "WRITE_SET", true, false, false, 0.65);
            }
        }
        for (DbDelta delta : deltas) {
            delta.ownIdentity = ownIdentity(ownHead, change, delta.table);
        }
        return deltas;
    }

    private void dbDeltasForDdl(ChangeRecord change, PeerImpact counter,
                                List<DbDelta> deltas, Set<String> seen) {
        if ("true".equals(str(change.extra.get("ddlRemoved")))) {
            countRemovedMigrationScript(change, counter);
            return;
        }
        String kind = str(change.extra.get("ddl"));
        String table = normalizeTable(change.extra.get("table"));
        String column = normalizeColumn(change.extra.get("column"));
        String oldType = str(change.extra.get("oldType"));
        String newType = str(change.extra.get("newType"));
        String oldNullable = str(change.extra.get("oldNullable"));
        String newNullable = str(change.extra.get("newNullable"));
        String reason = str(change.extra.get("schemaChange"));
        if (reason.isBlank()) {
            reason = "TYPE";
        }
        switch (kind) {
            case "DROP_COLUMN" -> {
                if (!table.isBlank() && !column.isBlank()) {
                    addColumnDelta(deltas, seen, table + "." + column, kind, true, true, true, 0.95);
                }
            }
            case "ADD_COLUMN" -> {
                // A named-column reader or writer is unaffected by a new column. Only SELECT * and
                // columnless INSERT can observe it, and those are counted as blind spots. A NOT
                // NULL column without a default can reject a columnless INSERT; that is exactly
                // the blind spot the count exists to surface, not a reason to flag every writer.
                // The delta is still emitted so the table reaches the blind-spot pass, but it hits
                // nothing; the change is counted here instead of vanishing from the report.
                if (!table.isBlank()) {
                    addTableDelta(deltas, seen, table, kind, false, false, false, 0.5);
                }
                countNonObservable(counter, schemaSubject(table, column),
                        "新增列，命名列的读写方都观察不到");
            }
            case "MODIFY_COLUMN" -> {
                if (table.isBlank() || column.isBlank()) {
                    break;
                }
                if ("DEFAULT".equals(reason)) {
                    // A default only affects writes that omit the column; named-column code does
                    // not observe it. Columnless INSERT / SELECT * are still counted as blind spots.
                    addTableDelta(deltas, seen, table, kind, false, false, false, 0.3);
                    break;
                }
                if ("NULLABILITY".equals(reason)) {
                    ColumnTypeCompatibility.Result result =
                            ColumnTypeCompatibility.compareNullability(oldNullable, newNullable);
                    if (ColumnTypeCompatibility.safe(result)) {
                        if (counter != null) {
                            counter.dbCompatibilitySuppressed++;
                            addExample(counter.dbCompatibilityExamples, table + "." + column
                                    + "（可空性 " + safeNullable(oldNullable) + " → "
                                    + safeNullable(newNullable) + "）");
                        }
                        break;
                    }
                    // Readers still see the same values; only writers can fail on NOT NULL.
                    addColumnDelta(deltas, seen, table + "." + column, kind, false, true, false, 0.8);
                    break;
                }
                ColumnTypeCompatibility.Result result = ColumnTypeCompatibility.compare(oldType, newType);
                if (ColumnTypeCompatibility.safe(result)) {
                    if (counter != null) {
                        counter.dbCompatibilitySuppressed++;
                        addExample(counter.dbCompatibilityExamples, table + "." + column
                                + "（" + oldType + " → " + newType + "）");
                    }
                    break;
                }
                boolean known = !oldType.isBlank() && !newType.isBlank();
                addColumnDelta(deltas, seen, table + "." + column, kind, true, true, true,
                        known ? 0.9 : 0.6);
            }
            case "DROP_TABLE" -> {
                if (!table.isBlank()) {
                    addTableDelta(deltas, seen, table, kind, true, true, true, 0.95);
                }
            }
            case "CREATE_TABLE" -> {
                // A new table cannot already be a dependency of another program. If the peer does
                // reference it, that code is new too; this is not a regression caused by the change.
                // Counted rather than dropped, for the same reason: the schema did change, and the
                // report has to say the change was judged rather than skip it silently.
                countNonObservable(counter, table, "新建表，对方不可能已经依赖它");
            }
            default -> {
                if (!table.isBlank()) {
                    addTableDelta(deltas, seen, table, kind, true, true, true, 0.6);
                }
            }
        }
    }

    /**
     * A deleted migration script does not undo the migration in a real database, so it cannot break
     * a consumer. Counted instead of dropped: the file change is real, and a change that leaves no
     * trace in the report reads as one the analyser never saw.
     */
    private static void countRemovedMigrationScript(ChangeRecord change, PeerImpact counter) {
        countNonObservable(counter,
                schemaSubject(change.extra.get("table"), change.extra.get("column")),
                "删除迁移脚本，已执行的迁移不会回滚");
    }

    /**
     * Counts a schema change no consumer can observe, with one capped example.
     *
     * These changes are deliberately not turned into deltas, so without this counter they would
     * disappear from the report entirely. The count is per change: every peer is analysed against
     * the same change list, so the report aggregates it with {@code max()}, not a sum.
     */
    private static void countNonObservable(PeerImpact counter, String subject, String reason) {
        if (counter == null) {
            // The blind-spot pass re-reads the same deltas; it owns no counters of its own.
            return;
        }
        counter.dbNonObservableChanges++;
        if (!subject.isBlank()) {
            addExample(counter.dbNonObservableExamples, subject + "（" + reason + "）");
        }
    }

    /** {@code table.column} when both are known, otherwise the table alone. */
    private static String schemaSubject(Object table, Object column) {
        String normalizedTable = normalizeTable(table);
        if (normalizedTable.isBlank()) {
            return "";
        }
        String normalizedColumn = normalizeColumn(column);
        return normalizedColumn.isBlank() ? normalizedTable : normalizedTable + "." + normalizedColumn;
    }

    private Set<String> mappedColumns(ChangeRecord change, CodeIndex ownHead) {
        Set<String> columns = new LinkedHashSet<>();
        for (String column : splitColumns(str(change.extra.get("mappedColumns")))) {
            if (column.contains(".")) {
                columns.add(column.toLowerCase(java.util.Locale.ROOT));
            }
        }
        for (Edge e : ownHead.outgoing.getOrDefault(change.symbolKey, List.of())) {
            if ("MAPS_COLUMN".equals(e.relation) && e.to.startsWith("db:column:")) {
                columns.add(e.to.substring("db:column:".length()).toLowerCase(java.util.Locale.ROOT));
            }
        }
        return columns;
    }

    /**
     * Counts peer statements that touch a changed table but name no column.
     *
     * Column-level matching needs a column. {@code SELECT *} and dynamically built SQL have none,
     * so they can neither match nor be ruled out - the report says how many there are and where,
     * because an empty match list would otherwise read as "nothing to check here".
     */
    private void collectColumnBlindSpots(List<ChangeRecord> changes, CodeIndex ownHead, CodeIndex peer,
                                         PeerImpact out) {
        Map<String, DbDelta> tables = new LinkedHashMap<>();
        for (ChangeRecord change : changes) {
            for (DbDelta delta : dbDeltas(change, ownHead, null)) {
                if (delta.table == null || delta.table.isBlank() || "MAPPING".equals(delta.operation)) {
                    continue;
                }
                tables.putIfAbsent(delta.table, delta);
            }
        }
        if (tables.isEmpty()) {
            return;
        }
        // A statement that already matched as a reader/writer is not a blind spot: the report's
        // note says blind spots are neither confirmed nor excluded, and double-counting them
        // contradicts that.
        Set<String> matchedConsumers = new LinkedHashSet<>();
        for (PeerImpact.ContractMatch match : out.matches) {
            if (match.peerSymbolKey != null) {
                matchedConsumers.add(match.peerSymbolKey);
            }
        }
        int count = 0;
        Set<String> files = new LinkedHashSet<>();
        for (Sym sym : peer.symbols.values()) {
            if (!"SQL_STATEMENT".equals(sym.type) || sym.test || matchedConsumers.contains(sym.key)) {
                continue;
            }
            Set<String> statementTables = splitColumns(sym.meta.get("readsTables"));
            statementTables.addAll(splitColumns(sym.meta.get("writesTables")));
            statementTables.retainAll(tables.keySet());
            if (statementTables.isEmpty()) {
                continue;
            }
            boolean noColumns = splitColumns(sym.meta.get("readsColumns")).isEmpty()
                    && splitColumns(sym.meta.get("writesColumns")).isEmpty();
            boolean incomplete = "true".equals(sym.meta.get("sqlIncomplete"));
            if (!noColumns && !incomplete) {
                continue;
            }
            boolean sameDatabase = true;
            for (String table : statementTables) {
                DbDelta delta = tables.get(table);
                DbIdentity peerIdentity = peerIdentity(peer, sym.key, table);
                if (!DbIdentity.compatible(delta.ownIdentity, peerIdentity)) {
                    sameDatabase = false;
                    break;
                }
            }
            if (!sameDatabase) {
                continue;
            }
            count++;
            if (files.size() < 10) {
                files.add((sym.filePath == null ? "" : sym.filePath) + ":" + sym.startLine);
            }
        }
        out.unparsedColumnStatements = count;
        out.unparsedColumnFiles = new ArrayList<>(files);
    }

    private DbDelta addColumnDelta(List<DbDelta> out, Set<String> seen, String contract,
                                   String operation, boolean readers, boolean writers, boolean mappings,
                                   double confidence) {
        if (contract == null || contract.isBlank() || !contract.contains(".")) {
            return null;
        }
        String normalized = contract.trim().toLowerCase(java.util.Locale.ROOT);
        String key = "db:column:" + normalized;
        if (!seen.add(key)) {
            return null;
        }
        DbDelta d = new DbDelta();
        d.symbolKey = key;
        d.contract = normalized;
        d.kind = "DB_COLUMN";
        d.operation = operation;
        d.table = normalized.substring(0, normalized.indexOf('.'));
        d.hitsReaders = readers;
        d.hitsWriters = writers;
        d.hitsMappings = mappings;
        d.confidence = confidence;
        out.add(d);
        return d;
    }

    private DbDelta addTableDelta(List<DbDelta> out, Set<String> seen, String table,
                                  String operation, boolean readers, boolean writers, boolean mappings,
                                  double confidence) {
        String normalized = normalizeTable(table);
        if (normalized.isBlank()) {
            return null;
        }
        String key = "db:table:" + normalized;
        if (!seen.add(key)) {
            return null;
        }
        DbDelta d = new DbDelta();
        d.symbolKey = key;
        d.contract = normalized;
        d.kind = "DB_TABLE";
        d.operation = operation;
        d.table = normalized;
        d.hitsReaders = readers;
        d.hitsWriters = writers;
        d.hitsMappings = mappings;
        d.confidence = confidence;
        out.add(d);
        return d;
    }

    /** Columns this program started or stopped writing, as {@code table.column}. */
    private Set<String> changedWriteColumns(ChangeRecord change) {
        if (isIncomplete(change)) {
            return Set.of();
        }
        Set<String> before = splitColumns(str(change.extra.get("oldWritesColumns")));
        Set<String> after = splitColumns(str(change.extra.get("newWritesColumns")));
        Set<String> out = new LinkedHashSet<>(after);
        out.removeAll(before);
        Set<String> removed = new LinkedHashSet<>(before);
        removed.removeAll(after);
        out.addAll(removed);
        return out;
    }

    /**
     * Tables whose write contract appeared or disappeared, for statements that name no column.
     *
     * When the columns are known they are the contract, and the table-level fallback would drag in
     * every reader of the table - exactly the noise column matching exists to remove. The fallback
     * is only for a write with no column list at all ({@code insert into t values (...)}), where the
     * statement genuinely moved at table granularity. Dynamic SQL is excluded: its parsed column
     * list is a lower bound, not evidence that the table-level contract moved.
     */
    private Set<String> changedWriteTables(ChangeRecord change) {
        if (isIncomplete(change)) {
            return Set.of();
        }
        if (!splitColumns(str(change.extra.get("newWritesColumns"))).isEmpty()) {
            return Set.of();
        }
        Set<String> after = splitColumns(str(change.extra.get("newWritesTables")));
        if (after.isEmpty()) {
            return Set.of();
        }
        Set<String> before = splitColumns(str(change.extra.get("oldWritesTables")));
        Set<String> out = new LinkedHashSet<>(after);
        out.removeAll(before);
        return out;
    }

    private boolean isIncomplete(ChangeRecord change) {
        return "true".equals(str(change.extra.get("oldSqlIncomplete")))
                || "true".equals(str(change.extra.get("newSqlIncomplete")));
    }

    private DbIdentity ownIdentity(CodeIndex own, ChangeRecord change, String table) {
        String explicit = str(change.extra.get("ddlSchema"));
        if (explicit.isBlank()) {
            explicit = statementSchema(own.symbol(change.symbolKey), table);
        }
        if (!explicit.isBlank()) {
            return DbIdentity.schema(explicit);
        }
        if (DatabaseIdentity.known(own.databaseName)) {
            return DbIdentity.database(own.databaseName);
        }
        if (DatabaseIdentity.known(own.defaultSchema)) {
            return DbIdentity.schema(own.defaultSchema);
        }
        return null;
    }

    private DbIdentity peerIdentity(CodeIndex peer, String fromKey, String table) {
        Sym from = peer.symbol(fromKey);
        String explicit = statementSchema(from, table);
        if (!explicit.isBlank()) {
            return DbIdentity.schema(explicit);
        }
        if (DatabaseIdentity.known(peer.databaseName)) {
            return DbIdentity.database(peer.databaseName);
        }
        if (DatabaseIdentity.known(peer.defaultSchema)) {
            return DbIdentity.schema(peer.defaultSchema);
        }
        return null;
    }

    private String statementSchema(Sym sym, String table) {
        if (sym == null || sym.meta == null || table == null || table.isBlank()) {
            return "";
        }
        String encoded = sym.meta.get("tableSchemas");
        if (encoded != null && !encoded.isBlank()) {
            for (String part : encoded.split(",")) {
                int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).equalsIgnoreCase(table)) {
                    return part.substring(eq + 1);
                }
            }
        }
        String ddlTable = sym.meta.get("ddlTable");
        String ddlSchema = sym.meta.get("ddlSchema");
        if (ddlTable != null && ddlSchema != null && normalizeTable(ddlTable).equals(table)) {
            return ddlSchema;
        }
        return "";
    }

    private static String safeNullable(String value) {
        if (value == null || value.isBlank()) {
            return "未知";
        }
        return "true".equalsIgnoreCase(value) ? "可空" : "非空";
    }

    private static void addExample(List<String> examples, String value) {
        if (examples.size() < 5 && !examples.contains(value)) {
            examples.add(value);
        }
    }

    private static String normalizeTable(Object value) {
        return com.geek.association.parse.SqlAnalyzer.normalizeTable(value == null ? "" : String.valueOf(value));
    }

    private static String normalizeColumn(Object value) {
        String column = str(value).trim().toLowerCase(java.util.Locale.ROOT);
        int dot = column.lastIndexOf('.');
        return dot >= 0 ? column.substring(dot + 1) : column;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Set<String> splitColumns(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String part : csv.split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    private void collectConfigMatches(ChangeRecord change, CodeIndex peer,
                                      List<PeerImpact.ContractMatch> out, Set<String> seen) {
        if (change.symbolKey == null || !change.symbolKey.startsWith("config:")) {
            return;
        }
        String key = change.symbolKey.substring("config:".length());
        // A change to a.b.c also concerns code that binds the prefix a.b.
        List<String> candidates = new ArrayList<>();
        candidates.add(change.symbolKey);
        String cursor = key;
        int dot = cursor.lastIndexOf('.');
        while (dot > 0) {
            cursor = cursor.substring(0, dot);
            candidates.add("config:" + cursor);
            dot = cursor.lastIndexOf('.');
        }
        for (String candidate : candidates) {
            Sym shared = peer.symbol(candidate);
            if (shared == null) {
                continue;
            }
            for (Edge e : peer.incoming.getOrDefault(shared.key, List.of())) {
                if (!"USES_CONFIG".equals(e.relation)) {
                    continue;
                }
                add(out, seen, "CONFIG", shared.qualifiedName, change, shared.key, e.from, peer, e,
                        "config consumer", 0.7);
            }
        }
    }

    /**
     * Messaging contracts are joined on the normalised route identity, not on broker addresses.
     *
     * A topic change matches consumers that subscribe to that topic. A schema change matches the
     * producer and consumer symbols that use the same payload type. Both then propagate inside the
     * peer with the ordinary impact engine.
     */
    private void collectMessagingMatches(ChangeRecord change, CodeIndex ownHead, CodeIndex peer,
                                         List<PeerImpact.ContractMatch> out, Set<String> seen) {
        if (change.symbolKey == null) {
            return;
        }
        String key = change.symbolKey;
        if (key.startsWith("mq:") && key.contains(":topic:")) {
            Sym ownTopic = ownHead.symbol(key);
            if (ownTopic == null) {
                return;
            }
            String middleware = ownTopic.meta.getOrDefault("middleware", "");
            String topic = ownTopic.qualifiedName;
            for (Sym candidate : peer.symbols.values()) {
                if (!"MESSAGE_TOPIC".equals(candidate.type)
                        || !topic.equals(candidate.qualifiedName)
                        || !sameMiddleware(middleware, candidate.meta.get("middleware"))) {
                    continue;
                }
                for (Edge e : peer.incoming.getOrDefault(candidate.key, List.of())) {
                    if (!"ROUTES_TO".equals(e.relation)) {
                        continue;
                    }
                    Sym route = peer.symbol(e.from);
                    if (route == null) {
                        continue;
                    }
                    for (Edge subscription : peer.incoming.getOrDefault(route.key, List.of())) {
                        if (!"SUBSCRIBES_TO".equals(subscription.relation)) {
                            continue;
                        }
                        add(out, seen, "MQ_TOPIC", topic, change, route.key,
                                subscription.from, peer, subscription, "subscriber", 0.9);
                    }
                }
            }
            return;
        }
        if (key.startsWith("mq:") && key.contains(":schema:")) {
            Sym ownSchema = ownHead.symbol(key);
            if (ownSchema == null) {
                return;
            }
            String javaType = ownSchema.meta.getOrDefault("javaType", ownSchema.qualifiedName);
            String middleware = ownSchema.meta.getOrDefault("middleware", "");
            for (Sym candidate : peer.symbols.values()) {
                if (!"MESSAGE_SCHEMA".equals(candidate.type)) {
                    continue;
                }
                String peerType = candidate.meta.getOrDefault("javaType", candidate.qualifiedName);
                if (!javaType.equals(peerType) || !sameMiddleware(middleware, candidate.meta.get("middleware"))) {
                    continue;
                }
                for (Edge e : peer.incoming.getOrDefault(candidate.key, List.of())) {
                    if (!"USES_MESSAGE_SCHEMA".equals(e.relation)) {
                        continue;
                    }
                    add(out, seen, "MQ_SCHEMA", javaType, change, e.from,
                            e.from, peer, e, "payload consumer", 0.9);
                }
            }
        }
    }

    private boolean sameMiddleware(String own, String peer) {
        if (own == null || own.isBlank() || peer == null || peer.isBlank()) {
            return true;
        }
        return own.equalsIgnoreCase(peer);
    }

    /**
     * The peer declares a Maven dependency on one of this program's modules, so every symbol this
     * program changed in that module is part of the peer's compile contract.
     */
    private void collectArtifactMatches(CodeIndex ownHead, CodeIndex peer,
                                        List<PeerImpact.ContractMatch> out, Set<String> seen) {
        Set<String> ownArtifacts = new LinkedHashSet<>();
        for (var module : ownHead.modules.values()) {
            if (module.artifactId != null && !module.artifactId.isBlank()) {
                ownArtifacts.add(module.artifactId);
            }
        }
        if (ownArtifacts.isEmpty()) {
            return;
        }
        for (var module : peer.modules.values()) {
            for (String dep : module.dependencyArtifacts) {
                if (!ownArtifacts.contains(dep)) {
                    continue;
                }
                String key = "maven:" + dep;
                if (!seen.add("MAVEN_ARTIFACT|" + key)) {
                    continue;
                }
                PeerImpact.ContractMatch m = new PeerImpact.ContractMatch();
                m.kind = "MAVEN_ARTIFACT";
                m.contract = dep;
                m.changedSymbol = dep;
                m.peerSymbol = key;
                m.peerModule = module.path;
                m.confidence = 0.8;
                m.usage = "compile dependency";
                out.add(m);
            }
        }
    }

    private void add(List<PeerImpact.ContractMatch> out, Set<String> seen, String kind, String contract,
                     ChangeRecord change, String contractSymbolKey, String peerSymbolKey, CodeIndex peer,
                     Edge edge, String usage, double confidence) {
        String dedupe = kind + "|" + contract + "|" + peerSymbolKey;
        if (!seen.add(dedupe)) {
            return;
        }
        Sym sym = peer.symbol(peerSymbolKey);
        PeerImpact.ContractMatch m = new PeerImpact.ContractMatch();
        m.kind = kind;
        m.contract = contract;
        m.contractSymbolKey = contractSymbolKey;
        m.changedSymbol = change.qualifiedName;
        m.changeRecordId = change.id;
        m.peerSymbol = sym == null ? peerSymbolKey : sym.display();
        m.peerSymbolKey = peerSymbolKey;
        m.peerModule = sym == null ? (edge.filePath == null ? "" : "") : sym.moduleOrRoot();
        m.peerFile = edge.filePath != null ? edge.filePath : (sym == null ? null : sym.filePath);
        m.peerLine = edge.line;
        m.usage = usage;
        m.confidence = confidence;
        out.add(m);
    }
}
