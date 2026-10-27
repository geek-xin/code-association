package com.geek.association;

import com.geek.association.transfer.SourceTags;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rules for user-assigned source tags: they must converge, never throw, and never reorder. */
class SourceTagsTest {

    @Test
    void splitsCommaSeparatedTextInBothCommaShapes() {
        // A Chinese IME produces the full-width comma; dropping the tail silently would look
        // like the tag was ignored rather than mistyped.
        assertEquals(List.of("a", "b", "c"), SourceTags.normalize("a, b，c"));
    }

    @Test
    void trimsAndDropsEmptyEntries() {
        assertEquals(List.of("api"), SourceTags.normalize(Arrays.asList("  api  ", "", null, "   ")));
    }

    @Test
    void deduplicatesIgnoringCaseButKeepsFirstSpelling() {
        assertEquals(List.of("Payment", "legacy"),
                SourceTags.normalize(Arrays.asList("Payment", "payment", "PAYMENT", "legacy")));
    }

    @Test
    void dropsTagsLongerThanTheLimit() {
        String tooLong = "x".repeat(SourceTags.MAX_TAG_LENGTH + 1);
        assertEquals(List.of("ok"), SourceTags.normalize(Arrays.asList("ok", tooLong)));
    }

    @Test
    void capsTheNumberOfTags() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < SourceTags.MAX_TAG_COUNT + 5; i++) {
            many.add("t" + i);
        }
        assertEquals(SourceTags.MAX_TAG_COUNT, SourceTags.normalize(many).size());
    }

    @Test
    void keepsInsertionOrder() {
        assertEquals(List.of("z", "a", "m"), SourceTags.normalize(Arrays.asList("z", "a", "m")));
    }

    @Test
    void nullsAndBlanksNormaliseToEmpty() {
        assertTrue(SourceTags.normalize((Object) null).isEmpty());
        assertTrue(SourceTags.normalize("").isEmpty());
        assertTrue(SourceTags.normalize("   ").isEmpty());
        assertTrue(SourceTags.normalize((List<String>) null).isEmpty());
    }

    @Test
    void mergeAppendsNewTagsAfterExistingOnes() {
        List<String> merged = SourceTags.merge(List.of("b", "a"), List.of("c", "a"));
        assertEquals(List.of("b", "a", "c"), merged, "existing order survives, duplicates collapse");
    }

    @Test
    void mergeKeepsExistingSpellingWhenIncomingDiffersInCase() {
        List<String> merged = SourceTags.merge(List.of("Payment"), List.of("payment", "new"));
        assertEquals(List.of("Payment", "new"), merged);
    }

    @Test
    void mergeRespectsTheCapEvenWhenExistingIsFull() {
        List<String> existing = new ArrayList<>();
        for (int i = 0; i < SourceTags.MAX_TAG_COUNT; i++) {
            existing.add("e" + i);
        }
        assertEquals(SourceTags.MAX_TAG_COUNT, SourceTags.merge(existing, List.of("extra")).size());
    }

    @Test
    void elementThatItselfContainsACommaIsSplit() {
        // A tag list built from a single text field can arrive as one element holding "a,b".
        assertEquals(List.of("a", "b"), SourceTags.normalize(List.of("a,b")));
    }
}
