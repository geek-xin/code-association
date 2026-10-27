package com.geek.association.source;

import java.util.List;
import java.util.Locale;

/** An immutable, read-only view of a source tree at one revision. */
public interface FileSource {

    List<String> files();

    String read(String path);

    String hash(String path);

    boolean exists(String path);

    String label();

    /**
     * Files that were present but deliberately not read, because they are larger than the
     * per-file limit.
     *
     * A file the indexer never opened is a blind spot, and a blind spot that is never named reads
     * exactly like "nothing to find here". The report names these files for that reason. Bearing
     * the size is only one half of it: reading tens of megabytes of generated code would also
     * make a run slower than the review it feeds.
     */
    default List<SkippedFile> skippedFiles() {
        return List.of();
    }

    /** One file that was left unread, with the size that disqualified it. */
    record SkippedFile(String path, long size) {

        /** Shown in the report, so the reader can judge whether the omission matters. */
        public String display() {
            return path + " (" + sizeLabel() + ")";
        }

        /**
         * The size as a reviewer reads it.
         *
         * A negative size means the object refused to report one - JGit only knows it while
         * streaming, and it refuses above the limit (see {@link GitTreeSource#read}). Saying so is
         * more honest than printing a made-up zero.
         */
        public String sizeLabel() {
            return size < 0 ? "大小未知" : String.format(Locale.ROOT, "%.1f MB", size / 1024.0 / 1024.0);
        }
    }
}
