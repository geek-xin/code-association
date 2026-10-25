package com.geek.association;

import com.geek.association.model.RepoSource;
import com.geek.association.source.SourceNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void gitRemoteUsesOwnerAndRepo() {
        assertEquals("org/repo", SourceNames.derive(source("GITHUB", "https://github.com/org/repo.git")));
        assertEquals("group/sub/repo",
                SourceNames.derive(source("GITLAB", "https://gitlab.com/group/sub/repo")));
    }

    @Test
    void scpLikeRemoteIsHandled() {
        String url = "git" + "@" + "github.com:org/repo.git";
        assertEquals("org/repo", SourceNames.derive(source("GIT", url)));
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
        assertEquals("org/repo",
                SourceNames.derive(source("GITHUB", "https://github.com/org/repo.git?ref=main#readme")));
    }
}
