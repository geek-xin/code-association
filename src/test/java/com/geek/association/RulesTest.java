package com.geek.association;

import com.geek.association.diff.ChangeDetector;
import com.geek.association.impact.PropagationRules;
import com.geek.association.impact.RiskScorer;
import com.geek.association.model.Edge;
import com.geek.association.model.ImpactResult;
import com.geek.association.parse.ApiAnalyzer;
import com.geek.association.parse.SqlAnalyzer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit level guarantees of the scoring and normalisation rules. */
class RulesTest {

    @Test
    void riskAndConfidenceAreIndependent() {
        ImpactResult riskyButUncertain = new ImpactResult();
        riskyButUncertain.impactType = "BEHAVIOR";
        riskyButUncertain.depth = 1;
        riskyButUncertain.confidenceScore = 0.25;
        riskyButUncertain.propagationWeight = 1.0;

        RiskScorer.Factors factors = RiskScorer.factors(null, riskyButUncertain, 6, true);
        double score = RiskScorer.score(factors);

        assertEquals("LOW", RiskScorer.confidenceLevel(riskyButUncertain.confidenceScore));
        assertTrue(score >= 60, "an uncertain but consequential impact must still score high, was " + score);
        assertEquals("HIGH", RiskScorer.level(score));
    }

    @Test
    void usageWeightLowersBehaviourRisk() {
        ImpactResult branch = impact(1.0);
        ImpactResult logOnly = impact(0.2);
        double branchScore = RiskScorer.score(RiskScorer.factors(null, branch, 4, true));
        double logScore = RiskScorer.score(RiskScorer.factors(null, logOnly, 4, true));
        assertTrue(branchScore > logScore + 5,
                "branching on the value must score clearly above logging it: " + branchScore + " vs " + logScore);
    }

    @Test
    void apiPathsNormaliseToComparableTemplates() {
        assertEquals("/api/users/{}", ApiAnalyzer.normalizePath("/api/users/{id}"));
        assertEquals("/api/users/{}", ApiAnalyzer.normalizePath("http://svc:8080/api/users/{userId}?x=1"));
        assertEquals("/api/users", ApiAnalyzer.normalizePath("/api/users/"));
        assertEquals("/api/users", ApiAnalyzer.normalizePath("api/users"));
    }

    @Test
    void tableNamesNormalise() {
        assertEquals("t_user", SqlAnalyzer.normalizeTable("`t_user`"));
        assertEquals("t_user", SqlAnalyzer.normalizeTable("public.t_user"));
        assertEquals("t_user", SqlAnalyzer.normalizeTable("T_USER"));
    }

    @Test
    void everyRelationHasATraversalRule() {
        for (String relation : new String[]{"CALLS", "INSTANTIATES", "REFERENCES_TYPE", "IMPLEMENTS", "EXTENDS",
                "OVERRIDES", "EXPOSES_API", "CALLS_API", "EXECUTES_SQL", "READS_TABLE", "WRITES_TABLE",
                "READS_COLUMN", "WRITES_COLUMN", "MAPS_COLUMN", "USES_TABLE", "USES_CONFIG", "TESTS", "DEPENDS_ON"}) {
            Edge edge = new Edge();
            edge.relation = relation;
            edge.confidence = 0.9;
            double factor = PropagationRules.confidenceFactor(edge);
            if ("TESTS".equals(relation)) {
                assertEquals(0.0, factor, "tests verify, they must not propagate impact");
            } else {
                assertTrue(factor > 0, relation + " must be traversable");
                assertTrue(factor <= 0.9 + 1e-9, relation + " must not amplify confidence");
            }
        }
    }

    @Test
    void changeTypesAreClassified() {
        assertEquals("METHOD_CONDITION_CHANGED", ChangeDetector.METHOD_CONDITION_CHANGED);
        assertEquals("API_CONTRACT_CHANGED", ChangeDetector.API_CONTRACT_CHANGED);
        assertEquals("SQL_CHANGED", ChangeDetector.SQL_CHANGED);
    }

    private ImpactResult impact(double weight) {
        ImpactResult result = new ImpactResult();
        result.impactType = "BEHAVIOR";
        result.depth = 1;
        result.confidenceScore = 0.9;
        result.propagationWeight = weight;
        return result;
    }
}
