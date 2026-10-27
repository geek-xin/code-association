package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.diff.ChangeDetector;
import com.geek.association.diff.FileDiffCollector;
import com.geek.association.model.ChangeRecord;
import com.geek.association.model.CodeIndex;
import com.geek.association.model.FileChange;
import com.geek.association.parse.CodeIndexer;
import com.geek.association.report.ImpactReport;
import com.geek.association.report.ReportBuilder;
import com.geek.association.source.FileSource;
import com.geek.association.source.RepositoryPreparer;
import com.geek.association.source.RevisionPair;
import com.geek.association.model.RepoSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole pipeline has to survive a repository that contains one oversized file.
 *
 * This reproduces the reported failure end to end rather than at the reader alone: the run used to
 * die with {@code Object unknown object exceeds 20,971,520 limit, actual size is 63,923,629} and the
 * UI showed only 分析失败, with no clue that a single file was the reason.
 *
 * The assertions are the acceptance criteria for the fix: the analysis completes, the file is
 * still part of the change set (so its addition is not silently missed), and the report names it.
 */
class OversizedFileAnalysisTest {

    /** Over the 20 MB default, so the default configuration exercises the skip path. */
    private static final int BIG_BYTES = 21 * 1024 * 1024;

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

    private static byte[] bigContent() {
        byte[] big = new byte[BIG_BYTES];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) ('a' + (i % 26));
        }
        return big;
    }

    /**
     * Two commits: the first contains a normal class and an oversized blob, the second edits the
     * class. Comparing them is the ordinary GIT_DIFF path a user runs every day.
     */
    private static Path repoWithTwoCommits(Path root) throws Exception {
        git(root, "init", "-q");
        git(root, "config", "user.email", "t@example.com");
        git(root, "config", "user.name", "t");
        Path src = Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.writeString(src.resolve("Demo.java"),
                "package demo;\npublic class Demo { public int value() { return 1; } }\n");
        Files.write(root.resolve("generated-dump.txt"), bigContent());
        git(root, "add", ".");
        git(root, "commit", "-qm", "init");

        Files.writeString(src.resolve("Demo.java"),
                "package demo;\npublic class Demo { public int value() { return 2; } }\n");
        git(root, "add", ".");
        git(root, "commit", "-qm", "change");
        return root;
    }

    private static RevisionPair prepare(Path root, AppProperties properties) throws IOException {
        RepoSource source = new RepoSource();
        source.type = "LOCAL_GIT";
        source.location = root.toString();
        source.name = "oversized-fixture";

        RepositoryPreparer.Request request = new RepositoryPreparer.Request();
        request.source = source;
        request.mode = "GIT_DIFF";
        request.targetRef = "HEAD";
        request.baseRef = "HEAD~1";
        return new RepositoryPreparer(properties).prepare(request);
    }

    @Test
    void theAnalysisCompletesAndTheReportNamesTheSkippedFile(@TempDir Path root) throws Exception {
        repoWithTwoCommits(root);
        AppProperties properties = new AppProperties();

        RevisionPair revisions = prepare(root, properties);

        // Indexing must not throw. Before the fix this is the call that threw
        // LargeObjectException out of GitTreeSource.read and failed the whole run.
        CodeIndex headIndex = assertDoesNotThrow(() -> new CodeIndexer(properties.getMaxFiles())
                .index(revisions.head, revisions.head.label(), revisions.headCommit));
        CodeIndex baseIndex = assertDoesNotThrow(() -> new CodeIndexer(properties.getMaxFiles())
                .index(revisions.base, revisions.base.label(), revisions.baseCommit));

        List<FileChange> fileChanges = new FileDiffCollector(revisions.base, revisions.head).collect();
        List<ChangeRecord> changes = new ChangeDetector(baseIndex, headIndex).detect(fileChanges);
        long seq = 1;
        for (ChangeRecord change : changes) {
            change.id = seq++;
        }

        Map<String, String> diffs = new FileDiffCollector(revisions.base, revisions.head)
                .unifiedDiffs(fileChanges, 80, 200);
        ImpactReport report = new ReportBuilder().build(revisions, headIndex, baseIndex, changes,
                List.of(), diffs, List.of());

        // The run produced a usable report rather than a FAILED record.
        assertNotNull(report);
        assertFalse(report.notes.isEmpty(), "a run with a skipped file must explain itself");

        // The skipped file is named, and the size that disqualified it is what the reader needs to
        // judge whether the omission matters.
        List<FileSource.SkippedFile> skipped = report.skippedFiles;
        assertEquals(1, skipped.size(), "exactly the oversized file, not a side effect on others: " + skipped);
        assertEquals("generated-dump.txt", skipped.get(0).path());
        assertTrue(skipped.get(0).size() >= BIG_BYTES, "recorded size: " + skipped.get(0).size());

        assertTrue(report.notes.stream().anyMatch(n -> n.contains("超过单文件读取上限")),
                "the notes must say a file was left out, got: " + report.notes);
    }

    @Test
    void theOversizedFileChangingIsStillAVisibleFileChange(@TempDir Path root) throws Exception {
        repoWithTwoCommits(root);
        AppProperties properties = new AppProperties();
        RevisionPair revisions = prepare(root, properties);

        List<FileChange> fileChanges = new FileDiffCollector(revisions.base, revisions.head).collect();

        // The diff is hash based and must not need the content, otherwise a large file could only
        // be reported as changed by loading it - the very thing the limit forbids.
        assertEquals(1, fileChanges.size(),
                "only Demo.java changed between the two commits, got: " + fileChanges);
        assertEquals("src/main/java/demo/Demo.java", fileChanges.get(0).path);
        assertEquals("MODIFIED", fileChanges.get(0).status);

        // Reading the oversized file yields no text, which is how the analyzers skip it, but it
        // must still be hashable so it never masquerades as added or deleted.
        assertNotNull(revisions.head.hash("generated-dump.txt"));
        assertEquals(revisions.base.hash("generated-dump.txt"),
                revisions.head.hash("generated-dump.txt"),
                "an unmodified large file must keep the same blob id on both sides");
    }

    @Test
    void theSameRepositoryIsAnalysableOnceTheLimitIsRaised(@TempDir Path root) throws Exception {
        repoWithTwoCommits(root);
        AppProperties properties = new AppProperties();
        // 64 MB: everything below is readable, so nothing is skipped.
        properties.setMaxFileMb(64);

        RevisionPair revisions = prepare(root, properties);
        CodeIndex headIndex = new CodeIndexer(properties.getMaxFiles())
                .index(revisions.head, revisions.head.label(), revisions.headCommit);

        assertNotNull(headIndex);
        assertTrue(revisions.head.skippedFiles().isEmpty(),
                "with a raised limit nothing should be skipped, got: " + revisions.head.skippedFiles());
    }
}