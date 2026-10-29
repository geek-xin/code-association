package com.geek.association;

import com.geek.association.model.RepoSource;
import com.geek.association.source.SourceNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The source name is derived from the location, so the UI never asks for it. */
class SourceNamesTest {

    private static RepoSource source(String type, String location) {
        RepoSource s = new RepoSource();
        s.type = type;
        s.location = location;
        return s;
    }

    @Test
    void localDirectoryUsesItsFolderName(@TempDir Path root) throws IOException {
        Path project = Files.createDirectory(root.resolve("order-platform"));
        assertEquals("order-platform", SourceNames.derive(source("LOCAL_DIR", project.toString())));
    }

    @Test
    void trailingSlashDoesNotLeakIntoTheName(@TempDir Path root) throws IOException {
        Path project = Files.createDirectory(root.resolve("billing"));
        assertEquals("billing", SourceNames.derive(source("LOCAL_DIR", project + "/")));
    }

    @Test
    void remoteUsesTheProgramNameOnly() {
        // owner/repo reads as a location and dragged two long coordinates into the source list;
        // the label names the program, the same thing a local folder name does.
        assertEquals("repo", SourceNames.derive(source("GITHUB", "https://github.com/org/repo.git")));
        assertEquals("repo", SourceNames.derive(source("GITLAB", "https://gitlab.com/group/sub/repo")));
        assertEquals("repo", SourceNames.derive(source("GIT", "https://gitlab.example.com/team/repo")));
    }

    @Test
    void scpLikeRemoteIsHandled() {
        String url = "git" + "@" + "github.com:org/repo.git";
        assertEquals("repo", SourceNames.derive(source("GIT", url)));
    }

    @Test
    void explicitNameWins() {
        RepoSource s = source("LOCAL_DIR", "/tmp/whatever");
        s.name = "  my-source  ";
        assertEquals("my-source", SourceNames.derive(s));
    }

    @Test
    void blankNameIsFilledInPlace(@TempDir Path root) throws IOException {
        Path project = Files.createDirectory(root.resolve("user-service"));
        RepoSource s = source("LOCAL_DIR", project.toString());
        SourceNames.withDerivedName(s);
        assertEquals("user-service", s.name);
    }

    @Test
    void unusableLocationStillYieldsAName() {
        assertEquals("source", SourceNames.derive(source("LOCAL_DIR", "")));
        assertEquals("source", SourceNames.derive(source("GITHUB", "")));
        assertEquals("source", SourceNames.derive(null));
    }

    @Test
    void queryAndFragmentAreStrippedFromRemotes() {
        assertEquals("repo",
                SourceNames.derive(source("GITHUB", "https://github.com/org/repo.git?ref=main#readme")));
    }

    @Test
    void deriveFromIgnoresAStoredName() {
        RepoSource s = source("GITLAB", "https://gitlab.com/group/repo.git");
        s.name = "picked-by-hand";
        assertEquals("picked-by-hand", SourceNames.derive(s));
        assertEquals("repo", SourceNames.deriveFrom(s.type, s.location));
    }

    @Test
    void legacyRemoteNameIsTheOldOwnerRepoForm() {
        assertEquals("org/repo",
                SourceNames.legacyRemoteName(source("GITHUB", "https://github.com/org/repo.git")));
        assertNull(SourceNames.legacyRemoteName(source("LOCAL_DIR", "/tmp/orders")));
        assertNull(SourceNames.legacyRemoteName(null));
    }
}
