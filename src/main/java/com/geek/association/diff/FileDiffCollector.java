package com.geek.association.diff;

import com.geek.association.model.FileChange;
import com.geek.association.source.FileSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Computes the file level change set between two snapshots (blob hashes, not timestamps). */
public class FileDiffCollector {

    private final FileSource base;
    private final FileSource head;

    public FileDiffCollector(FileSource base, FileSource head) {
        this.base = base;
        this.head = head;
    }

    public List<FileChange> collect() {
        List<FileChange> out = new ArrayList<>();
        Set<String> baseFiles = new LinkedHashSet<>(base.files());
        Set<String> headFiles = new LinkedHashSet<>(head.files());
        for (String f : headFiles) {
            String oldHash = base.exists(f) ? base.hash(f) : null;
            String newHash = head.hash(f);
            if (oldHash == null) {
                out.add(change("ADDED", f, null, null, newHash));
            } else if (!oldHash.equals(newHash)) {
                out.add(change("MODIFIED", f, f, oldHash, newHash));
            }
        }
        for (String f : baseFiles) {
            if (!headFiles.contains(f)) {
                out.add(change("DELETED", f, f, base.hash(f), null));
            }
        }
        out.sort((a, b) -> a.path.compareTo(b.path));
        return out;
    }

    private FileChange change(String status, String path, String oldPath, String oldHash, String newHash) {
        FileChange fc = new FileChange();
        fc.status = status;
        fc.path = path;
        fc.oldPath = oldPath == null ? path : oldPath;
        fc.oldHash = oldHash;
        fc.newHash = newHash;
        return fc;
    }

    public Map<String, String> unifiedDiffs(List<FileChange> changes, int maxFiles, int maxLines) {
        Map<String, String> out = new LinkedHashMap<>();
        int count = 0;
        for (FileChange fc : changes) {
            if (count++ >= maxFiles) {
                break;
            }
            String ext = fc.path.contains(".") ? fc.path.substring(fc.path.lastIndexOf('.') + 1).toLowerCase() : "";
            boolean textual = Set.of("java", "xml", "yml", "yaml", "properties", "sql", "json", "gradle", "md")
                    .contains(ext);
            if (!textual) {
                continue;
            }
            String oldText = "DELETED".equals(fc.status) || fc.oldPath == null ? "" : base.read(fc.oldPath);
            String newText = "ADDED".equals(fc.status) ? head.read(fc.path) : head.read(fc.path);
            if (oldText == null) {
                oldText = "";
            }
            if (newText == null) {
                newText = "";
            }
            out.put(fc.path, LineDiff.unified(fc.path, oldText, newText, maxLines));
        }
        return out;
    }
}
