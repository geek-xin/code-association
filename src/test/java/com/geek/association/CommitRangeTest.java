package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.diff.FileDiffCollector;
import com.geek.association.model.CommitInfo;
import com.geek.association.model.FileChange;
import com.geek.association.model.RepoSource;
import com.geek.association.report.ImpactReport;
import com.geek.association.report.MarkdownReport;
import com.geek.association.report.ReportBuilder;
import com.geek.association.model.CodeIndex;
import com.geek.association.parse.CodeIndexer;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reviewing an explicit commit range.
 *
 * The unit of review is often a set of commits rather than a branch name ("these five commits are
 * what I am about to merge"), and a diff alone cannot say whether that set is five commits or fifty.
 * These tests pin the three things that make the mode trustworthy: the range is read exactly, the
 * commits in it are reported, and a range whose ends belong to different lines of history is
 * refused instead of silently producing a plausible-looking report.
 */
class CommitRangeTest {

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

    /** Four commits on master, plus an unrelated branch that diverges after the second one. */
    private static Path repo(Path root) throws Exception {
        git(root, "init", "-q", "-b", "master");
        git(root, "config", "user.email", "t@example.com");
        git(root, "config", "user.name", "t");
        Path src = Files.createDirectories(root.resolve("src/main/java/demo"));
        for (int i = 1; i <= 4; i++) {
            Files.writeString(src.resolve("Demo.java"),
                    "package demo;\npublic class Demo { public int value() { return " + i + "; } }\n");
            git(root, "add", ".");
            git(root, "commit", "-qm", "change " + i);
            if (i == 2) {
                // A branch that shares commits 1-2 with master and then goes its own way.
                git(root, "checkout", "-q", "-b", "side");
                Files.writeString(src.resolve("Side.java"), "package demo;\npublic class Side {}\n");
                git(root, "add", ".");
                git(root, "commit", "-qm", "side only");
                git(root, "checkout", "-q", "master");
            }
        }
        return root;
    }

    private static RepoSource source(Path root) {
        RepoSource source = new RepoSource();
        source.type = "LOCAL_GIT";
        source.location = root.toString();
        source.name = "range-fixture";
        return source;
    }

    private static RevisionPair prepare(Path root, String base, String target) throws IOException {
        RepositoryPreparer.Request request = new RepositoryPreparer.Request();
        request.source = source(root);
        request.mode = "COMMIT_RANGE";
        request.baseRef = base;
        request.targetRef = target;
        return new RepositoryPreparer(PROPERTIES).prepare(request);
    }

    @Test
    void readsTheRangeAndListsItsCommitsNewestFirst(@TempDir Path root) throws Exception {
        repo(root);

        RevisionPair pair = prepare(root, "master~2", "master");

        assertEquals("COMMIT_RANGE", pair.mode);
        assertEquals(List.of("change 4", "change 3"), subjectsOf(pair.commits),
                "the range is master~2..master, so exactly the last two commits, newest first");
        assertTrue(pair.rangeNote.isEmpty(), "two commits are never truncated");
        // The diff covers those two commits and nothing else.
        List<FileChange> files = new FileDiffCollector(pair.base, pair.head).collect();
        assertEquals(List.of("src/main/java/demo/Demo.java"),
                files.stream().map(f -> f.path).toList());
    }

    @Test
    void aShortShaAndABranchNameResolveToTheSameRange(@TempDir Path root) throws Exception {
        repo(root);
        String sha = commitSha(root, "master~2");

        RevisionPair byName = prepare(root, "master~2", "master");
        RevisionPair bySha = prepare(root, sha, "master");

        assertEquals(byName.baseCommit, bySha.baseCommit);
        assertEquals(byName.headCommit, bySha.headCommit);
        assertEquals(subjectsOf(byName.commits), subjectsOf(bySha.commits));
    }

    @Test
    void theCommitsReachTheReportAndThePullRequestComment(@TempDir Path root) throws Exception {
        repo(root);
        RevisionPair pair = prepare(root, "master~2", "master");

        CodeIndex headIndex = new CodeIndexer(PROPERTIES.getMaxFiles())
                .index(pair.head, pair.head.label(), pair.headCommit);
        CodeIndex baseIndex = new CodeIndexer(PROPERTIES.getMaxFiles())
                .index(pair.base, pair.base.label(), pair.baseCommit);
        List<FileChange> files = new FileDiffCollector(pair.base, pair.head).collect();
        ImpactReport report = new ReportBuilder().build(pair, headIndex, baseIndex, List.of(),
                List.of(), Map.of(), List.of());

        assertEquals(2, report.commitCount);
        assertEquals(2, report.summary.commitCount);
        assertEquals(List.of("change 4", "change 3"), subjectsOf(report.commits));

        String markdown = MarkdownReport.render(report, 10);
        assertTrue(markdown.contains("本次提交范围"), markdown);
        assertTrue(markdown.contains("change 4"), markdown);
        assertTrue(markdown.contains("| 提交数 | 2"), markdown);
    }

    @Test
    void aBranchComparisonAlsoReportsItsCommits(@TempDir Path root) throws Exception {
        repo(root);
        RepositoryPreparer.Request request = new RepositoryPreparer.Request();
        request.source = source(root);
        request.mode = "GIT_DIFF";
        request.baseRef = "master~3";
        request.targetRef = "master";

        RevisionPair pair = new RepositoryPreparer(PROPERTIES).prepare(request);

        assertEquals("GIT_DIFF", pair.mode);
        assertEquals(3, pair.commitCount(),
                "a branch comparison is a commit range too, and the reviewer needs its size");
    }

    @Test
    void refusesARangeWhoseEndsAreNotOnTheSameLineOfHistory(@TempDir Path root) throws Exception {
        repo(root);

        // side contains commits master does not, and vice versa: the diff would compare two
        // unrelated snapshots while looking like an ordinary review.
        IOException e = assertThrows(IOException.class, () -> prepare(root, "side", "master"));
        assertTrue(e.getMessage().contains("同一条提交线"), e.getMessage());
    }

    @Test
    void refusesAnEmptyRangeWithAReason(@TempDir Path root) throws Exception {
        repo(root);

        IOException e = assertThrows(IOException.class, () -> prepare(root, "master", "master"));
        assertTrue(e.getMessage().contains("同一个"), e.getMessage());
    }

    @Test
    void refusesAnUnknownCommitNamingWhichEndIsWrong(@TempDir Path root) throws Exception {
        repo(root);

        IOException base = assertThrows(IOException.class, () -> prepare(root, "no-such-commit", "master"));
        assertTrue(base.getMessage().contains("起始提交"), base.getMessage());

        IOException head = assertThrows(IOException.class, () -> prepare(root, "master~1", "no-such-commit"));
        assertTrue(head.getMessage().contains("结束提交"), head.getMessage());
    }

    @Test
    void theCommitPickerListsANamedRefAndARange(@TempDir Path root) throws Exception {
        repo(root);
        RepositoryPreparer preparer = new RepositoryPreparer(PROPERTIES);

        Map<String, Object> newest = preparer.commits(source(root), null, "master", 3);
        assertEquals(3, newest.get("count"));
        assertEquals("master", newest.get("ref"));
        assertTrue(((List<?>) newest.get("commits")).size() <= 3);

        Map<String, Object> range = preparer.commits(source(root), "master~2", "master", 30);
        assertEquals(2, range.get("count"));
        assertEquals(List.of("change 4", "change 3"), subjectsOf(castCommits(range.get("commits"))));
    }

    @Test
    void theCommitPickerReportsAProblemInsteadOfThrowing(@TempDir Path root) throws Exception {
        RepositoryPreparer preparer = new RepositoryPreparer(PROPERTIES);

        // A directory that is not a repository at all: the form must stay usable.
        Map<String, Object> notGit = preparer.commits(source(root), null, "master", 30);
        assertTrue(((List<?>) notGit.get("commits")).isEmpty());
        assertNotNull(notGit.get("note"), "the form needs a sentence it can show");

        // An unknown ref on a real repository is reported the same way.
        repo(root);
        Map<String, Object> unknown = preparer.commits(source(root), null, "no-such-branch", 30);
        assertTrue(((List<?>) unknown.get("commits")).isEmpty());
        assertTrue(String.valueOf(unknown.get("note")).contains("找不到"), String.valueOf(unknown.get("note")));
    }

    private static String commitSha(Path root, String ref) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(List.of("git", "-C", root.toString(), "rev-parse", ref))
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, p.waitFor());
        return out;
    }

    private static List<String> subjectsOf(List<CommitInfo> commits) {
        return commits.stream().map(c -> c.subject).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<CommitInfo> castCommits(Object value) {
        return (List<CommitInfo>) value;
    }
}
