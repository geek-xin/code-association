package com.geek.association;

import com.geek.association.source.RepositoryPreparer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Branch discovery for the source form: the user should never have to type a branch name.
 *
 * Remote lookups need the network and are covered by the manual/API checks; these tests pin down
 * the local paths and the "never throws at a form field" contract.
 */
class RefDiscoveryTest {

    private final RepositoryPreparer preparer =
            new RepositoryPreparer(new com.geek.association.config.AppProperties());

    @SuppressWarnings("unchecked")
    private static List<String> branchesOf(Map<String, Object> refs) {
        return (List<String>) refs.get("branches");
    }

    @SuppressWarnings("unchecked")
    private static List<String> tagsOf(Map<String, Object> refs) {
        return (List<String>) refs.get("tags");
    }

    private static void git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(dir.toString());
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), "git " + String.join(" ", args));
    }

    @Test
    void listsBranchesAndTagsOfALocalRepository(@TempDir Path root) throws Exception {
        git(root, "init", "-q");
        git(root, "config", "user.email", "t@example.com");
        git(root, "config", "user.name", "t");
        Files.writeString(root.resolve("a.txt"), "a");
        git(root, "add", ".");
        git(root, "commit", "-qm", "init");
        git(root, "branch", "feature/login");
        git(root, "branch", "release/1.0");
        git(root, "tag", "v1.0");

        Map<String, Object> refs = preparer.refsForLocation("LOCAL_GIT", root.toString(), null);

        assertEquals(List.of("feature/login", "master", "release/1.0"), branchesOf(refs));
        assertEquals(List.of("v1.0"), tagsOf(refs));
        assertNotNull(refs.get("defaultRef"), "a default branch is offered so the field is pre-filled");
    }

    @Test
    void nonGitDirectoryReportsWhyInsteadOfThrowing(@TempDir Path root) {
        Map<String, Object> refs = preparer.refsForLocation("LOCAL_DIR", root.toString(), null);

        assertTrue(branchesOf(refs).isEmpty());
        assertNotNull(refs.get("note"), "the form needs a reason it can show");
    }

    @Test
    void missingPathIsReportedNotThrown() {
        Map<String, Object> refs = preparer.refsForLocation("LOCAL_GIT", "/definitely/not/here", null);

        assertTrue(branchesOf(refs).isEmpty());
        assertNotNull(refs.get("note"));
    }

    @Test
    void blankLocationIsReportedNotThrown() {
        Map<String, Object> refs = preparer.refsForLocation("LOCAL_GIT", "", null);

        assertTrue(branchesOf(refs).isEmpty());
        assertEquals("位置为空", refs.get("note"));
    }

    @Test
    void unreachableRemoteFailsWithoutThrowing() {
        // Reserved for documentation use; must not resolve, and must not hang the form.
        Map<String, Object> refs = preparer.refsForLocation("GITHUB",
                "https://invalid.invalid/org/repo.git", null);

        assertTrue(branchesOf(refs).isEmpty());
        assertNotNull(refs.get("note"));
        assertFalse(String.valueOf(refs.get("note")).contains("Exception"),
                "the note is shown to a user, so it must not leak a raw exception");
    }
}
