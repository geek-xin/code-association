package com.geek.association.source;

import com.geek.association.config.AppProperties;
import com.geek.association.model.RepoSource;
import com.geek.association.util.Texts;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a configured source into a base/head pair of snapshots.
 *
 * Every supported origin - a local directory, a local git repository, GitLab, GitHub, or a plain
 * remote URL - ends up as the same RevisionPair, so the rest of the pipeline is origin agnostic.
 */
@Component
public class RepositoryPreparer {

    private static final Logger log = LoggerFactory.getLogger(RepositoryPreparer.class);

    private final AppProperties properties;

    public RepositoryPreparer(AppProperties properties) {
        this.properties = properties;
    }

    public static final class Request {
        public RepoSource source;
        /** GIT_DIFF (two refs), WORKTREE_DIFF (uncommitted changes vs HEAD), PULL_REQUEST (PR/MR head). */
        public String mode = "GIT_DIFF";
        public String baseRef;
        public String targetRef;
        public Integer pullRequest;
        public boolean includeUncommitted = true;
    }

    public RevisionPair prepare(Request request) throws IOException {
        RepoSource source = request.source;
        String type = source.type == null ? "LOCAL_DIR" : source.type.toUpperCase();
        String mode = request.mode == null ? "GIT_DIFF" : request.mode.toUpperCase();
        if ("WORKTREE_DIFF".equals(mode) && !isRemote(type)) {
            return worktreeDiff(request);
        }
        if ("PULL_REQUEST".equals(mode) || "MR".equals(mode) || "PR".equals(mode)) {
            return pullRequest(request);
        }
        return switch (type) {
            case "LOCAL_DIR", "DIRECTORY", "FOLDER" -> localDir(request);
            case "GITLAB", "GITHUB", "GIT", "GIT_REMOTE", "REMOTE" -> remote(request);
            default -> localGit(request);
        };
    }

    private boolean isRemote(String type) {
        return "GITLAB".equals(type) || "GITHUB".equals(type) || "GIT".equals(type)
                || "GIT_REMOTE".equals(type) || "REMOTE".equals(type);
    }

    /**
     * Compares the working tree (including uncommitted edits) against HEAD. This is the "local
     * changes" entry point: no commit is required, so a developer can check the blast radius of
     * their current edits before pushing.
     */
    private RevisionPair worktreeDiff(Request request) throws IOException {
        Path root = Path.of(request.source.location).toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve(".git"))) {
            throw new IOException("not a git repository: " + root);
        }
        RevisionPair pair = new RevisionPair();
        pair.repoPath = root.toString();
        pair.note = request.source.name;
        pair.mode = "WORKTREE_DIFF";
        try (Repository repo = GitSupport.open(root)) {
            ObjectId headId = GitSupport.resolve(repo, request.targetRef == null || request.targetRef.isBlank()
                    ? "HEAD" : request.targetRef);
            if (headId == null) {
                throw new IOException("cannot resolve HEAD of " + root);
            }
            pair.base = new GitTreeSource(repo, headId, "HEAD " + headId.name().substring(0, 8),
                    properties.getMaxFiles());
            pair.head = new DirSource(root, "working tree", properties.getMaxFiles());
            pair.baseCommit = headId.name();
            pair.headCommit = "worktree";
            pair.baseRef = headId.name().substring(0, 8);
            pair.headRef = "working tree";
        }
        return pair;
    }

    /**
     * Analyses a GitHub pull request or a GitLab merge request: the head commit is fetched from the
     * provider specific ref, the base is the merge base with the target branch.
     */
    private RevisionPair pullRequest(Request request) throws IOException {
        if (request.pullRequest == null || request.pullRequest <= 0) {
            throw new IOException("pull request number is required for PULL_REQUEST mode");
        }
        RepoSource source = request.source;
        Path cache = properties.dataPath().resolve("repos").resolve(slug(source));
        String token = tokenOf(source);
        Repository repo;
        if (isRemote(source.type == null ? "" : source.type.toUpperCase())) {
            repo = GitSupport.cloneIfMissing(cache, source.location, token);
            try {
                GitSupport.fetchAll(repo, token);
            } catch (IOException ignored) {
                // the cache may already contain the required commits
            }
        } else {
            repo = GitSupport.open(Path.of(source.location).toAbsolutePath().normalize());
        }
        String[] patterns = refPatterns(source.type);
        ObjectId headId = null;
        String usedRef = null;
        for (String pattern : patterns) {
            String ref = pattern.replace("{n}", String.valueOf(request.pullRequest));
            try {
                GitSupport.fetchRef(repo, ref, token);
            } catch (IOException ignored) {
                // provider may not expose this ref; try the next pattern
            }
            ObjectId resolved = repo.resolve(ref + "^{commit}");
            if (resolved == null) {
                resolved = repo.resolve("refs/code-association/" + sanitize(ref) + "^{commit}");
            }
            if (resolved != null) {
                headId = resolved;
                usedRef = ref;
                break;
            }
        }
        if (headId == null) {
            throw new IOException("cannot resolve pull request " + request.pullRequest
                    + " (tried " + String.join(", ", patterns) + "). Check the number and repository access.");
        }
        String targetRef = Texts.isBlank(request.targetRef) ? defaultRef(source, repo) : request.targetRef;
        ObjectId targetId = GitSupport.resolve(repo, targetRef);
        ObjectId baseId = targetId == null ? firstParent(repo, headId)
                : ObjectId.fromString(GitSupport.mergeBase(repo, headId, targetId));
        if (baseId == null) {
            baseId = firstParent(repo, headId);
        }
        RevisionPair pair = new RevisionPair();
        pair.repoPath = repo.getDirectory().getParent();
        pair.note = source.name;
        pair.mode = "PULL_REQUEST";
        pair.pullRequest = request.pullRequest;
        pair.base = baseId == null ? new EmptySource()
                : new GitTreeSource(repo, baseId, "base " + baseId.name().substring(0, 8), properties.getMaxFiles());
        pair.head = new GitTreeSource(repo, headId, "PR#" + request.pullRequest, properties.getMaxFiles());
        pair.baseCommit = baseId == null ? "" : baseId.name();
        pair.headCommit = headId.name();
        pair.baseRef = targetRef;
        pair.headRef = usedRef;
        return pair;
    }

    private String[] refPatterns(String type) {
        String t = type == null ? "" : type.toUpperCase();
        return switch (t) {
            case "GITHUB" -> new String[]{"refs/pull/{n}/head"};
            case "GITLAB" -> new String[]{"refs/merge-requests/{n}/head"};
            default -> new String[]{"refs/pull/{n}/head", "refs/merge-requests/{n}/head"};
        };
    }

    private RevisionPair localDir(Request request) throws IOException {
        Path root = Path.of(request.source.location).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("directory not found: " + root);
        }
        boolean git = Files.isDirectory(root.resolve(".git"));
        if (git && !"WORKTREE".equalsIgnoreCase(request.mode)) {
            RevisionPair pair = new RevisionPair();
            pair.repoPath = root.toString();
            pair.note = request.source.name;
            fillGitPair(pair, root, request, null);
            return pair;
        }
        RevisionPair pair = new RevisionPair();
        pair.mode = "WORKTREE";
        pair.note = request.source.name;
        DirSource head = new DirSource(root, "working tree", properties.getMaxFiles());
        pair.head = head;
        pair.base = new EmptySource();
        pair.headRef = "worktree";
        pair.baseRef = "empty";
        pair.headCommit = "";
        pair.baseCommit = "";
        if (git) {
            try (Repository repo = GitSupport.open(root)) {
                ObjectId headId = repo.resolve("HEAD^{commit}");
                if (headId != null) {
                    pair.headCommit = headId.name();
                }
            } catch (IOException ignored) {
                // keep the plain directory view
            }
        }
        return pair;
    }

    private RevisionPair localGit(Request request) throws IOException {
        Path root = Path.of(request.source.location).toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve(".git"))) {
            throw new IOException("not a git repository: " + root);
        }
        RevisionPair pair = new RevisionPair();
        pair.repoPath = root.toString();
        pair.note = request.source.name;
        fillGitPair(pair, root, request, null);
        return pair;
    }

    private RevisionPair remote(Request request) throws IOException {
        Path cache = properties.dataPath().resolve("repos").resolve(slug(request.source));
        String token = tokenOf(request.source);
        Repository repo = GitSupport.cloneIfMissing(cache, request.source.location, token);
        try {
            GitSupport.fetchAll(repo, token);
        } catch (IOException e) {
            // offline / no permission: fall back to the local cache if it has history
            if (repo.resolve("HEAD^{commit}") == null) {
                throw e;
            }
        }
        RevisionPair pair = new RevisionPair();
        pair.repoPath = cache.toString();
        pair.note = request.source.name;
        fillGitPair(pair, cache, request, token);
        return pair;
    }

    private void fillGitPair(RevisionPair pair, Path root, Request request, String token) throws IOException {
        try (Repository repo = GitSupport.open(root)) {
            String targetRef = Texts.isBlank(request.targetRef) ? defaultRef(request.source, repo) : request.targetRef;
            ObjectId headId = GitSupport.resolve(repo, targetRef);
            if (headId == null && !Texts.isBlank(targetRef)) {
                GitSupport.fetchRef(repo, "refs/heads/" + targetRef, token);
                headId = GitSupport.resolve(repo, targetRef);
            }
            if (headId == null) {
                throw new IOException("cannot resolve target ref: " + targetRef);
            }
            ObjectId baseId = null;
            String baseRef = request.baseRef;
            if (!Texts.isBlank(baseRef)) {
                baseId = GitSupport.resolve(repo, baseRef);
                if (baseId == null) {
                    GitSupport.fetchRef(repo, "refs/heads/" + baseRef, token);
                    baseId = GitSupport.resolve(repo, baseRef);
                }
                if (baseId == null) {
                    throw new IOException("cannot resolve base ref: " + baseRef);
                }
            } else {
                // default base: the first parent of the target commit. A root commit has no parent,
                // in which case the base is the empty tree and every file counts as added.
                baseId = firstParent(repo, headId);
            }
            if (baseId != null && baseId.equals(headId)) {
                ObjectId parent = firstParent(repo, headId);
                if (parent != null) {
                    baseId = parent;
                }
            }
            pair.base = baseId == null ? new EmptySource() : new GitTreeSource(repo, baseId,
                    "base " + shortName(repo, baseId), properties.getMaxFiles());
            pair.head = new GitTreeSource(repo, headId, "head " + shortName(repo, headId), properties.getMaxFiles());
            pair.baseCommit = baseId == null ? "" : baseId.name();
            pair.headCommit = headId.name();
            pair.baseRef = Texts.isBlank(request.baseRef) ? (baseId == null ? "" : shortName(repo, baseId)) : request.baseRef;
            pair.headRef = targetRef;
            pair.mode = "GIT_DIFF";
        }
    }

    private ObjectId firstParent(Repository repo, ObjectId commitId) {
        try (org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(repo)) {
            org.eclipse.jgit.revwalk.RevCommit commit = walk.parseCommit(commitId);
            return commit.getParentCount() > 0 ? commit.getParent(0).getId() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private String shortName(Repository repo, ObjectId id) {
        if (id == null) {
            return "";
        }
        try {
            String name = repo.getBranch();
            if (name != null && id.equals(repo.resolve("HEAD^{commit}"))) {
                return name;
            }
        } catch (IOException ignored) {
            // fall through
        }
        return id.name().substring(0, 8);
    }

    private String defaultRef(RepoSource source, Repository repo) {
        if (!Texts.isBlank(source.defaultRef)) {
            return source.defaultRef;
        }
        try {
            String branch = repo.getBranch();
            if (branch != null && !branch.isBlank()) {
                return branch;
            }
        } catch (IOException ignored) {
            // fall through
        }
        return "HEAD";
    }

    private String tokenOf(RepoSource source) {
        if (Texts.isBlank(source.credentialEnv)) {
            return null;
        }
        return System.getenv(source.credentialEnv);
    }

    private static String sanitize(String ref) {
        return ref.replaceAll("[^A-Za-z0-9._/-]", "_").replace('/', '_');
    }

    /**
     * Stable, collision free cache directory per remote repository.
     *
     * The id alone is not enough (a CLI run has no id yet) and the name alone is not enough
     * (two different repositories may share a name), so both are combined with a hash of the URL.
     */
    private String slug(RepoSource source) {
        String raw = source.name == null ? "repo" : source.name;
        String clean = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        String urlHash = com.geek.association.util.Hashes.sha1(source.location == null ? "" : source.location);
        return source.id + "-" + clean + "-" + urlHash.substring(0, 10);
    }

    /**
     * Deletes the local clone cached for a remote source, if any.
     *
     * Only the directory this service created under the data dir is removed - never the location
     * the user configured. Returns true when something was deleted.
     */
    public boolean deleteCache(RepoSource source) throws IOException {
        if (source == null || source.type == null || !isRemote(source.type.toUpperCase())) {
            return false;
        }
        Path cache = properties.dataPath().resolve("repos").resolve(slug(source));
        // Guard against a slug that somehow escapes the cache root before deleting recursively.
        Path reposRoot = properties.dataPath().resolve("repos").toAbsolutePath().normalize();
        if (!cache.toAbsolutePath().normalize().startsWith(reposRoot) || !Files.isDirectory(cache)) {
            return false;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(cache)) {
            java.util.List<Path> paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
        return true;
    }

    /**
     * Branches and tags for a location that is not saved yet.
     *
     * The source form needs this the moment a path or URL is entered, so it works from the raw
     * inputs rather than from a stored source. A local repository is read directly; a remote one
     * is queried with {@code ls-remote}, which avoids cloning until an analysis actually runs.
     *
     * Never throws for an unreachable location: the caller is a form field, and a failed lookup
     * must not block saving. The returned map carries an empty list plus a note instead.
     */
    public Map<String, Object> refsForLocation(String type, String location, String credentialEnv) {
        String normalizedType = type == null ? "LOCAL_DIR" : type.toUpperCase();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("branches", List.of());
        out.put("tags", List.of());
        out.put("defaultRef", "");
        if (Texts.isBlank(location)) {
            out.put("note", "位置为空");
            return out;
        }
        if (isRemote(normalizedType)) {
            long started = System.nanoTime();
            String masked = GitSupport.maskCredentials(location);
            try {
                String token = Texts.isBlank(credentialEnv) ? null : System.getenv(credentialEnv);
                log.debug("refs lookup for {} (credential env: {})",
                        masked, Texts.isBlank(credentialEnv) ? "none" : credentialEnv);
                java.util.Collection<org.eclipse.jgit.lib.Ref> refs =
                        GitSupport.lsRemote(GitSupport.withCredentials(location, token), token);
                List<String> branches = new java.util.ArrayList<>();
                List<String> tags = new java.util.ArrayList<>();
                for (org.eclipse.jgit.lib.Ref ref : refs) {
                    String name = ref.getName();
                    if (name.startsWith("refs/heads/")) {
                        branches.add(name.substring("refs/heads/".length()));
                    } else if (name.startsWith("refs/tags/") && !name.endsWith("^{}")) {
                        tags.add(name.substring("refs/tags/".length()));
                    }
                }
                java.util.Collections.sort(branches);
                java.util.Collections.sort(tags);
                out.put("branches", branches);
                out.put("tags", tags);
                out.put("defaultRef", branches.contains("main") ? "main"
                        : branches.contains("master") ? "master"
                        : branches.isEmpty() ? "" : branches.get(0));
                log.debug("refs lookup for {} found {} branches, {} tags in {} ms",
                        masked, branches.size(), tags.size(), elapsedMillis(started));
                return out;
            } catch (IOException | RuntimeException e) {
                // A form field cannot show a stack trace; keep the reason but phrase it for people.
                // The log keeps the full cause chain, because the UI detail is one short sentence and
                // "why did the same URL work yesterday" is only answerable from the chain.
                String cause = GitSupport.describe(e);
                log.warn("refs lookup for {} failed after {} ms: {}",
                        masked, elapsedMillis(started), cause);
                out.put("note", refsFailureNote(cause));
                // The URL that failed may carry a token (https://oauth2:<token>@host/...), so the
                // detail is masked before it reaches the browser or any saved response.
                out.put("detail", GitSupport.maskCredentials(
                        e.getMessage() == null ? cause : e.getMessage()));
                return out;
            }
        }
        Path path;
        try {
            path = PathBrowser.resolve(location);
        } catch (RuntimeException e) {
            out.put("note", "路径格式不正确");
            return out;
        }
        if (!Files.isDirectory(path.resolve(".git"))) {
            out.put("note", "这个目录不是 Git 仓库，没有可选的提交或分支");
            return out;
        }
        try (Repository repo = GitSupport.open(path)) {
            List<String> branches = new java.util.ArrayList<>();
            List<String> tags = new java.util.ArrayList<>();
            for (org.eclipse.jgit.lib.Ref ref : repo.getRefDatabase().getRefs()) {
                String name = ref.getName();
                if (name.startsWith("refs/heads/")) {
                    branches.add(name.substring("refs/heads/".length()));
                } else if (name.startsWith("refs/remotes/origin/") && !name.endsWith("/HEAD")) {
                    branches.add(name.substring("refs/remotes/origin/".length()));
                } else if (name.startsWith("refs/tags/")) {
                    tags.add(name.substring("refs/tags/".length()));
                }
            }
            java.util.Collections.sort(branches);
            java.util.Collections.sort(tags);
            String head = "";
            try {
                head = repo.getBranch();
            } catch (IOException ignored) {
                // detached head
            }
            out.put("branches", branches.stream().distinct().toList());
            out.put("tags", tags);
            out.put("defaultRef", Texts.isBlank(head) ? "" : head);
            return out;
        } catch (IOException | RuntimeException e) {
            out.put("note", "无法读取该仓库");
            out.put("detail", e.getMessage());
            return out;
        }
    }

    /**
     * Resolves the commit a source currently points at, without preparing a full revision pair.
     *
     * The daily scan needs this cheap check first: fetching and indexing every program just to
     * discover nothing changed would be wasteful. Remote sources are refreshed first so a push
     * made elsewhere is seen; offline failures fall back to the cached clone.
     *
     * @return the commit id, or null when the location cannot be resolved
     */
    public String currentCommit(RepoSource source) {
        String type = source.type == null ? "LOCAL_DIR" : source.type.toUpperCase();
        try {
            if (isRemote(type)) {
                Path cache = properties.dataPath().resolve("repos").resolve(slug(source));
                String token = tokenOf(source);
                try (Repository repo = GitSupport.cloneIfMissing(cache, source.location, token)) {
                    try {
                        GitSupport.fetchAll(repo, token);
                    } catch (IOException ignored) {
                        // offline: the cached clone still tells us what it last saw
                    }
                    String ref = Texts.isBlank(source.defaultRef) ? "HEAD" : source.defaultRef;
                    ObjectId id = GitSupport.resolve(repo, ref);
                    return id == null ? null : id.name();
                }
            }
            Path root = Path.of(source.location).toAbsolutePath().normalize();
            if (!Files.isDirectory(root.resolve(".git"))) {
                // A plain directory has no commit to compare; the caller decides how to rescan it.
                return null;
            }
            try (Repository repo = GitSupport.open(root)) {
                String ref = Texts.isBlank(source.defaultRef) ? "HEAD" : source.defaultRef;
                ObjectId id = GitSupport.resolve(repo, ref);
                return id == null ? null : id.name();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Whether the source's location can be reached right now.
     *
     * Local sources point at a directory that may have been moved or deleted; remote sources are
     * reachable as long as the URL is well formed, because the clone is created on demand.
     */
    public boolean locationExists(RepoSource source) {
        String type = source.type == null ? "LOCAL_DIR" : source.type.toUpperCase();
        if (isRemote(type)) {
            return !Texts.isBlank(source.location);
        }
        if (Texts.isBlank(source.location)) {
            return false;
        }
        try {
            return Files.isDirectory(Path.of(source.location).toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    /**
     * Turns a transport failure into the one sentence the branch dropdown can show.
     *
     * "Check the address, network or credentials" covers three very different fixes and leaves the
     * user guessing. The cause chain usually names which one it is, so name it here instead.
     */
    static String refsFailureNote(String cause) {
        String c = Texts.lower(cause);
        // SSH URLs deserve their own answer: the JGit distribution this service ships has no SSH
        // transport, so git@host:group/repo.git fails inside JGit with a null SshSessionFactory and
        // reads like a network problem. GitLab hands out exactly that URL in its clone box, so this
        // is the mistake people actually make.
        if (c.contains("sshsessionfactory") || c.contains("ssh://")
                || c.contains("git@") || c.contains("ssh not supported")) {
            return "不支持 SSH 地址：请改用 https 形式，例如 https://gitlab.com/group/repo.git";
        }
        // "URI not supported" means the host was given without a repository path.
        if (c.contains("uri not supported")) {
            return "地址不完整：请填到仓库一级，例如 https://gitlab.com/group/repo.git";
        }
        if (c.contains("timeout") || c.contains("timed out") || c.contains("read timed out")) {
            return "读取远程分支超时：网络到 GitLab/GitHub 太慢或不可达，可稍后重试";
        }
        if (c.contains("authentication") || c.contains("not authorized")
                || c.contains("401") || c.contains("403")) {
            return "远程仓库需要认证：请确认地址正确，私有仓库还要配置有效的凭据环境变量";
        }
        if (c.contains("not found") || c.contains("404") || c.contains("repository does not exist")) {
            return "远程仓库不存在或无权访问：请检查地址拼写、命名空间与仓库名";
        }
        if (c.contains("unknownhost") || c.contains("unable to resolve")
                || c.contains("connection refused") || c.contains("connect timed out")) {
            return "无法连接远程主机：请检查网络、代理或主机名";
        }
        if (c.contains("ssl") || c.contains("certificate")) {
            return "TLS 证书校验失败：请检查代理设置或系统证书";
        }
        if (c.contains("hung up") || c.contains("connection reset")) {
            return "连接被远程中断：地址可能不完整，或代理拦截了 Git 协议";
        }
        return "无法读取远程分支，请检查地址、网络或凭据";
    }

    /** True when the source has a git history at all, i.e. a commit can be compared. */
    public boolean isVersioned(RepoSource source) {
        String type = source.type == null ? "LOCAL_DIR" : source.type.toUpperCase();
        if (isRemote(type)) {
            return true;
        }
        Path root = Path.of(source.location).toAbsolutePath().normalize();
        return Files.isDirectory(root.resolve(".git"));
    }

    /** Lists the branches and tags a source can be compared against, for the UI pickers. */
    public Map<String, Object> refs(RepoSource source) throws IOException {
        String type = source.type == null ? "LOCAL_DIR" : source.type.toUpperCase();
        Path path;
        String token = null;
        if (isRemote(type)) {
            path = properties.dataPath().resolve("repos").resolve(slug(source));
            token = tokenOf(source);
            if (!Files.isDirectory(path.resolve(".git"))) {
                return Map.of("branches", List.of(), "tags", List.of(), "defaultRef", "",
                        "note", "repository is cloned on the first analysis");
            }
        } else {
            path = Path.of(source.location).toAbsolutePath().normalize();
        }
        try (Repository repo = GitSupport.open(path)) {
            if (isRemote(type)) {
                try {
                    GitSupport.fetchAll(repo, token);
                } catch (IOException e) {
                    // offline: report whatever the cache already has. Worth a log line, because a
                    // stale ref list looks like a bug in this application until you know the fetch failed.
                    log.warn("refs of {} served from the cache, refresh failed: {}",
                            source.name, GitSupport.describe(e));
                }
            }
            List<String> branches = new java.util.ArrayList<>();
            List<String> tags = new java.util.ArrayList<>();
            for (org.eclipse.jgit.lib.Ref ref : repo.getRefDatabase().getRefs()) {
                String name = ref.getName();
                if (name.startsWith("refs/heads/")) {
                    branches.add(name.substring("refs/heads/".length()));
                } else if (name.startsWith("refs/remotes/origin/") && !name.endsWith("/HEAD")) {
                    branches.add(name.substring("refs/remotes/origin/".length()));
                } else if (name.startsWith("refs/tags/")) {
                    tags.add(name.substring("refs/tags/".length()));
                }
            }
            java.util.Collections.sort(branches);
            java.util.Collections.sort(tags);
            String head = "";
            try {
                head = repo.getBranch();
            } catch (IOException ignored) {
                // detached head
            }
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("branches", branches.stream().distinct().toList());
            out.put("tags", tags);
            out.put("defaultRef", Texts.isBlank(source.defaultRef) ? head : source.defaultRef);
            return out;
        }
    }

    public Map<String, Object> describe(RevisionPair pair) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", pair.mode);
        out.put("baseRef", pair.baseRef);
        out.put("headRef", pair.headRef);
        out.put("baseCommit", pair.baseCommit);
        out.put("headCommit", pair.headCommit);
        out.put("baseLabel", pair.base == null ? "" : pair.base.label());
        out.put("headLabel", pair.head == null ? "" : pair.head.label());
        return out;
    }
}