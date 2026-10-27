package com.geek.association;

import com.geek.association.source.PathBrowser;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guarantees of the folder picker backing {@code GET /api/fs/dirs}. */
class PathBrowserTest {

    private final PathBrowser browser = new PathBrowser();

    @SuppressWarnings("unchecked")
    private static List<PathBrowser.Entry> entriesOf(Map<String, Object> listing) {
        return (List<PathBrowser.Entry>) listing.get("entries");
    }

    @Test
    void listsDirectoriesButNeverFiles(@TempDir Path root) throws IOException {
        Files.createDirectory(root.resolve("alpha"));
        Files.createDirectory(root.resolve("beta"));
        Files.writeString(root.resolve("README.md"), "not a directory");

        Map<String, Object> listing = browser.browse(root.toString(), false);

        List<PathBrowser.Entry> entries = entriesOf(listing);
        assertEquals(2, entries.size(), "only directories are offered");
        assertEquals(List.of("alpha", "beta"), entries.stream().map(PathBrowser.Entry::name).toList());
        assertEquals(root.toAbsolutePath().normalize().toString(), listing.get("path"));
    }

    @Test
    void hidesDotDirectoriesUnlessAsked(@TempDir Path root) throws IOException {
        Files.createDirectory(root.resolve("visible"));
        Files.createDirectory(root.resolve(".hidden"));

        assertEquals(1, entriesOf(browser.browse(root.toString(), false)).size());
        assertEquals(2, entriesOf(browser.browse(root.toString(), true)).size());
    }

    @Test
    void marksGitAndMavenDirectories(@TempDir Path root) throws IOException {
        Path gitRepo = Files.createDirectory(root.resolve("repo"));
        Files.createDirectory(gitRepo.resolve(".git"));
        Files.writeString(gitRepo.resolve("pom.xml"), "<project/>");

        PathBrowser.Entry entry = entriesOf(browser.browse(root.toString(), false)).get(0);

        assertEquals("repo", entry.name());
        assertTrue(entry.git(), "a .git child makes it a git working copy");
        assertTrue(entry.maven(), "a pom.xml makes it a maven module");
    }

    @Test
    void exposesParentAndRootShortcuts(@TempDir Path root) throws IOException {
        Path child = Files.createDirectory(root.resolve("child"));

        Map<String, Object> listing = browser.browse(child.toString(), false);

        assertNotNull(listing.get("parent"));
        assertEquals(root.toAbsolutePath().normalize().toString(), listing.get("parent"));
        assertFalse(((List<?>) listing.get("roots")).isEmpty(), "the picker always offers shortcuts");
    }

    @Test
    void rejectsMissingAndNonDirectoryPaths(@TempDir Path root) throws IOException {
        Path file = Files.writeString(root.resolve("file.txt"), "x");

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> browser.browse(root.resolve("nope").toString(), false));
        assertTrue(missing.getMessage().contains("目录不存在"));

        IllegalArgumentException notDir = assertThrows(IllegalArgumentException.class,
                () -> browser.browse(file.toString(), false));
        assertTrue(notDir.getMessage().contains("不是文件夹"));
    }

    @Test
    void resolvesBlankAndTildeToHomeAndNormalisesPaths() {
        String home = System.getProperty("user.home");

        assertEquals(Path.of(home).toAbsolutePath().normalize(), PathBrowser.resolve(""));
        assertEquals(Path.of(home).toAbsolutePath().normalize(), PathBrowser.resolve("~"));
        assertEquals(Path.of(home, "code").toAbsolutePath().normalize(), PathBrowser.resolve("~/code"));
        assertEquals(Path.of("/tmp/../tmp/x").toAbsolutePath().normalize(), PathBrowser.resolve("/tmp/../tmp/x"));
    }

    @Test
    void reportsTheFlagsOfTheFolderBeingListed(@TempDir Path root) throws IOException {
        Path repo = Files.createDirectory(root.resolve("repo"));
        Files.createDirectory(repo.resolve(".git"));
        Files.writeString(repo.resolve("pom.xml"), "<project/>");

        Map<String, Object> listing = browser.browse(repo.toString(), false);

        assertTrue((Boolean) listing.get("git"), "the folder itself is a working copy");
        assertTrue((Boolean) listing.get("maven"), "the folder itself is a maven module");
    }

    @Test
    void rootDirectoryHasNoParent(@TempDir Path root) throws IOException {
        Path top = root.getRoot();
        Map<String, Object> listing = browser.browse(top.toString(), false);
        assertNull(listing.get("parent"), "the filesystem root cannot go up");
    }

    /**
     * The picker's 上一级 button walks the {@code parent} field until it disappears, so the field
     * has to lead all the way to the filesystem root and stop there.
     *
     * This is the contract the client used to ignore: it derived the parent itself by stripping
     * the last path segment, which cannot express "the parent of /Users is /" and therefore
     * stranded the user one level above their home directory.
     */
    @Test
    void parentChainReachesTheFilesystemRootAndStops(@TempDir Path root) throws IOException {
        Path deep = Files.createDirectories(root.resolve("a/b/c"));

        Path current = deep.toAbsolutePath().normalize();
        int steps = 0;
        while (true) {
            String parent = (String) browser.browse(current.toString(), false).get("parent");
            if (parent == null) {
                break;
            }
            Path next = Path.of(parent);
            assertEquals(current.getParent(), next, "each step is exactly one level up");
            assertTrue(next.getNameCount() < current.getNameCount(),
                    "walking up must strictly shorten the path, or the loop would not terminate");
            current = next;
            assertTrue(++steps < 64, "a path this shallow cannot need 64 steps to reach the root");
        }

        assertEquals(current.getRoot(), current, "the walk ends at the filesystem root");
    }
}
