package com.geek.association.source;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pieces that make a failed remote lookup diagnosable.
 *
 * A branch dropdown can only show one sentence, so the reason has to be derived from the transport
 * failure rather than dumped verbatim; and a token embedded in a URL must never reach a log file,
 * an exception message or the browser.
 */
class GitSupportTest {

    private static final String FAKE_TOKEN = "tkn-abc123";

    @Test
    void masksCredentialsEmbeddedInAUrl() {
        assertEquals("https://***@gitlab.com/group/repo.git",
                GitSupport.maskCredentials("https://oauth2:" + FAKE_TOKEN + "@gitlab.com/group/repo.git"));
        assertEquals("https://***@github.com/org/repo.git",
                GitSupport.maskCredentials("https://x-access-token:" + FAKE_TOKEN + "@github.com/org/repo.git"));
    }

    @Test
    void leavesCredentialFreeUrlsUntouched() {
        String plain = "https://gitlab.com/group/repo.git";
        assertEquals(plain, GitSupport.maskCredentials(plain));
        assertEquals("", GitSupport.maskCredentials(null));
    }

    @Test
    void describeKeepsTheCauseChainThatJGitHides() {
        Exception root = new IOException("Connection reset");
        Exception middle = new IOException("cannot open git-upload-pack", root);
        Exception top = new IOException("cannot list remote refs", middle);

        String described = GitSupport.describe(top);

        assertTrue(described.contains("cannot list remote refs"), described);
        assertTrue(described.contains("cannot open git-upload-pack"), described);
        assertTrue(described.contains("Connection reset"), described);
    }

    @Test
    void describeMasksACredentialedUrlInTheMessage() {
        String described = GitSupport.describe(
                new IOException("https://oauth2:" + FAKE_TOKEN + "@gitlab.com/g/r.git: not authorized"));

        assertFalse(described.contains(FAKE_TOKEN), described);
        assertTrue(described.contains("not authorized"), described);
    }

    @Test
    void namesTheActualCauseInTheUserFacingNote() {
        assertTrue(RepositoryPreparer.refsFailureNote("TransportException: timeout").contains("超时"));
        assertTrue(RepositoryPreparer.refsFailureNote("TransportException: Authentication is required")
                .contains("认证"));
        assertTrue(RepositoryPreparer.refsFailureNote("TransportException: not found").contains("不存在"));
        assertTrue(RepositoryPreparer.refsFailureNote("UnknownHostException: gitlab.example")
                .contains("无法连接远程主机"));
        assertTrue(RepositoryPreparer.refsFailureNote("SSLHandshakeException: certificate")
                .contains("证书"));
        assertTrue(RepositoryPreparer.refsFailureNote("remote hung up unexpectedly").contains("中断"));
    }

    @Test
    void pointsSshUrlsAtTheHttpsForm() {
        // This JGit distribution ships no SSH transport, and GitLab's clone box offers exactly this
        // URL. Saying "check the network" would send the user looking in the wrong place.
        String note = RepositoryPreparer.refsFailureNote(
                "TransportException: git@gitlab.com:group/repo.git: remote hung up unexpectedly"
                        + " <- NullPointerException: Cannot invoke \"SshSessionFactory.getSession\" because \"this.sch\" is null");

        assertTrue(note.contains("SSH"), note);
        assertTrue(note.contains("https"), note);
    }

    @Test
    void namesAnAddressThatStopsAtTheHost() {
        String note = RepositoryPreparer.refsFailureNote(
                "JGitInternalException: Exception caught during execution of ls-remote command"
                        + " <- NotSupportedException: URI not supported: https://gitlab.com");

        assertTrue(note.contains("不完整"), note);
    }

    @Test
    void fallsBackToTheGenericNoteForAnUnrecognisedFailure() {
        assertEquals("无法读取远程分支，请检查地址、网络或凭据",
                RepositoryPreparer.refsFailureNote("something unexpected"));
    }

    @Test
    void addsTheTokenToHttpAndHttpsUrls() {
        // GitLab serves self-hosted installations over plain HTTP too; stripping only "https://"
        // used to produce "https://oauth2:token@http://host/..." and an unusable address.
        assertEquals("https://oauth2:tok-123@gitlab.com/group/repo.git",
                GitSupport.withCredentials("https://gitlab.com/group/repo.git", "tok-123"));
        assertEquals("http://oauth2:tok-123@gitlab.example:8080/group/repo.git",
                GitSupport.withCredentials("http://gitlab.example:8080/group/repo.git", "tok-123"));
    }

    @Test
    void replacesCredentialsAlreadyInTheUrlWithTheConfiguredToken() {
        // The configured token wins: it is the one the user just saved, and an old token baked
        // into a copied clone URL would otherwise keep failing authentication.
        assertEquals("https://oauth2:tok-123@gitlab.com/group/repo.git",
                GitSupport.withCredentials("https://oauth2:old-token@gitlab.com/group/repo.git", "tok-123"));
        assertEquals("https://oauth2:tok-123@github.com/org/repo.git",
                GitSupport.withCredentials("https://x-access-token:old-token@github.com/org/repo.git", "tok-123"));
    }

    @Test
    void leavesNonHttpAddressesAndBlankTokensAlone() {
        assertEquals("git@gitlab.com:group/repo.git",
                GitSupport.withCredentials("git@gitlab.com:group/repo.git", "tok-123"),
                "SSH has no transport in this build; the address must not be silently rewritten");
        assertEquals("https://gitlab.com/group/repo.git",
                GitSupport.withCredentials("https://gitlab.com/group/repo.git", "  "));
        assertEquals(null, GitSupport.withCredentials(null, "tok-123"));
    }

    @Test
    void stripsCredentialsBeforeStoringTheCloneUrl() {
        assertEquals("https://gitlab.com/group/repo.git",
                GitSupport.withoutCredentials("https://oauth2:" + FAKE_TOKEN + "@gitlab.com/group/repo.git"),
                "a token must not reach remote.origin.url");
        assertEquals("http://gitlab.example:8080/group/repo.git",
                GitSupport.withoutCredentials("http://user:" + FAKE_TOKEN + "@gitlab.example:8080/group/repo.git"));
        assertEquals("git@gitlab.com:group/repo.git",
                GitSupport.withoutCredentials("git@gitlab.com:group/repo.git"));
        assertEquals(null, GitSupport.withoutCredentials(null));
    }

    @Test
    void masksCredentialsInAnHttpUrlToo() {
        assertEquals("http://***@gitlab.example/group/repo.git",
                GitSupport.maskCredentials("http://oauth2:tok-123@gitlab.example/group/repo.git"));
    }
}
