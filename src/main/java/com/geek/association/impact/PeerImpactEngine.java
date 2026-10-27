package com.geek.association.impact;

import com.geek.association.config.AppProperties;
import com.geek.association.diff.ChangeDetector;
import com.geek.association.model.ChangeRecord;
import com.geek.association.model.CodeIndex;
import com.geek.association.model.Edge;
import com.geek.association.model.ImpactResult;
import com.geek.association.model.PeerImpact;
import com.geek.association.model.Sym;

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
            collectSqlMatches(change, ownHead, peer, matches, seen);
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
                                   List<PeerImpact.ContractMatch> out, Set<String> seen) {
        for (ColumnDelta delta : columnDeltas(change, ownHead)) {
            Sym shared = peer.symbol(delta.symbolKey);
            if (shared == null) {
                continue;
            }
            for (Edge e : peer.incoming.getOrDefault(shared.key, List.of())) {
                String relation = e.relation == null ? "" : e.relation;
                if (!delta.accepts(relation)) {
                    continue;
                }
                add(out, seen, delta.kind, delta.contract, change, shared.key, e.from, peer, e, delta.usageOf(relation),
                        delta.confidence);
            }
        }
    }

    /** One database delta this program introduced: either a column or a whole table. */
    private static final class ColumnDelta {
        String symbolKey;
        String contract;
        String kind;
        /** Which relations on the peer side count as a use of this delta. */
        boolean hitsReaders = true;
        boolean hitsWriters = true;
        boolean hitsMappings = true;
        double confidence = 0.85;

        boolean accepts(String relation) {
            return switch (relation) {
                case "READS_COLUMN" -> hitsReaders;
                case "WRITES_COLUMN" -> hitsWriters;
                case "MAPS_COLUMN" -> hitsMappings;
                case "READS_TABLE", "WRITES_TABLE", "USES_TABLE" -> "DB_TABLE".equals(kind);
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
     * The database deltas carried by one change record.
     *
     * A change record points at the SQL statement, never at the column: the columns have to be read
     * back from the statement's own metadata (and from the base metadata, for the "which write
     * columns disappeared" comparison).
     */
    private List<ColumnDelta> columnDeltas(ChangeRecord change, CodeIndex ownHead) {
        List<ColumnDelta> deltas = new ArrayList<>();
        Set<String> seenDeltas = new LinkedHashSet<>();
        if (change.symbolKey == null) {
            return deltas;
        }
        if (change.symbolKey.startsWith("db:column:")) {
            String column = change.symbolKey.substring("db:column:".length());
            addColumnDelta(deltas, seenDeltas, column, true, true, true);
            return deltas;
        }
        if (change.symbolKey.startsWith("db:table:")) {
            addTableDelta(deltas, seenDeltas, change.symbolKey.substring("db:table:".length()));
            return deltas;
        }
        // An entity field mapped onto a column: the mapping is the contract.
        if (ChangeDetector.FIELD_REMOVED.equals(change.changeType)
                || ChangeDetector.FIELD_ADDED.equals(change.changeType)
                || ChangeDetector.FIELD_TYPE_CHANGED.equals(change.changeType)) {
            // The detector captured the mapping, because a removed field only exists in the base
            // index and its edges are gone from the head graph the peer analysis runs against.
            for (String column : splitColumns(str(change.extra.get("mappedColumns")))) {
                addColumnDelta(deltas, seenDeltas, column, true, true, true);
            }
            for (Edge e : ownHead.outgoing.getOrDefault(change.symbolKey, List.of())) {
                if (!"MAPS_COLUMN".equals(e.relation) || !e.to.startsWith("db:column:")) {
                    continue;
                }
                addColumnDelta(deltas, seenDeltas, e.to.substring("db:column:".length()), true, true, true);
            }
            if (!deltas.isEmpty()) {
                return deltas;
            }
        }
        if (!change.symbolKey.startsWith("sql:")) {
            return deltas;
        }
        if (ChangeDetector.DDL_CHANGED.equals(change.changeType)) {
            String table = str(change.extra.get("table"));
            String column = str(change.extra.get("column"));
            String kind = str(change.extra.get("ddl"));
            if (!column.isBlank() && !table.isBlank()) {
                // A column whose shape moved: whoever reads or writes it is affected.
                addColumnDelta(deltas, seenDeltas, table + "." + column, true, true, true);
            } else if (!table.isBlank()) {
                // CREATE / DROP TABLE: no column to name, so the table itself is the contract.
                addTableDelta(deltas, seenDeltas, table);
            }
            if (!deltas.isEmpty()) {
                return deltas;
            }
        }
        // A plain statement edit: only the columns this program writes can reach the other side.
        for (String column : changedWriteColumns(change)) {
            addColumnDelta(deltas, seenDeltas, column, true, false, false);
        }
        for (String table : changedWriteTables(change)) {
            // The statement started or stopped writing a whole table and named no column: this is a
            // table-level change of the write contract, and readers of the table see new rows.
            addTableDelta(deltas, seenDeltas, table);
        }
        return deltas;
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
        Set<String> tables = new LinkedHashSet<>();
        for (ChangeRecord change : changes) {
            for (ColumnDelta delta : columnDeltas(change, ownHead)) {
                if (delta.symbolKey.startsWith("db:table:")) {
                    tables.add(delta.symbolKey.substring("db:table:".length()));
                } else if (delta.symbolKey.startsWith("db:column:")) {
                    String column = delta.symbolKey.substring("db:column:".length());
                    int dot = column.indexOf('.');
                    if (dot > 0) {
                        tables.add(column.substring(0, dot));
                    }
                }
            }
        }
        if (tables.isEmpty()) {
            return;
        }
        int count = 0;
        Set<String> files = new LinkedHashSet<>();
        for (Sym sym : peer.symbols.values()) {
            if (!"SQL_STATEMENT".equals(sym.type)) {
                continue;
            }
            Set<String> statementTables = splitColumns(sym.meta.get("readsTables"));
            statementTables.addAll(splitColumns(sym.meta.get("writesTables")));
            statementTables.retainAll(tables);
            if (statementTables.isEmpty() || !splitColumns(sym.meta.get("readsColumns")).isEmpty()) {
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

    private void addColumnDelta(List<ColumnDelta> out, Set<String> seen, String contract,
                                boolean readers, boolean writers, boolean mappings) {
        if (contract == null || contract.isBlank() || !contract.contains(".")) {
            return;
        }
        String key = "db:column:" + contract;
        if (!seen.add(key)) {
            return;
        }
        ColumnDelta d = new ColumnDelta();
        d.symbolKey = key;
        d.contract = contract;
        d.kind = "DB_COLUMN";
        d.hitsReaders = readers;
        d.hitsWriters = writers;
        d.hitsMappings = mappings;
        d.confidence = 0.85;
        out.add(d);
    }

    private void addTableDelta(List<ColumnDelta> out, Set<String> seen, String table) {
        if (table == null || table.isBlank()) {
            return;
        }
        String key = "db:table:" + table;
        if (!seen.add(key)) {
            return;
        }
        ColumnDelta d = new ColumnDelta();
        d.symbolKey = key;
        d.contract = table;
        d.kind = "DB_TABLE";
        d.confidence = 0.75;
        out.add(d);
    }

    /** Columns this program started or stopped writing, as {@code table.column}. */
    private Set<String> changedWriteColumns(ChangeRecord change) {
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
     * statement genuinely moved at table granularity.
     */
    private Set<String> changedWriteTables(ChangeRecord change) {
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
