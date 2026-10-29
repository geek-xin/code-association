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

import com.geek.association.model.CommitInfo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

    /**
     * The most recent {@code limit} commits reachable from {@code head}, newest first.
     *
     * This is the list a reviewer picks a range from, so it must be bounded: a repository with
     * 300k commits would otherwise be walked in full just to fill a dropdown.
     */
    public static List<CommitInfo> commits(Repository repo, ObjectId head, int limit) {
        return commits(repo, head, limit, null, null);
    }

    /**
     * The newest commits of {@code head} inside an optional committer-time window, newest first.
     *
     * Bounds are inclusive epoch seconds - exactly what {@link RevCommit#getCommitTime()} returns,
     * i.e. the same instant the commit list renders as its date. Filtering on any other clock (the
     * author date, for instance) would let a commit be shown as Tuesday and rejected by a Tuesday
     * filter. {@code null} means "no bound", so the older three-argument method can delegate here
     * instead of keeping a second walk in sync.
     *
     * Because the walk is sorted newest first, a window is applied as "skip until it starts, stop
     * after it ends": commits newer than {@code until} are skipped (they are just history the
     * dropdown did not ask for), and the first commit older than {@code since} ends the walk,
     * since everything after it is older still. Stopping is the point of the design - a filter near
     * the end of a long history must not cost a full walk.
     */
    public static List<CommitInfo> commits(Repository repo, ObjectId head, int limit,
                                           Long sinceEpochSecond, Long untilEpochSecond) {
        return collect(repo, null, head, limit, sinceEpochSecond, untilEpochSecond);
    }

    /**
     * Commits reachable from {@code head} but not from {@code base}, newest first: exactly the set
     * a "review this range" run covers.
     *
     * When {@code base} is null the whole reachable history is returned, which is the root-commit
     * case (a first commit has no parent to exclude).
     */
    public static List<CommitInfo> commitsInRange(Repository repo, ObjectId base, ObjectId head, int limit) {
        return commitsInRange(repo, base, head, limit, null, null);
    }

    /**
     * The commits of {@code base}..{@code head} inside an optional committer-time window.
     *
     * The window composes with the range rather than replacing it: "the commits merged into this
     * branch between these two days" is a question that neither the endpoints nor the dates answer
     * on their own.
     */
    public static List<CommitInfo> commitsInRange(Repository repo, ObjectId base, ObjectId head, int limit,
                                                  Long sinceEpochSecond, Long untilEpochSecond) {
        return collect(repo, base, head, limit, sinceEpochSecond, untilEpochSecond);
    }

    /**
     * One bounded, newest-first walk shared by both public forms.
     *
     * {@code base} is excluded when present; the time window is applied per commit as described on
     * {@link #commits(Repository, ObjectId, int, Long, Long)}. A failure is logged and the commits
     * collected so far returned: the caller is a picker that must stay usable on a truncated or
     * shallow clone.
     */
    private static List<CommitInfo> collect(Repository repo, ObjectId base, ObjectId head, int limit,
                                            Long sinceEpochSecond, Long untilEpochSecond) {
        List<CommitInfo> out = new ArrayList<>();
        if (head == null || limit <= 0) {
            return out;
        }
        String walkName = base == null ? "commit walk from " + head.name()
                : "range walk " + base.name() + ".." + head.name();
        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(head));
            if (base != null && !base.equals(head)) {
                walk.markUninteresting(walk.parseCommit(base));
            }
            walk.sort(org.eclipse.jgit.revwalk.RevSort.TOPO);
            walk.sort(org.eclipse.jgit.revwalk.RevSort.COMMIT_TIME_DESC, true);
            for (RevCommit commit : walk) {
                int committedAt = commit.getCommitTime();
                if (untilEpochSecond != null && committedAt > untilEpochSecond) {
                    continue;
                }
                if (sinceEpochSecond != null && committedAt < sinceEpochSecond) {
                    break;
                }
                out.add(toInfo(commit));
                if (out.size() >= limit) {
                    break;
                }
            }
        } catch (IOException | RuntimeException e) {
            // A truncated or shallow clone can fail mid-walk; the commits collected so far are still
            // true, and the caller shows the list rather than an error page.
            log.debug("{} stopped early: {}", walkName, describe(e));
        }
        return out;
    }

    /**
     * Whether {@code ancestor} is reachable from {@code descendant}.
     *
     * A range whose base is not an ancestor of its head is not a range at all - it is two unrelated
     * lines of history - and the resulting diff would silently include everything on both sides.
     * The caller uses this to refuse such a run with a sentence instead of producing a report that
     * looks legitimate but compares the wrong things.
     */
    public static boolean isAncestor(Repository repo, ObjectId ancestor, ObjectId descendant) {
        if (ancestor == null || descendant == null) {
            return false;
        }
        if (ancestor.equals(descendant)) {
            return true;
        }
        try (RevWalk walk = new RevWalk(repo)) {
            walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(descendant));
            walk.markStart(walk.parseCommit(ancestor));
            RevCommit base = walk.next();
            return base != null && base.getId().equals(ancestor);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Projects one commit onto the DTO the API and the report serialise. */
    public static CommitInfo toInfo(RevCommit commit) {
        String subject = commit.getShortMessage();
        if (subject != null) {
            subject = subject.strip();
        }
        String author = commit.getAuthorIdent() == null ? "" : commit.getAuthorIdent().getName();
        String date = DateTimeFormatter.ISO_INSTANT
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochSecond(commit.getCommitTime()));
        List<String> parents = new ArrayList<>();
        for (RevCommit parent : commit.getParents()) {
            String id = parent.getId().name();
            parents.add(id.length() > 8 ? id.substring(0, 8) : id);
        }
        return CommitInfo.of(commit.getId().name(), subject, author, date, parents);
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
