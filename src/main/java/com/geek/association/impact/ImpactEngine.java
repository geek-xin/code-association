package com.geek.association.impact;

import com.geek.association.config.AppProperties;
import com.geek.association.model.ChangeRecord;
import com.geek.association.model.CodeIndex;
import com.geek.association.model.Edge;
import com.geek.association.model.ImpactResult;
import com.geek.association.model.Sym;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The impact engine: three layers over one merged graph.
 *
 *  1. structural propagation - reverse BFS over CALLS / REFERENCES_TYPE / IMPLEMENTS / API / SQL /
 *     CONFIG edges, keeping the best (highest confidence, shortest) path to every reached symbol;
 *  2. semantic filtering - the first hop decides the impact type and the propagation weight, so a
 *     callee whose return value is only logged does not reach the same risk as one used in an
 *     if-condition;
 *  3. risk / confidence scoring - two independent numbers, never merged.
 */
public class ImpactEngine {

    private final AppProperties properties;

    public ImpactEngine(AppProperties properties) {
        this.properties = properties;
    }

    public static final class Options {
        public int maxDepth = 8;
        public int maxResultsPerChange = 400;
        public boolean includeTests = false;
        public double minConfidence = 0.15;
        public Set<String> ignoreModules = new LinkedHashSet<>();
    }

    public List<ImpactResult> analyze(ImpactGraph graph, List<ChangeRecord> changes, Options options) {
        List<ImpactResult> out = new ArrayList<>();
        for (ChangeRecord change : changes) {
            out.addAll(analyzeOne(graph, change, options));
        }
        out.sort(Comparator.comparingDouble((ImpactResult r) -> -r.riskScore));
        return out;
    }

    private List<ImpactResult> analyzeOne(ImpactGraph graph, ChangeRecord change, Options options) {
        List<ImpactResult> results = new ArrayList<>();
        String startKey = change.symbolKey;
        if (startKey == null) {
            return results;
        }
        Sym start = graph.symbol(startKey);
        Set<String> changedModules = new LinkedHashSet<>();
        if (change.modulePath != null) {
            changedModules.add(change.modulePath);
        }
        if (start != null && start.modulePath != null) {
            changedModules.add(start.modulePath);
        }
        Map<String, Visit> best = new LinkedHashMap<>();
        Deque<Visit> queue = new ArrayDeque<>();
        for (String origin : startKeys(change, graph)) {
            for (Edge e : graph.incomingOf(origin)) {
                if (!PropagationRules.traversable(e)) {
                    continue;
                }
                double factor = PropagationRules.confidenceFactor(e);
                if (factor <= 0) {
                    continue;
                }
                Visit v = new Visit(e.from, 1, factor, e, List.of(stepFor(e, graph)));
                offer(best, queue, v);
            }
        }
        if (change.extra.containsKey("directImpact")) {
            Sym direct = graph.symbol(startKey);
            if (direct != null) {
                Visit seed = new Visit(startKey, 1, 0.9, null, List.of());
                offer(best, queue, seed);
            }
        }
        int depthLimit = Math.min(options.maxDepth <= 0 ? 8 : options.maxDepth, properties.getMaxDepth());
        while (!queue.isEmpty()) {
            Visit current = queue.poll();
            if (current.depth >= depthLimit) {
                continue;
            }
            for (Edge e : graph.incomingOf(current.key)) {
                if (!PropagationRules.traversable(e)) {
                    continue;
                }
                double factor = PropagationRules.confidenceFactor(e);
                if (factor <= 0) {
                    continue;
                }
                double confidence = current.confidence * factor * PropagationRules.DEFAULT_DECAY;
                if (confidence < options.minConfidence) {
                    continue;
                }
                List<ImpactResult.PathStep> path = new ArrayList<>(current.path);
                path.add(stepFor(e, graph));
                Visit next = new Visit(e.from, current.depth + 1, confidence, current.firstEdge, path);
                offer(best, queue, next);
            }
            if (change.changeType != null && (change.changeType.startsWith("MESSAGE_")
                    || change.changeType.startsWith("TOPIC_")
                    || change.tags.contains("CROSS_PROGRAM"))) {
                // A message entry point executes downstream handlers. This is the one place where
                // propagation follows outgoing calls: the consumer method itself was reached from
                // the broker, so its callees are inside the affected execution.
                for (Edge e : graph.outgoingOf(current.key)) {
                    if (!"CALLS".equals(e.relation) || !PropagationRules.traversable(e)) {
                        continue;
                    }
                    double factor = PropagationRules.confidenceFactor(e);
                    if (factor <= 0) {
                        continue;
                    }
                    double confidence = current.confidence * factor * PropagationRules.DEFAULT_DECAY;
                    if (confidence < options.minConfidence) {
                        continue;
                    }
                    List<ImpactResult.PathStep> path = new ArrayList<>(current.path);
                    path.add(stepFor(e, graph));
                    Visit next = new Visit(e.to, current.depth + 1, confidence, current.firstEdge, path);
                    offer(best, queue, next);
                }
            }
        }
        int produced = 0;
        for (Visit visit : best.values()) {
            Sym target = graph.symbol(visit.key);
            if (target == null) {
                continue;
            }
            if (!options.includeTests && target.test) {
                continue;
            }
            if ("TEST_CASE".equals(target.type) || "TEST_CLASS".equals(target.type)) {
                continue;
            }
            if (target.type.equals("CLASS") || target.type.equals("INTERFACE") || target.type.equals("ENUM")
                    || target.type.equals("RECORD") || target.type.equals("ANNOTATION")) {
                // report the concrete consumer symbols, not just their type
                continue;
            }
            String module = target.modulePath == null ? "" : target.modulePath;
            if (options.ignoreModules.contains(module)) {
                continue;
            }
            ImpactResult r = new ImpactResult();
            r.changeRecordId = change.id;
            r.targetModule = module.isBlank() ? "(root)" : module;
            r.targetSymbolKey = target.key;
            r.targetQualifiedName = target.qualifiedName;
            r.targetSignature = target.signature;
            r.targetFile = target.filePath;
            r.targetLine = target.startLine;
            r.depth = visit.depth;
            r.confidenceScore = round(visit.confidence);
            r.confidence = RiskScorer.confidenceLevel(visit.confidence);
            r.impactType = PropagationRules.impactTypeOf(change.changeType, visit.firstEdge, visit.depth);
            r.propagationWeight = visit.firstEdge == null ? 1.0
                    : PropagationRules.usageFactor(visit.firstEdge);
            r.path = visit.path;
            r.evidence.addAll(evidenceFor(change, graph, visit));
            r.riskFactors = new LinkedHashMap<>();
            RiskScorer.Factors factors = RiskScorer.factors(change, r,
                    graph.incomingOf(startKey).size(), isPublic(start));
            r.riskFactors.put("changeRisk", round(factors.changeRisk));
            r.riskFactors.put("depth", round(factors.depth));
            r.riskFactors.put("behavior", round(factors.behavior));
            r.riskFactors.put("criticality", round(factors.criticality));
            r.riskFactors.put("uncertainty", round(factors.uncertainty));
            r.riskScore = RiskScorer.score(factors);
            r.riskLevel = RiskScorer.level(r.riskScore);
            r.reason = reasonFor(change, graph, visit, target);
            for (String testKey : graph.coveringTests(target.key)) {
                Sym test = graph.symbol(testKey);
                if (test != null) {
                    r.suggestedTests.add(test.qualifiedName + test.signature);
                }
            }
            if (r.suggestedTests.isEmpty()) {
                for (String testKey : graph.coveringTests(startKey)) {
                    Sym test = graph.symbol(testKey);
                    if (test != null) {
                        r.suggestedTests.add(test.qualifiedName + test.signature);
                    }
                }
            }
            results.add(r);
            if (++produced >= options.maxResultsPerChange) {
                break;
            }
        }
        return results;
    }

    /**
     * Symbols the propagation starts from.
     *
     * Beyond the changed symbol itself this adds the contracts the symbol carries, because a change
     * travels along them as well:
     *
     *  - a changed controller handler is also a change of the HTTP endpoint it exposes, so callers
     *    of that endpoint (Feign clients, RestTemplate code, other repositories) are impacted;
     *  - a changed field is also a change of its declaring type, so code that references the type
     *    (DTO consumers, serialisers) is impacted.
     */
    private List<String> startKeys(ChangeRecord change, ImpactGraph graph) {
        Set<String> keys = new LinkedHashSet<>();
        if (change.symbolKey != null) {
            keys.add(change.symbolKey);
            for (Edge e : graph.outgoingOf(change.symbolKey)) {
                if ("EXPOSES_API".equals(e.relation)) {
                    keys.add(e.to);
                }
            }
            Sym sym = graph.symbol(change.symbolKey);
            if (sym != null && sym.ownerKey != null
                    && (change.changeType.startsWith("FIELD") || change.changeType.startsWith("ENUM_CONSTANT"))) {
                keys.add(sym.ownerKey);
            }
        }
        return new ArrayList<>(keys);
    }

    private void offer(Map<String, Visit> best, Deque<Visit> queue, Visit candidate) {
        Visit existing = best.get(candidate.key);
        if (existing != null && existing.depth <= candidate.depth && existing.confidence >= candidate.confidence) {
            return;
        }
        best.put(candidate.key, candidate);
        queue.add(candidate);
    }

    /**
     * One hop of the rendered path: the symbol that consumes the change, plus the relation and the
     * way it consumes the value. Steps are stored nearest-first (from the changed symbol outwards).
     */
    private ImpactResult.PathStep stepFor(Edge e, ImpactGraph graph) {
        Sym from = graph.symbol(e.from);
        ImpactResult.PathStep step = new ImpactResult.PathStep();
        step.symbol = from == null ? e.from : from.display();
        step.module = from == null || from.modulePath == null ? "" : from.modulePath;
        step.file = e.filePath;
        step.line = e.line;
        step.relation = e.relation;
        step.usage = e.usage;
        step.confidence = e.confidence;
        step.note = e.detail;
        return step;
    }

    private List<ImpactResult.Evidence> evidenceFor(ChangeRecord change, ImpactGraph graph, Visit visit) {
        List<ImpactResult.Evidence> out = new ArrayList<>();
        out.add(new ImpactResult.Evidence("CHANGE", change.qualifiedName, change.changeType, change.filePath,
                change.startLine, change.semanticSummary));
        for (ImpactResult.PathStep step : visit.path) {
            out.add(new ImpactResult.Evidence("PATH", step.symbol, step.relation, step.file, step.line, step.note));
        }
        Sym target = graph.symbol(visit.key);
        if (target != null) {
            for (Edge e : graph.outgoingOf(target.key)) {
                if ("EXPOSES_API".equals(e.relation)) {
                    out.add(new ImpactResult.Evidence("EXPOSES_API", target.display(), e.to, e.filePath, e.line, e.detail));
                }
            }
        }
        return out;
    }

    private String reasonFor(ChangeRecord change, ImpactGraph graph, Visit visit, Sym target) {
        Edge first = visit.firstEdge;
        StringBuilder sb = new StringBuilder();
        sb.append(change.semanticSummary == null ? change.changeType : change.semanticSummary);
        sb.append("。");
        if (first != null) {
            Sym start = graph.symbol(change.symbolKey);
            String callee = start == null ? change.qualifiedName : start.display();
            Sym direct = graph.symbol(first.from);
            String consumer = direct == null ? first.from : direct.display();
            switch (first.relation) {
                case "CALLS" -> {
                    sb.append(consumer).append(" 调用 ").append(callee).append("。");
                    if (first.flowsIntoCondition || "CONDITION".equals(first.usage)) {
                        sb.append("返回值参与分支条件，因此该变化可能改变 ")
                                .append(shortName(consumer)).append(" 的执行路径。");
                    } else if ("LOG_ONLY".equals(first.usage)) {
                        sb.append("返回值仅用于日志，行为影响有限。");
                    } else if ("IGNORED".equals(first.usage)) {
                        sb.append("返回值被忽略，只需关注被调用方法自身的副作用。");
                    } else if ("ARGUMENT".equals(first.usage)) {
                        sb.append("返回值作为参数继续传递，影响取决于下游使用方式。");
                    } else if ("ASSIGNMENT".equals(first.usage) || "RETURN".equals(first.usage)) {
                        sb.append("返回值会继续向上游传播。");
                    }
                }
                case "INSTANTIATES" -> sb.append(consumer).append(" 实例化了发生变化的类型。");
                case "REFERENCES_TYPE" -> sb.append(consumer)
                        .append(" 在字段、参数、返回值或局部变量中引用了发生变化的类型。");
                case "IMPLEMENTS", "EXTENDS", "OVERRIDES" -> sb.append(consumer)
                        .append(" 与发生变化的类型处于同一继承体系中。");
                case "EXPOSES_API" -> sb.append(consumer).append(" 提供发生变化的 HTTP 接口。");
                case "CALLS_API" -> sb.append(consumer).append(" 调用发生变化的 HTTP 接口。");
                case "EXECUTES_SQL" -> sb.append(consumer).append(" 执行发生变化的 SQL 语句。");
                case "READS_TABLE", "WRITES_TABLE" -> sb.append(consumer)
                        .append(" 读取或写入受影响的数据库表。");
                case "READS_COLUMN", "WRITES_COLUMN" -> sb.append(consumer)
                        .append(" 依赖受影响的数据库字段。");
                case "USES_TABLE" -> sb.append(consumer).append(" 映射到受影响的数据库表。");
                case "USES_CONFIG" -> sb.append(consumer).append(" 读取发生变化的配置项。");
                case "PUBLISHES_TO" -> sb.append(consumer).append(" 向该消息通道发布消息。");
                case "SUBSCRIBES_TO" -> sb.append(consumer).append(" 订阅该消息通道。");
                case "USES_MESSAGE_SCHEMA" -> sb.append(consumer).append(" 使用该消息体契约。");
                case "ROUTES_TO" -> sb.append(consumer).append(" 定义该消息路由。");
                case "FILTERS_BY" -> sb.append(consumer).append(" 按该过滤条件消费消息。");
                default -> sb.append(consumer).append(" 通过 ")
                        .append(first.relation).append(" 关系与变更相连。");
            }
        }
        if (visit.depth > 1) {
            sb.append("该变化再经过 ").append(visit.depth - 1)
                    .append(" 跳传播到 ").append(target.display()).append("。");
        }
        return sb.toString();
    }

    private boolean isPublic(Sym sym) {
        return sym != null && (sym.modifiers.contains("public") || sym.modifiers.contains("protected"));
    }

    private String shortName(String qualified) {
        if (qualified == null) {
            return "";
        }
        String name = qualified;
        int hash = name.indexOf('#');
        if (hash >= 0) {
            name = name.substring(hash + 1);
        }
        return name;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static final class Visit {
        final String key;
        final int depth;
        final double confidence;
        final Edge firstEdge;
        final List<ImpactResult.PathStep> path;

        Visit(String key, int depth, double confidence, Edge firstEdge, List<ImpactResult.PathStep> path) {
            this.key = key;
            this.depth = depth;
            this.confidence = confidence;
            this.firstEdge = firstEdge;
            this.path = path;
        }
    }

    /** Convenience factory used by the service layer. */
    public static Options options(AppProperties properties, boolean includeTests) {
        Options o = new Options();
        o.maxDepth = properties.getMaxDepth();
        o.includeTests = includeTests;
        return o;
    }
}
