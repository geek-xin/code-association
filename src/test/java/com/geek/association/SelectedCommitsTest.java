package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.diff.FileDiffCollector;
import com.geek.association.model.CommitInfo;
import com.geek.association.model.FileChange;
import com.geek.association.model.RepoSource;
import com.geek.association.source.RepositoryPreparer;
import com.geek.association.source.RevisionPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reviewing a hand-picked set of commits.
 *
 * The range mode answers "what does this branch add up to the endpoint"; this one answers "what do
 * these N commits change". The distinction is the whole point: in a range of five commits a
 * reviewer may care about two, and the other three only add noise. So the assertions are about the
 * diff containing exactly the picked commits' files - not the commits in between - and about the
 * selection being a set rather than a click order.
 */
class SelectedCommitsTest {

    private static final AppProperties PROPERTIES = new AppProperties();

    private static void git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(dir.toString());
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "git " + String.join(" ", args) + " -> " + output);
    }

    /**
     * Five commits, each touching a different file.
     *
     * Different files on purpose: with one shared file the last commit's content would win no
     * matter which commits were selected, and the test could not tell "picked two" from "picked
     * all five".
     */
    private static Path repo(Path root) throws Exception {
        git(root, "init", "-q", "-b", "master");
        git(root, "config", "user.email", "t@example.com");
        git(root, "config", "user.name", "t");
        Path src = Files.createDirectories(root.resolve("src/main/java/demo"));
        for (int i = 1; i <= 5; i++) {
            Files.writeString(src.resolve("Demo" + i + ".java"),
                    "package demo;\npublic class Demo" + i + " { public int v() { return " + i + "; } }\n");
            git(root, "add", ".");
            git(root, "commit", "-qm", "change " + i);
        }
        return root;
    }

    private static String sha(Path root, String ref) throws Exception {
        Process p = new ProcessBuilder("git", "-C", root.toString(), "rev-parse", ref)
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        p.waitFor();
        return out;
    }

    private static RepoSource source(Path root) {
        RepoSource source = new RepoSource();
        source.type = "LOCAL_GIT";
        source.location = root.toString();
        source.name = "selected-fixture";
        return source;
    }

    private static RevisionPair prepare(Path root, String... commits) throws IOException {
        RepositoryPreparer.Request request = new RepositoryPreparer.Request();
        request.source = source(root);
        request.mode = "COMMIT_RANGE";
        request.commits = new ArrayList<>(List.of(commits));
        return new RepositoryPreparer(PROPERTIES).prepare(request);
    }

    private static List<String> subjectsOf(List<CommitInfo> commits) {
        List<String> out = new ArrayList<>();
        for (CommitInfo c : commits) {
            out.add(c.subject);
        }
        return out;
    }

    @Test
    void theDiffCoversOnlyThePickedCommits(@TempDir Path root) throws Exception {
        repo(root);
        // Pick 2 and 4, leaving 3 and 5 out: this is the case a range cannot express.
        RevisionPair pair = prepare(root, sha(root, "master~3"), sha(root, "master~1"));

        List<String> paths = new FileDiffCollector(pair.base, pair.head).collect().stream()
                .map(f -> f.path).sorted().toList();

        assertEquals(List.of("src/main/java/demo/Demo2.java", "src/main/java/demo/Demo4.java"), paths,
                "only the two picked commits' files may appear - not Demo3 or Demo5 in between");
        assertEquals(2, pair.selectedCommits);
        assertEquals(2, pair.commitCount());
    }

    @Test
    void theSelectionIsASetNotAClickOrder(@TempDir Path root) throws Exception {
        repo(root);
        String two = sha(root, "master~3");
        String four = sha(root, "master~1");

        // Same two commits, opposite click order: the overlay has to apply them oldest-first, or
        // the older tree would overwrite the newer commit's files.
        RevisionPair forwards = prepare(root, two, four);
        RevisionPair backwards = prepare(root, four, two);

        assertEquals(subjectsOf(forwards.commits), subjectsOf(backwards.commits),
                "the report lists the same commits either way");
        assertEquals(
                new FileDiffCollector(forwards.base, forwards.head).collect().stream().map(f -> f.path).sorted().toList(),
                new FileDiffCollector(backwards.base, backwards.head).collect().stream().map(f -> f.path).sorted().toList(),
                "and the same diff");
    }

    @Test
    void pickingOneCommitAnalysesExactlyThatCommit(@TempDir Path root) throws Exception {
        repo(root);
        RevisionPair pair = prepare(root, sha(root, "master~2"));

        assertEquals(1, pair.selectedCommits);
        assertEquals(List.of("change 3"), subjectsOf(pair.commits));
        List<FileChange> files = new FileDiffCollector(pair.base, pair.head).collect();
        assertEquals(List.of("src/main/java/demo/Demo3.java"),
                files.stream().map(f -> f.path).toList());
    }

    @Test
    void theRangeNoteSaysTheRunWasASelection(@TempDir Path root) throws Exception {
        repo(root);
        RevisionPair pair = prepare(root, sha(root, "master~3"), sha(root, "master~1"));

        // The note is what stops a reader from assuming the diff covers every commit in between.
        assertTrue(pair.rangeNote.contains("只分析了选中的 2 个提交"), pair.rangeNote);
        assertEquals("2 个选中提交", pair.headRef);
    }

    @Test
    void anUnknownCommitIsNamedInTheError(@TempDir Path root) throws Exception {
        repo(root);

        IOException e = assertThrows(IOException.class,
                () -> prepare(root, "0123456789abcdef0123456789abcdef01234567"));

        assertTrue(e.getMessage().contains("找不到提交"), e.getMessage());
        assertTrue(e.getMessage().contains("0123456"), "the message has to name what could not be found");
    }

    @Test
    void commitsOnDifferentLinesAreRefused(@TempDir Path root) throws Exception {
        repo(root);
        git(root, "checkout", "-q", "-b", "side", "master~3");
        Files.writeString(root.resolve("src/main/java/demo/Side.java"), "package demo;\npublic class Side {}\n");
        git(root, "add", ".");
        git(root, "commit", "-qm", "side only");
        String side = sha(root, "side");
        git(root, "checkout", "-q", "master");

        // Combining a commit from side with one from master would describe a tree that never
        // existed; the report would look legitimate and be meaningless.
        IOException e = assertThrows(IOException.class,
                () -> prepare(root, side, sha(root, "master")));
        assertTrue(e.getMessage().contains("同一条提交线"), e.getMessage());
    }

    @Test
    void anEmptySelectionFallsBackToTheRangeEndpoints(@TempDir Path root) throws Exception {
        repo(root);
        RepositoryPreparer.Request request = new RepositoryPreparer.Request();
        request.source = source(root);
        request.mode = "COMMIT_RANGE";
        request.commits = new ArrayList<>();
        request.baseRef = "master~2";
        request.targetRef = "master";

        RevisionPair pair = new RepositoryPreparer(PROPERTIES).prepare(request);

        // An absent selection must not mean "no commits": every caller that predates this feature
        // sends no list at all and expects the range form to keep working.
        assertEquals(0, pair.selectedCommits);
        assertEquals(List.of("change 5", "change 4"), subjectsOf(pair.commits));
        assertFalse(pair.rangeNote.contains("选中"));
    }
}
