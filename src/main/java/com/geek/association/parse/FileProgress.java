package com.geek.association.parse;

/**
 * Reports indexing progress as files are parsed: which file, and how far through the run we are.
 *
 * A bare file path was not enough. The UI needs a denominator to show real movement, and the
 * percentage reported by the analysis service has to advance inside a stage - otherwise a stage
 * that covers most of the wall-clock time displays one frozen number the whole way, which is
 * indistinguishable from a hung run.
 */
@FunctionalInterface
public interface FileProgress {

    /**
     * @param path  the file just reached
     * @param done  how many files have been processed, 1-based
     * @param total how many files this stage will process; 0 when the caller cannot know it
     */
    void file(String path, int done, int total);
}
