package com.geek.association.impact;

import com.geek.association.model.Edge;

/**
 * Propagation rules of the impact engine.
 *
 * Every relation has its own semantics: a removed public method breaks compilation of its callers,
 * while a changed branch condition only matters when the caller actually consumes the return value.
 * Encoding that distinction here is what keeps the report from degenerating into "everything is
 * impacted".
 */
public final class PropagationRules {

    private PropagationRules() {
    }

    public static final double DEFAULT_DECAY = 0.82;

    /**
     * Confidence multiplier for traversing an edge in the reverse (caller) direction.
     *
     * This measures only how reliable the *link* is - it is never multiplied by how strongly the
     * value is consumed. That strength is a separate weight ({@link #usageFactor}) applied to risk,
     * so "calls and branches on the result" and "calls and logs the result" stay equally certain
     * as links while differing in consequence.
     */
    public static double confidenceFactor(Edge edge) {
        double base = edge.confidence <= 0 ? 0.6 : edge.confidence;
        return switch (edge.relation) {
            case "CALLS" -> base;
            case "INSTANTIATES" -> base * 0.9;
            case "REFERENCES_TYPE" -> base * 0.85;
            case "IMPLEMENTS", "EXTENDS", "OVERRIDES" -> base * 0.9;
            case "EXPOSES_API" -> base;
            case "CALLS_API" -> base * 0.95;
            case "EXECUTES_SQL" -> base;
            case "READS_TABLE", "WRITES_TABLE" -> base * 0.75;
            case "READS_COLUMN", "WRITES_COLUMN", "MAPS_COLUMN" -> base * 0.9;
            case "USES_TABLE" -> base * 0.7;
            case "USES_CONFIG" -> base * 0.85;
            case "PUBLISHES_TO", "SUBSCRIBES_TO" -> base;
            case "USES_MESSAGE_SCHEMA" -> base * 0.95;
            case "ROUTES_TO" -> base * 0.9;
            case "FILTERS_BY" -> base * 0.85;
            case "USES_MESSAGE_GROUP" -> base * 0.8;
            case "TESTS" -> 0.0; // tests verify, they are not impacted business code
            default -> base * 0.7;
        };
    }

    /**
     * How much a behaviour change of the callee can matter to the caller, based on what the caller
     * does with the result. Logging the value is not the same as branching on it.
     */
    public static double usageFactor(Edge edge) {
        String usage = edge.usage == null ? "NONE" : edge.usage;
        if (edge.flowsIntoCondition || "CONDITION".equals(usage)) {
            return 1.0;
        }
        return switch (usage) {
            case "RETURN" -> 0.9;
            case "ASSIGNMENT" -> 0.8;
            case "ARGUMENT" -> 0.7;
            case "IGNORED" -> 0.45;
            case "LOG_ONLY" -> 0.2;
            default -> 0.5;
        };
    }

    /** Whether the reverse edge should be traversed at all. */
    public static boolean traversable(Edge edge) {
        return !"TESTS".equals(edge.relation) && !"MAPS_COLUMN".equals(edge.relation);
    }

    /**
     * Impact type of the first hop, i.e. what the change does to its immediate consumer.
     */
    public static String impactTypeOf(String changeType, Edge edge, int depth) {
        if (edge == null) {
            if (changeType != null && (changeType.startsWith("MESSAGE_")
                    || changeType.startsWith("TOPIC_"))) {
                return "MESSAGE_CONTRACT";
            }
            return depth == 1 ? "STRUCTURE" : "STRUCTURE";
        }
        String relation = edge.relation;
        if ("CALLS_API".equals(relation) || "EXPOSES_API".equals(relation)) {
            return "API_CONTRACT";
        }
        if ("EXECUTES_SQL".equals(relation) || "READS_TABLE".equals(relation) || "WRITES_TABLE".equals(relation)
                || "READS_COLUMN".equals(relation) || "WRITES_COLUMN".equals(relation) || "USES_TABLE".equals(relation)) {
            return "DATA";
        }
        if ("USES_CONFIG".equals(relation)) {
            return "CONFIG";
        }
        if ("PUBLISHES_TO".equals(relation) || "SUBSCRIBES_TO".equals(relation)
                || "USES_MESSAGE_SCHEMA".equals(relation) || "ROUTES_TO".equals(relation)
                || "FILTERS_BY".equals(relation) || "USES_MESSAGE_GROUP".equals(relation)) {
            return "MESSAGE_CONTRACT";
        }
        if ("REFERENCES_TYPE".equals(relation)) {
            return "TYPE";
        }
        if ("IMPLEMENTS".equals(relation) || "EXTENDS".equals(relation) || "OVERRIDES".equals(relation)) {
            return "TYPE";
        }
        if (changeType != null && changeType.contains("REMOVED")) {
            return "COMPILE";
        }
        if (changeType != null && changeType.contains("SIGNATURE")) {
            return "COMPILE";
        }
        if (changeType != null && changeType.contains("VISIBILITY")) {
            return "COMPILE";
        }
        if ("CALLS".equals(relation)) {
            if (edge.flowsIntoCondition || "CONDITION".equals(edge.usage)) {
                return "BEHAVIOR";
            }
            if ("RETURN".equals(edge.usage) || "ASSIGNMENT".equals(edge.usage) || "ARGUMENT".equals(edge.usage)) {
                return "BEHAVIOR";
            }
            return "BEHAVIOR";
        }
        return depth == 1 ? "STRUCTURE" : "STRUCTURE";
    }

    /** Extra risk weight of an impact type. */
    public static double typeWeight(String impactType) {
        return switch (impactType == null ? "" : impactType) {
            case "COMPILE" -> 1.0;
            case "API_CONTRACT" -> 0.95;
            case "DATA" -> 0.9;
            case "BEHAVIOR" -> 0.85;
            case "CONFIG" -> 0.75;
            case "TYPE" -> 0.7;
            case "SECURITY" -> 1.0;
            case "MESSAGE_CONTRACT" -> 0.95;
            case "MESSAGE_BEHAVIOR" -> 0.9;
            case "STRUCTURE" -> 0.5;
            default -> 0.4;
        };
    }
}
