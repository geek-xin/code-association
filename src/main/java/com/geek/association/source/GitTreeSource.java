package com.geek.association.source;

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

    private final Repository repository;
    private final ObjectId commitId;
    private final String label;
    private final int maxFiles;
    private Map<String, String> blobs;
    private List<String> cached;
    private RevCommit commit;

    public GitTreeSource(Repository repository, ObjectId commitId, String label, int maxFiles) {
        this.repository = repository;
        this.commitId = commitId;
        this.label = label;
        this.maxFiles = maxFiles;
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

    @Override
    public String read(String path) {
        load();
        String id = blobs.get(path);
        if (id == null) {
            return null;
        }
        try {
            ObjectLoader loader = repository.open(ObjectId.fromString(id), Constants.OBJ_BLOB);
            byte[] bytes = loader.getBytes(20 * 1024 * 1024);
            for (byte b : bytes) {
                if (b == 0) {
                    return null;
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
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
