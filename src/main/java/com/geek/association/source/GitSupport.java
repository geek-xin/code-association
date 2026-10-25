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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Thin wrapper around JGit: clone, fetch, resolve refs, merge-base. */
public final class GitSupport {

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
        try {
            Files.createDirectories(targetDir);
            Git git = Git.cloneRepository()
                    .setURI(withCredentials(uri, token))
                    .setDirectory(dir)
                    .setCloneAllBranches(true)
                    .setCloneSubmodules(false)
                    .setTimeout(180)
                    .call();
            return git.getRepository();
        } catch (GitAPIException e) {
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
        try (Git git = new Git(repo)) {
            git.fetch()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec("+refs/heads/*:refs/remotes/origin/*"),
                            new RefSpec("+refs/tags/*:refs/tags/*"))
                    .setTimeout(180)
                    .setCredentialsProvider(credentials(token))
                    .call();
        } catch (GitAPIException e) {
            throw new IOException("fetch failed: " + e.getMessage(), e);
        }
    }

    public static void fetchRef(Repository repo, String ref, String token) throws IOException {
        try (Git git = new Git(repo)) {
            git.fetch()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec("+" + ref + ":refs/code-association/" + sanitize(ref)))
                    .setTimeout(180)
                    .setCredentialsProvider(credentials(token))
                    .call();
        } catch (GitAPIException e) {
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
     * Lists the refs a remote advertises, without cloning it.
     *
     * {@code ls-remote} is what makes branch discovery possible before a source is saved: cloning
     * just to show a dropdown would be slow and would leave a cache directory behind.
     */
    public static java.util.Collection<org.eclipse.jgit.lib.Ref> lsRemote(String uri, String token)
            throws IOException {
        try {
            return Git.lsRemoteRepository()
                    .setRemote(uri)
                    .setCredentialsProvider(credentials(token))
                    // This backs a form field, so it must fail fast rather than hang the UI.
                    .setTimeout(10)
                    .call();
        } catch (GitAPIException e) {
            throw new IOException("cannot list remote refs: " + e.getMessage(), e);
        }
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
