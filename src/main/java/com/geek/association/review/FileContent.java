package com.geek.association.review;

/**
 * Reads the new-side content of a changed file, or returns {@code null} when it is unavailable.
 *
 * The locator's second layer scans the whole new file for a snippet that the diff hunks did not
 * contain. That is only possible during a live run, where the head snapshot is still open;
 * regenerating the AI section of a historical report has only the hunks stored in the report and
 * passes {@link #NONE}, which skips the second layer instead of failing the generation.
 */
@FunctionalInterface
public interface FileContent {

    String read(String path);

    /** The "not available" value used by historical reports. */
    FileContent NONE = path -> null;
}
