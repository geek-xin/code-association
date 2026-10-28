package com.geek.association.source;

import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Source tree backed by a Git commit (or the empty tree when the commit is null). */
public class GitTreeSource implements FileSource {

    /** Used when no limit is supplied; matches the value the hard-coded read limit had. */
    public static final int DEFAULT_MAX_FILE_BYTES = 20 * 1024 * 1024;

    private final Repository repository;
    private final ObjectId commitId;
    private final String label;
    private final int maxFiles;
    private final int maxFileBytes;
    private final List<SkippedFile> skipped = new ArrayList<>();
    private Map<String, String> blobs;
    private List<String> cached;
    private RevCommit commit;

    public GitTreeSource(Repository repository, ObjectId commitId, String label, int maxFiles) {
        this(repository, commitId, label, maxFiles, DEFAULT_MAX_FILE_BYTES);
    }

    public GitTreeSource(Repository repository, ObjectId commitId, String label, int maxFiles,
                         int maxFileBytes) {
        this.repository = repository;
        this.commitId = commitId;
        this.label = label;
        this.maxFiles = maxFiles;
        this.maxFileBytes = maxFileBytes;
    }

    /**
     * A view over an explicit path-to-blob map, for trees that exist only in memory.
     *
     * The one caller is a "review only these commits" run: the head snapshot it needs is the base
     * tree with a handful of files replaced, and materialising that as a real commit would mean
     * writing to the user's repository. Reusing this class keeps the read path - including the
     * per-file size limit and the skipped-file bookkeeping - identical to a real tree, so a
     * synthesized snapshot cannot behave differently from an ordinary one.
     */
    public static GitTreeSource ofBlobs(Repository repository, Map<String, String> blobs, String label,
                                        int maxFiles, int maxFileBytes) {
        GitTreeSource source = new GitTreeSource(repository, null, label, maxFiles, maxFileBytes);
        source.blobs = new LinkedHashMap<>(blobs);
        source.cached = new ArrayList<>(source.blobs.keySet());
        source.cached.sort(Comparator.naturalOrder());
        return source;
    }

    /**
     * Every path of a commit's tree mapped to its blob id.
     *
     * Exposed for the same reason as {@link #ofBlobs}: comparing two of these maps is how "which
     * files did this commit change" is answered without a working tree or a diff driver, and
     * {@code .class} / {@code .jar} entries are skipped exactly as {@link #load()} skips them.
     */
    public static Map<String, String> treeEntries(Repository repository, ObjectId commitId, int maxFiles) {
        Map<String, String> out = new LinkedHashMap<>();
        if (commitId == null) {
            return out;
        }
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(commitId);
            try (TreeWalk tw = new TreeWalk(repository)) {
                tw.addTree(commit.getTree());
                tw.setRecursive(true);
                while (tw.next() && out.size() < maxFiles) {
                    String path = tw.getPathString();
                    if (path.endsWith(".class") || path.endsWith(".jar")) {
                        continue;
                    }
                    out.put(path, tw.getObjectId(0).name());
                }
            }
        } catch (IOException | RuntimeException e) {
            // Same contract as load(): a tree that cannot be walked yields what was read so far.
        }
        return out;
    }

    private void load() {
        if (blobs != null) {
            return;
        }
        blobs = new LinkedHashMap<>();
        cached = new ArrayList<>();
        if (commitId == null) {
            return;
        }
        try (RevWalk walk = new RevWalk(repository)) {
            commit = walk.parseCommit(commitId);
            try (TreeWalk tw = new TreeWalk(repository)) {
                tw.addTree(commit.getTree());
                tw.setRecursive(true);
                while (tw.next() && cached.size() < maxFiles) {
                    String path = tw.getPathString();
                    if (path.endsWith(".class") || path.endsWith(".jar")) {
                        continue;
                    }
                    blobs.put(path, tw.getObjectId(0).name());
                    cached.add(path);
                }
            }
        } catch (IOException e) {
            // leave whatever we have
        }
        cached.sort(Comparator.naturalOrder());
    }

    @Override
    public List<String> files() {
        load();
        return cached;
    }

    /**
     * Reads one blob, refusing anything above the per-file limit.
     *
     * JGit signals the size refusal with {@link LargeObjectException}, which is a
     * {@link RuntimeException}: it is not an {@link IOException}, so a plain {@code catch
     * (IOException)} let it escape as an analysis failure. A repository that happens to contain a
     * large binary - a committed SDK, a database dump, a packaged jar with a different extension -
     * would kill the whole run rather than being reported as the one file it is. The size is
     * checked up front so the refusal is a decision of ours and not an exception we happen to
     * survive; the catch stays for the objects whose size JGit only learns while streaming them.
     */
    @Override
    public String read(String path) {
        load();
        String id = blobs.get(path);
        if (id == null) {
            return null;
        }
        ObjectId objectId = ObjectId.fromString(id);
        try {
            ObjectLoader loader = repository.open(objectId, Constants.OBJ_BLOB);
            long size = loader.getSize();
            if (size > maxFileBytes) {
                skip(path, size);
                return null;
            }
            byte[] bytes = loader.getBytes(maxFileBytes);
            for (byte b : bytes) {
                if (b == 0) {
                    return null;
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (LargeObjectException e) {
            // getSize() can be unknown for some loaders; the limit still has to hold.
            skip(path, -1);
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private void skip(String path, long size) {
        for (SkippedFile existing : skipped) {
            if (existing.path().equals(path)) {
                return;
            }
        }
        skipped.add(new SkippedFile(path, size));
    }

    @Override
    public List<SkippedFile> skippedFiles() {
        load();
        return List.copyOf(skipped);
    }

    @Override
    public String hash(String path) {
        load();
        return blobs.get(path);
    }

    @Override
    public boolean exists(String path) {
        load();
        return blobs.containsKey(path);
    }

    @Override
    public String label() {
        return label;
    }

    public RevCommit commit() {
        load();
        return commit;
    }
}
