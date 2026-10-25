package com.geek.association;

import com.geek.association.parse.ApiAnalyzer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiPathMatchTest {

    @Test
    void templateMatchesConcretePath() {
        assertTrue(ApiAnalyzer.pathsMatch("/api/users/{}", "/api/users/42"));
        assertTrue(ApiAnalyzer.pathsMatch("/api/users/{}", "/api/users/{}"));
        assertTrue(ApiAnalyzer.pathsMatch("/api/users", "/api/users"));
    }

    @Test
    void concatenatedClientPathMatchesTheTemplate() {
        // "\"/api/users/\" + id" leaves only the prefix after the literal scan
        assertTrue(ApiAnalyzer.pathsMatch("/api/users/{}", "/api/users"),
                "a client that concatenates the id still targets this endpoint");
    }

    @Test
    void unrelatedPathsDoNotMatch() {
        assertFalse(ApiAnalyzer.pathsMatch("/api/users/{}", "/api/orders"));
        assertFalse(ApiAnalyzer.pathsMatch("/api/users/{}", "/api"));
        assertFalse(ApiAnalyzer.pathsMatch("/api/users/{}", "/"));
        assertFalse(ApiAnalyzer.pathsMatch("", "/api/users"));
    }
}
