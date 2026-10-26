package com.geek.association;

import com.geek.association.update.ReleaseInfo;
import com.geek.association.update.UpdateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two rules that are easy to get wrong and expensive to get wrong.
 *
 * <ul>
 *   <li>Which asset gets installed: picking {@code -sources.jar} would replace the running
 *       application with a file that cannot start;</li>
 *   <li>Which jar the process is running from: inside a Spring Boot executable jar the classloader
 *       location is a nested URL, not a file path, and getting it wrong silently disables apply.</li>
 * </ul>
 */
class UpdateJarSelectionTest {

    private static ReleaseInfo release(String... assetNames) {
        ReleaseInfo release = new ReleaseInfo();
        release.version = "0.4.0";
        release.tag = "v0.4.0";
        for (String name : assetNames) {
            release.assets.add(new ReleaseInfo.Asset(name, 100L, "https://example.test/" + name));
        }
        return release;
    }

    @Test
    void prefersTheApplicationJarOverAnUnrelatedOne() {
        ReleaseInfo.Asset asset = UpdateService.jarAsset(release("helper.jar", "code-association-0.4.0.jar"));
        assertEquals("code-association-0.4.0.jar", asset.name);
    }

    @Test
    void neverPicksASourcesOrJavadocJar() {
        // The realistic ordering: the side artefacts come first in the release, so a naive
        // "first .jar" rule would install a sources jar and break the installation.
        ReleaseInfo release = release("code-association-0.4.0-sources.jar",
                "code-association-0.4.0-javadoc.jar",
                "code-association-0.4.0-tests.jar",
                "code-association-0.4.0.jar");

        assertEquals("code-association-0.4.0.jar", UpdateService.jarAsset(release).name);
    }

    @Test
    void onlySideArtefactsIsTreatedAsNoJar() {
        ReleaseInfo release = release("code-association-0.4.0-sources.jar", "code-association-0.4.0.zip");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> UpdateService.jarAsset(release));
        assertTrue(error.getMessage().contains("没有提供 .jar 附件"), error.getMessage());
        assertTrue(error.getMessage().contains("tar.gz/zip"),
                "the message must explain that this is not a network failure: " + error.getMessage());
    }

    @Test
    void anArchiveOnlyReleaseExplainsWhyItCannotBeInstalled() {
        // Exactly the current state of the real repository: only tar.gz and zip are published.
        ReleaseInfo release = release("code-association-0.4.0.tar.gz", "code-association-0.4.0.zip");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> UpdateService.jarAsset(release));
        assertTrue(error.getMessage().contains("请从发布页手动下载"), error.getMessage());
    }

    @Test
    void aPlainJarIsStillAcceptedWhenNothingIsNamedAfterTheApp() {
        assertEquals("app.jar", UpdateService.jarAsset(release("app.jar")).name);
    }

    @Test
    void theApplicationJarWinsEvenWhenItIsNotFirst() {
        ReleaseInfo release = release("aaa.jar", "code-association.jar", "zzz.jar");
        assertEquals("code-association.jar", UpdateService.jarAsset(release).name);
    }

    // ------------------------------------------------- running-jar resolution

    @Test
    void resolvesTheSpringBoot32NestedLocation(@TempDir Path tmp) throws Exception {
        Path jar = Files.createFile(tmp.resolve("code-association.jar"));

        // Spring Boot 3.2+ reports this form; note there is no "/" before the "!".
        Path resolved = com.geek.association.update.JarLocation.jarOf(
                "jar:nested:" + jar.toUri() + "/!BOOT-INF/classes/!/");

        assertEquals(jar, resolved);
    }

    @Test
    void resolvesTheClassicNestedLocation(@TempDir Path tmp) throws Exception {
        Path jar = Files.createFile(tmp.resolve("app.jar"));

        Path resolved = com.geek.association.update.JarLocation.jarOf(
                "jar:" + jar.toUri() + "!/BOOT-INF/classes!/");

        assertEquals(jar, resolved);
    }

    @Test
    void resolvesAPlainJarAndAPlainFileUri(@TempDir Path tmp) throws Exception {
        Path jar = Files.createFile(tmp.resolve("app.jar"));
        assertEquals(jar, com.geek.association.update.JarLocation.jarOf(jar.toUri().toString()));
        assertEquals(jar, com.geek.association.update.JarLocation.jarOf(jar.toString()));
    }

    @Test
    void aDirectoryIsNotAJar(@TempDir Path tmp) {
        // The IDE and test case: there is nothing to replace, so apply must report that clearly
        // instead of trying to copy over a directory.
        assertNull(com.geek.association.update.JarLocation.jarOf(tmp.toUri().toString()));
        assertNull(com.geek.association.update.JarLocation.jarOf(null));
        assertNull(com.geek.association.update.JarLocation.jarOf(""));
    }

    @Test
    void aNonJarFileIsRejected(@TempDir Path tmp) throws Exception {
        Path file = Files.createFile(tmp.resolve("notes.txt"));
        assertNull(com.geek.association.update.JarLocation.jarOf(file.toUri().toString()));
    }

    @Test
    void aMissingFileIsNotAJar(@TempDir Path tmp) {
        assertNull(com.geek.association.update.JarLocation.jarOf(
                tmp.resolve("does-not-exist.jar").toUri().toString()));
    }

    @Test
    void everyAssetNameIsCheckedAgainstTheSideArtefactList() {
        List<String> sideArtefacts = List.of("x-sources.jar", "x-javadoc.jar", "x-tests.jar");
        for (String name : sideArtefacts) {
            ReleaseInfo release = release(name);
            assertThrows(IllegalArgumentException.class, () -> UpdateService.jarAsset(release),
                    name + " must never be installed as the application");
        }
    }
}
