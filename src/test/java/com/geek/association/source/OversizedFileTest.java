package com.geek.association.source;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A repository that happens to contain one very large file must still be analysable.
 *
 * This is a regression test for a real failure. JGit refuses to hand out an object larger than the
 * limit it was given with {@code LargeObjectException.ExceedsLimit}, which is a
 * {@link RuntimeException} - so the {@code catch (IOException)} in the reader did not catch it and
 * the whole run died with
 * {@code Object unknown object exceeds 20,971,520 limit, actual size is 63,923,629}.
 *
 * One committed SDK, database dump or oddly named jar was enough to fail every analysis of that
 * program, with no indication of which file caused it. The file is now skipped instead, and named.
 */
class OversizedFileTest {

    /** Comfortably over the 20 MB default while staying cheap to create and commit. */
    private static final int BIG_BYTES = 21 * 1024 * 1024;

    private static void git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(dir.toString());
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), "git " + String.join(" ", args));
    }

    /** A committed repository containing one normal source file and one oversized blob. */
    private static Path repoWithOneHugeFile(Path root) throws Exception {
        git(root, "init", "-q");
        git(root, "config", "user.email", "t@example.com");
        git(root, "config", "user.name", "t");
        Files.writeString(root.resolve("Small.java"), "class Small {}");
        // Random-ish content would be incompressible and slow; repeated text compresses in the
        // pack but getSize() still reports the uncompressed length, which is what the limit uses.
        byte[] big = new byte[BIG_BYTES];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) ('a' + (i % 26));
        }
        Files.write(root.resolve("huge.txt"), big);
        git(root, "add", ".");
        git(root, "commit", "-qm", "init");
        return root;
    }

    private static ObjectId head(Repository repo) throws IOException {
        return repo.resolve("HEAD");
    }

    @Test
    void readingAnOversizedBlobReturnsNullInsteadOfFailingTheRun(@TempDir Path root) throws Exception {
        repoWithOneHugeFile(root);
        try (Repository repo = GitSupport.open(root)) {
            GitTreeSource source = new GitTreeSource(repo, head(repo), "HEAD", 5000);

            // The read that used to throw LargeObjectException. Returning null is what the
            // indexing pipeline treats as "not readable", so the run continues.
            assertNull(source.read("huge.txt"),
                    "an oversized file must be skipped, not thrown out of the reader");
        }
    }

    @Test
    void theSkippedFileIsNamedSoTheGapIsVisible(@TempDir Path root) throws Exception {
        repoWithOneHugeFile(root);
        try (Repository repo = GitSupport.open(root)) {
            GitTreeSource source = new GitTreeSource(repo, head(repo), "HEAD", 5000);
            source.read("huge.txt");

            List<FileSource.SkippedFile> skipped = source.skippedFiles();
            assertEquals(1, skipped.size(), "exactly the oversized file should be recorded");
            assertEquals("huge.txt", skipped.get(0).path());
            assertTrue(skipped.get(0).size() >= BIG_BYTES,
                    "the recorded size is what lets a reader judge whether the omission matters");
            assertTrue(skipped.get(0).display().contains("MB"), skipped.get(0).display());
        }
    }

    @Test
    void ordinaryFilesAreStillReadAndHashed(@TempDir Path root) throws Exception {
        repoWithOneHugeFile(root);
        try (Repository repo = GitSupport.open(root)) {
            GitTreeSource source = new GitTreeSource(repo, head(repo), "HEAD", 5000);

            assertEquals("class Small {}", source.read("Small.java"),
                    "a skipped neighbour must not disturb the files around it");
            assertNotNull(source.hash("Small.java"));
            assertTrue(source.skippedFiles().isEmpty(), "nothing was skipped yet");
        }
    }

    /**
     * The same file through a working tree, which reads from disk rather than from a blob.
     *
     * The two readers exist for the same analysis, so the limit has to mean the same thing in
     * both: one huge file skipped, everything else indexed, and the file named in the same way.
     */
    @Test
    void aWorkingTreeSkipsTheSameFileAndStreamsItsHash(@TempDir Path root) throws Exception {
        repoWithOneHugeFile(root);
        DirSource source = new DirSource(root, "working tree", 5000);

        assertNull(source.read("huge.txt"));
        assertEquals("class Small {}", source.read("Small.java"));

        assertEquals(1, source.skippedFiles().size());
        assertEquals("huge.txt", source.skippedFiles().get(0).path());

        // Hashing must still work for the huge file: the diff pass compares by blob id, and a file
        // that cannot be hashed would be reported as newly added on every run.
        String hash = source.hash("huge.txt");
        assertNotNull(hash);
        assertEquals(40, hash.length(), "git blob ids are 40 hex characters, got: " + hash);
    }

    /**
     * A limit of zero means "no limit", so an installation can opt back into reading everything.
     */
    @Test
    void aDisabledLimitReadsTheLargeFileAnyway(@TempDir Path root) throws Exception {
        repoWithOneHugeFile(root);
        DirSource unlimited = new DirSource(root, "working tree", 5000, Integer.MAX_VALUE);

        String content = unlimited.read("huge.txt");
        assertNotNull(content, "a disabled limit must not skip anything");
        assertEquals(BIG_BYTES, content.length());
        assertTrue(unlimited.skippedFiles().isEmpty());
    }

    /** The configured megabyte value is what the readers compare against. */
    @Test
    void configurationConvertsMegabytesToBytes() {
        com.geek.association.config.AppProperties properties =
                new com.geek.association.config.AppProperties();
        assertEquals(20 * 1024 * 1024, properties.maxFileBytes());

        properties.setMaxFileMb(5);
        assertEquals(5 * 1024 * 1024, properties.maxFileBytes());

        properties.setMaxFileMb(0);
        assertEquals(Integer.MAX_VALUE, properties.maxFileBytes(),
                "zero disables the limit rather than skipping every file");
    }

    /** The default limit is applied when a caller constructs a source the old way. */
    @Test
    void theDefaultLimitMatchesTheConfiguredDefault() {
        com.geek.association.config.AppProperties properties =
                new com.geek.association.config.AppProperties();
        assertEquals(GitTreeSource.DEFAULT_MAX_FILE_BYTES, properties.maxFileBytes(),
                "the no-argument constructors and the configuration must not drift apart");
    }

    /** A source with no limit must never claim to have skipped something. */
    @Test
    void anEmptySourceHasNoSkippedFiles() {
        assertTrue(new EmptySource().skippedFiles().isEmpty());
        assertFalse(new EmptySource().files().iterator().hasNext());
        assertEquals("empty", new EmptySource().label());
    }
}