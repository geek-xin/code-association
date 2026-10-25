package com.geek.association.impact;

import com.geek.association.config.AppProperties;
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

        if (matches.isEmpty()) {
            return out;
        }

        // Propagate inside the peer from every matched consumer, reusing the normal engine so a
        // cross-program impact is scored and explained exactly like a local one.
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
            cr.riskSeed = 0.9;
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
     * Database contracts.
     *
     * A change record points at the SQL statement, not at the table: changing {@code insert into
     * orders(...)} alters the {@code orders} contract without touching the table symbol. The
     * tables a statement touches are read back from its own metadata, so a changed query still
     * matches the other program that reads the same table.
     */
    private void collectSqlMatches(ChangeRecord change, CodeIndex ownHead, CodeIndex peer,
                                   List<PeerImpact.ContractMatch> out, Set<String> seen) {
        Set<String> tableKeys = new LinkedHashSet<>();
        if (change.symbolKey != null && change.symbolKey.startsWith("db:table:")) {
            tableKeys.add(change.symbolKey);
        } else if (change.symbolKey != null && change.symbolKey.startsWith("db:column:")) {
            String column = change.symbolKey.substring("db:column:".length());
            int dot = column.indexOf('.');
            if (dot > 0) {
                tableKeys.add("db:table:" + column.substring(0, dot));
            }
        } else if (change.symbolKey != null && change.symbolKey.startsWith("sql:")) {
            Sym statement = ownHead.symbol(change.symbolKey);
            if (statement != null) {
                for (String table : splitTables(statement.meta.get("readsTables"))) {
                    tableKeys.add("db:table:" + table);
                }
                for (String table : splitTables(statement.meta.get("writesTables"))) {
                    tableKeys.add("db:table:" + table);
                }
            }
        }
        for (String tableKey : tableKeys) {
            Sym shared = peer.symbol(tableKey);
            if (shared == null) {
                continue;
            }
            for (Edge e : peer.incoming.getOrDefault(shared.key, List.of())) {
                String relation = e.relation == null ? "" : e.relation;
                if (!relation.contains("TABLE")) {
                    continue;
                }
                String usage = relation.startsWith("WRITES") ? "writer" : "reader";
                add(out, seen, "DB_TABLE", shared.qualifiedName, change,
                        shared.key, e.from, peer, e, usage, 0.75);
            }
        }
    }

    private static List<String> splitTables(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
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
