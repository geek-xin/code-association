package com.geek.association.impact;

import com.geek.association.model.ChangeRecord;
import com.geek.association.model.ImpactResult;

/**
 * Risk and confidence are deliberately two separate numbers.
 *
 * Risk answers "how bad would it be if this really broke something"; confidence answers
 * "how sure is the analyser that this link exists and carries the change". A removed public API
 * is high risk even when the caller cannot be resolved - in that case the risk stays high while
 * confidence drops, which is exactly the signal a reviewer needs.
 */
public final class RiskScorer {

    private RiskScorer() {
    }

    public static final class Factors {
        public double changeRisk;
        public double depth;
        public double behavior;
        public double criticality;
        public double uncertainty;
    }

    public static Factors factors(ChangeRecord change, ImpactResult impact, int totalCallers, boolean publicApi) {
        Factors f = new Factors();
        f.changeRisk = clamp(change == null ? 0.4 : change.riskSeed, 0, 1);
        f.depth = clamp(1.0 - (Math.max(1, impact.depth) - 1) * 0.15, 0.15, 1.0);
        double typeWeight = switch (impact.impactType == null ? "" : impact.impactType) {
            case "BEHAVIOR" -> 0.9;
            case "COMPILE" -> 0.85;
            case "API_CONTRACT" -> 0.85;
            case "DATA" -> 0.8;
            case "SECURITY" -> 1.0;
            case "MESSAGE_CONTRACT" -> 0.9;
            case "MESSAGE_BEHAVIOR" -> 0.85;
            case "CONFIG" -> 0.65;
            case "TYPE" -> 0.6;
            default -> 0.45;
        };
        // how the consumer uses the changed value decides whether the behaviour can actually change
        double weight = clamp(impact.propagationWeight <= 0 ? 1.0 : impact.propagationWeight, 0.05, 1.0);
        f.behavior = clamp(typeWeight * (0.4 + 0.6 * weight), 0, 1);
        f.criticality = clamp(0.35 + Math.min(0.5, totalCallers * 0.05) + (publicApi ? 0.15 : 0), 0, 1);
        f.uncertainty = clamp(1.0 - impact.confidenceScore, 0, 1);
        return f;
    }

    /**
     * R = 100 * (0.25 S + 0.20 D + 0.20 B + 0.20 C + 0.15 U)
     * The same weights documented in the design note, kept in one place so they can be tuned.
     */
    public static double score(Factors f) {
        double value = 100 * (0.25 * f.changeRisk + 0.20 * f.depth + 0.20 * f.behavior
                + 0.20 * f.criticality + 0.15 * f.uncertainty);
        return Math.round(clamp(value, 0, 100) * 10.0) / 10.0;
    }

    public static String level(double score) {
        if (score >= 80) {
            return "CRITICAL";
        }
        if (score >= 60) {
            return "HIGH";
        }
        if (score >= 40) {
            return "MEDIUM";
        }
        if (score >= 20) {
            return "LOW";
        }
        return "INFO";
    }

    public static String confidenceLevel(double confidence) {
        if (confidence >= 0.8) {
            return "HIGH";
        }
        if (confidence >= 0.55) {
            return "MEDIUM";
        }
        return "LOW";
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
