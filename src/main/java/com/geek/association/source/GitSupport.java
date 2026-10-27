package com.geek.association.source;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Thin wrapper around JGit: clone, fetch, resolve refs, merge-base. */
public final class GitSupport {

    private static final Logger log = LoggerFactory.getLogger(GitSupport.class);

    /**
     * Timeout for the branch dropdown's {@code ls-remote}.
     *
     * Ten seconds was measured to be too short: a large hosted repository needs more than that for
     * the transport handshake plus the (already filtered) ref advertisement, and the user then saw
     * a blank dropdown with no reason. Clone and fetch use 180s, so 60s stays the fast path while
     * no longer failing on a merely slow provider.
     */
    private static final int LS_REMOTE_TIMEOUT_SECS = 60;

    private GitSupport() {
    }

    public static Repository open(Path repoDir) throws IOException {
        File gitDir = new File(repoDir.toFile(), ".git");
        if (!gitDir.exists()) {
            throw new IOException("not a git repository: " + repoDir);
        }
        return org.eclipse.jgit.storage.file.FileRepositoryBuilder.create(gitDir);
    }

    public static Repository cloneIfMissing(Path targetDir, String uri, String token) throws IOException {
        File dir = targetDir.toFile();
        if (new File(dir, ".git").exists()) {
            return open(targetDir);
        }
        String target = maskCredentials(uri);
        long started = System.nanoTime();
        try {
            Files.createDirectories(targetDir);
            log.info("cloning {} into {}", target, targetDir);
            Git git = Git.cloneRepository()
                    .setURI(withCredentials(uri, token))
                    .setDirectory(dir)
                    .setCloneAllBranches(true)
                    .setCloneSubmodules(false)
                    .setTimeout(180)
                    .call();
            log.info("cloned {} in {} ms", target, elapsedMillis(started));
            return git.getRepository();
        } catch (GitAPIException e) {
            // A clone failure is the usual reason an analysis reports "cannot read this program", so
            // the transport cause chain belongs in the log: the exception message alone hides it.
            log.warn("clone {} failed after {} ms: {}", target, elapsedMillis(started), describe(e));
            throw new IOException("clone failed: " + e.getMessage(), e);
        }
    }

    public static String withCredentials(String uri, String token) {
        if (token == null || token.isBlank() || uri == null) {
            return uri;
        }
        if (!uri.startsWith("http")) {
            return uri;
        }
        String clean = uri.replaceFirst("^https://", "");
        int at = clean.indexOf('@');
        if (at >= 0) {
            clean = clean.substring(at + 1);
        }
        if (clean.startsWith("oauth2:") || clean.contains("x-access-token")) {
            return "https://" + clean;
        }
        return "https://oauth2:" + token + "@" + clean;
    }

    public static void fetchAll(Repository repo, String token) throws IOException {
        long started = System.nanoTime();
        try (Git git = new Git(repo)) {
            git.fetch()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec("+refs/heads/*:refs/remotes/origin/*"),
                            new RefSpec("+refs/tags/*:refs/tags/*"))
                    .setTimeout(180)
                    .setCredentialsProvider(credentials(token))
                    .call();
            log.debug("fetched {} in {} ms", repo.getDirectory(), elapsedMillis(started));
        } catch (GitAPIException e) {
            log.warn("fetch in {} failed after {} ms: {}", repo.getDirectory(), elapsedMillis(started), describe(e));
            throw new IOException("fetch failed: " + e.getMessage(), e);
        }
    }

    public static void fetchRef(Repository repo, String ref, String token) throws IOException {
        long started = System.nanoTime();
        try (Git git = new Git(repo)) {
            git.fetch()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec("+" + ref + ":refs/code-association/" + sanitize(ref)))
                    .setTimeout(180)
                    .setCredentialsProvider(credentials(token))
                    .call();
            log.debug("fetched ref {} in {} ms", ref, elapsedMillis(started));
        } catch (GitAPIException e) {
            log.warn("fetch ref {} failed after {} ms: {}", ref, elapsedMillis(started), describe(e));
            throw new IOException("fetch " + ref + " failed: " + e.getMessage(), e);
        }
    }

    private static String sanitize(String ref) {
        return ref.replaceAll("[^A-Za-z0-9._/-]", "_").replace('/', '_');
    }

    private static UsernamePasswordCredentialsProvider credentials(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return new UsernamePasswordCredentialsProvider("oauth2", token);
    }

    public static ObjectId resolve(Repository repo, String ref) throws IOException {
        if (ref == null || ref.isBlank()) {
            return repo.resolve(Constants.HEAD);
        }
        String[] candidates = {
                ref,
                "refs/heads/" + ref,
                "refs/remotes/origin/" + ref,
                "refs/tags/" + ref,
                "refs/code-association/" + sanitize(ref)
        };
        for (String c : candidates) {
            ObjectId id = repo.resolve(c + "^{commit}");
            if (id == null) {
                id = repo.resolve(c);
            }
            if (id != null) {
                try (RevWalk walk = new RevWalk(repo)) {
                    return walk.parseCommit(id).getId();
                } catch (Exception ignored) {
                    // try next candidate
                }
            }
        }
        return null;
    }

    public static String mergeBase(Repository repo, ObjectId a, ObjectId b) {
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit ca = walk.parseCommit(a);
            RevCommit cb = walk.parseCommit(b);
            walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE);
            walk.markStart(ca);
            walk.markStart(cb);
            RevCommit base = walk.next();
            return base == null ? null : base.getId().name();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Lists branches and tags advertised by a remote, without cloning it.
     *
     * {@code ls-remote} is what makes branch discovery possible before a source is saved: cloning
     * just to show a dropdown would be slow and would leave a cache directory behind.
     *
     * The heads/tags restriction is not a nicety. A large GitLab project advertises over a million
     * refs - environment refs, merge-request refs, pages refs - while only a few tens of thousands
     * are branches and tags. Measured against {@code gitlab.com/gitlab-org/gitlab}: the unrestricted
     * call needs 33-65s and blows through any sane timeout, the filtered call answers in ~3-4s.
     * Those refs are dropped later anyway, so asking the server to skip them costs nothing.
     */
    public static java.util.Collection<org.eclipse.jgit.lib.Ref> lsRemote(String uri, String token)
            throws IOException {
        String target = maskCredentials(uri);
        long started = System.nanoTime();
        try {
            java.util.Collection<org.eclipse.jgit.lib.Ref> refs = Git.lsRemoteRepository()
                    .setRemote(uri)
                    .setCredentialsProvider(credentials(token))
                    .setHeads(true)
                    .setTags(true)
                    // A form field waits on this, but a slow provider must still be allowed to
                    // answer: 10s was below the handshake+refs time of a large hosted repository.
                    .setTimeout(LS_REMOTE_TIMEOUT_SECS)
                    .call();
            log.debug("ls-remote {} -> {} refs in {} ms", target, refs.size(), elapsedMillis(started));
            return refs;
        } catch (GitAPIException e) {
            log.warn("ls-remote {} failed after {} ms: {}", target, elapsedMillis(started), describe(e));
            throw new IOException("cannot list remote refs: " + e.getMessage(), e);
        }
    }

    /** Elapsed milliseconds since {@code startedNanos}, for log lines that must stay cheap. */
    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    /**
     * Strips credentials from a URL before it reaches a log line.
     *
     * The token is read from an environment variable and must never be written to a log file; a
     * URL of the form {@code https://oauth2:secret@host/path} is exactly where it would leak.
     */
    public static String maskCredentials(String uri) {
        if (uri == null) {
            return "";
        }
        return uri.replaceAll("://[^/@]*@", "://***@");
    }

    /** One-line description of a transport failure, including the causes JGit hides in the message. */
    public static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable current = e;
        int depth = 0;
        while (current != null && depth < 4) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            String message = current.getMessage();
            sb.append(current.getClass().getSimpleName());
            if (message != null && !message.isBlank()) {
                sb.append(": ").append(maskCredentials(message.strip()));
            }
            current = current.getCause();
            depth++;
        }
        return sb.toString();
    }

    public static List<String> remoteBranches(Repository repo) {
        List<String> out = new ArrayList<>();
        StoredConfig cfg = repo.getConfig();
        for (String b : cfg.getSubsections("remote")) {
            out.add(b);
        }
        return out;
    }
}
