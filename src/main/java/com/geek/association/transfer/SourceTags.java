package com.geek.association.transfer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * The canonical rules for user-assigned source tags.
 *
 * <p>Tags are a filing aid, not a contract: nothing in the analysis engine reads them, so a
 * malformed tag must never fail a request. Everything here therefore converges silently instead of
 * throwing - the user gets whatever survived the rules, not an error about a comma.
 *
 * <p>The limits exist so a tag list stays renderable: twelve tags fit on one row of the source
 * table, and a tag longer than {@value #MAX_TAG_LENGTH} characters is a sentence rather than a
 * label. They are applied on the server so the web UI, the CLI and an imported file all end up
 * with the same answer.
 */
public final class SourceTags {

    /** How many tags one source may carry. */
    public static final int MAX_TAG_COUNT = 12;

    /** How long a single tag may be, in characters. */
    public static final int MAX_TAG_LENGTH = 24;

    private SourceTags() {
        // static only
    }

    /** Normalises anything a caller might hand us: a list, a comma-separated string, or null. */
    public static List<String> normalize(Object raw) {
        if (raw == null) {
            return new ArrayList<>();
        }
        if (raw instanceof String text) {
            return normalize(text);
        }
        if (raw instanceof Collection<?> collection) {
            List<String> values = new ArrayList<>();
            for (Object item : collection) {
                if (item != null) {
                    values.add(item.toString());
                }
            }
            return normalize(values);
        }
        return normalize(List.of(raw.toString()));
    }

    /** Normalises a comma-separated string, so "a, b" and ["a","b"] agree. */
    public static List<String> normalize(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return new ArrayList<>();
        }
        // Both comma shapes: a Chinese IME produces the full-width one, and rejecting it would
        // look like the tag was silently dropped.
        return normalize(List.of(commaSeparated.split("[,，]")));
    }

    /**
     * Normalises a collection of tags.
     *
     * Order is the caller's order with duplicates removed, because a tag list the user typed is
     * also the order they expect to see it in. Deduplication ignores case but keeps the first
     * spelling: "Payment" and "payment" are one tag, and the one that was there first wins.
     */
    public static List<String> normalize(Collection<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        LinkedHashSet<String> seenLower = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null) {
                continue;
            }
            // A single element may itself be "a,b" when a list was built from a text field.
            for (String part : value.split("[,，]")) {
                String tag = part.strip();
                if (tag.isEmpty() || tag.length() > MAX_TAG_LENGTH) {
                    continue;
                }
                if (seenLower.add(tag.toLowerCase(Locale.ROOT)) && out.size() < MAX_TAG_COUNT) {
                    out.add(tag);
                }
            }
        }
        return out;
    }

    /**
     * Unions two tag lists: everything already on the source, then whatever is new.
     *
     * Used by import. Existing tags keep their position and spelling so an import never reorders
     * or re-cases a list someone curated by hand.
     */
    public static List<String> merge(Collection<String> existing, Collection<String> incoming) {
        List<String> out = normalize(existing);
        LinkedHashSet<String> seenLower = new LinkedHashSet<>();
        for (String tag : out) {
            seenLower.add(tag.toLowerCase(Locale.ROOT));
        }
        for (String tag : normalize(incoming)) {
            if (out.size() >= MAX_TAG_COUNT) {
                break;
            }
            if (seenLower.add(tag.toLowerCase(Locale.ROOT))) {
                out.add(tag);
            }
        }
        return out;
    }
}
