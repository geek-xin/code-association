package com.geek.association.source;

import com.geek.association.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The precedence between a source's own credential variable and the configured GitLab personal
 * token.
 *
 * A test cannot set an environment variable of the running JVM, so the lookup is injected; that
 * also lets "the variable is named but unset" be tested, which is the case that silently produced
 * anonymous requests before the fallback existed.
 */
class GitLabTokenResolutionTest {

    private static final String CONFIGURED = "glpat-configured-token";

    private static RepositoryPreparer preparer(String configuredToken, Map<String, String> env) {
        AppProperties properties = new AppProperties();
        properties.getGitlab().setToken(configuredToken);
        return new RepositoryPreparer(properties, env::get);
    }

    @Test
    void namedEnvironmentVariableWinsForEveryType() {
        RepositoryPreparer preparer = preparer(CONFIGURED, Map.of("GITLAB_TOKEN", "env-token"));

        assertEquals("env-token", preparer.tokenFor("GITLAB", "GITLAB_TOKEN"));
        assertEquals("env-token", preparer.tokenFor("GITHUB", "GITLAB_TOKEN"),
                "an explicitly configured variable is the repository's own credential, not a GitLab fallback");
    }

    @Test
    void aGitLabSourceFallsBackToTheConfiguredToken() {
        RepositoryPreparer preparer = preparer(CONFIGURED, Map.of());

        assertEquals(CONFIGURED, preparer.tokenFor("GITLAB", "GITLAB_TOKEN"),
                "a named but unset variable must not disable the saved token");
        assertEquals(CONFIGURED, preparer.tokenFor("gitlab", null));
        assertEquals(CONFIGURED, preparer.tokenFor("GitLab", ""));
    }

    @Test
    void otherTypesNeverReceiveTheGitLabToken() {
        RepositoryPreparer preparer = preparer(CONFIGURED, Map.of());

        assertNull(preparer.tokenFor("GITHUB", null), "the GitLab token must not be offered to GitHub");
        assertNull(preparer.tokenFor("GIT", null));
        assertNull(preparer.tokenFor("LOCAL_DIR", null));
        assertNull(preparer.tokenFor(null, null));
    }

    @Test
    void noConfiguredTokenMeansNoCredential() {
        RepositoryPreparer preparer = preparer("", Map.of());

        assertNull(preparer.tokenFor("GITLAB", null));
        assertNull(preparer.tokenFor("GITLAB", "GITLAB_TOKEN"));
    }

    @Test
    void configuredTokenIsTrimmedBeforeUse() {
        RepositoryPreparer preparer = preparer("  " + CONFIGURED + "\n", Map.of());

        assertEquals(CONFIGURED, preparer.tokenFor("GITLAB", null));
    }
}
