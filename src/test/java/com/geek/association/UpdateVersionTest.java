package com.geek.association;

import com.geek.association.update.Version;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Version parsing and ordering.
 *
 * This is the rule that decides whether a user is told "there is an update", so the interesting
 * cases are the ones a hand-written tag can produce: a v prefix, a missing segment, a pre-release
 * and a number large enough to overflow an int.
 */
class UpdateVersionTest {

    @Test
    void parsesWithAndWithoutTheVPrefix() {
        assertEquals("0.3.0", Version.parse("0.3.0").toString());
        assertEquals("0.3.0", Version.parse("v0.3.0").toString());
        assertEquals("0.3.0", Version.parse("V0.3.0").toString());
        assertEquals("0.3.0", Version.parse("  v0.3.0  ").toString());
    }

    @Test
    void missingSegmentsDefaultToZero() {
        assertEquals("1.2.0", Version.parse("1.2").toString());
        assertEquals("1.0.0", Version.parse("1").toString());
        assertEquals(Version.parse("1.2"), Version.parse("1.2.0"));
        assertEquals(0, Version.parse("1.2").compareTo(Version.parse("1.2.0")));
    }

    @Test
    void preReleaseSortsBelowTheRelease() {
        assertTrue(Version.parse("1.0.0-rc1").compareTo(Version.parse("1.0.0")) < 0,
                "1.0.0-rc1 is not yet 1.0.0");
        assertTrue(Version.parse("1.0.0").isNewerThan(Version.parse("1.0.0-rc1")));
        assertTrue(Version.parse("1.0.0-beta.2").isNewerThan(Version.parse("1.0.0-beta.1")));
        assertTrue(Version.parse("1.0.0-beta.1").compareTo(Version.parse("1.0.0-rc1")) < 0);
        assertEquals("1.2.3-beta.1", Version.parse("v1.2.3-beta.1").toString());
    }

    @Test
    void buildMetadataDoesNotChangePrecedence() {
        assertEquals(Version.parse("1.2.3"), Version.parse("1.2.3+build.7"));
        assertEquals(Version.parse("1.2.3-rc1"), Version.parse("1.2.3-rc1+build.7"));
    }

    @Test
    void numericComparisonIsNotLexicographic() {
        assertTrue(Version.parse("0.10.0").isNewerThan(Version.parse("0.9.0")));
        assertTrue(Version.parse("10.0.0").isNewerThan(Version.parse("9.9.9")));
        assertFalse(Version.parse("0.3.0").isNewerThan(Version.parse("0.3.0")));
    }

    @Test
    void hugeNumbersDoNotOverflow() {
        Version huge = Version.parse("99999999999999999999.0.0");
        assertTrue(huge.isNewerThan(Version.parse("2147483648.0.0")));
        assertTrue(Version.parse("1.99999999999999999999.0")
                .isNewerThan(Version.parse("1.2147483648.0")));
        assertEquals("99999999999999999999.0.0", huge.toString());
    }

    @Test
    void extraSegmentsStillCompare() {
        // Four-part tags exist in the wild; comparing them is safer than silently truncating to
        // three, which would make 1.2.3.4 and 1.2.3 look identical.
        assertTrue(Version.parse("1.2.3.4").isNewerThan(Version.parse("1.2.3")));
        assertTrue(Version.parse("1.2.4.0").isNewerThan(Version.parse("1.2.3.9")));
    }

    @Test
    void invalidInputIsRejected() {
        for (String bad : new String[]{null, "", "   ", "v", "V", "abc", "vabc", "-1.2.3", "..", "x.y.z"}) {
            assertThrows(IllegalArgumentException.class, () -> Version.parse(bad),
                    "expected a rejection for: " + bad);
        }
        assertEquals(null, Version.tryParse("abc"));
    }

    @Test
    void trailingLettersBehaveLikeAPreRelease() {
        // release.sh allows tags such as 0.3.0rc1; they must not fail the whole release list.
        assertEquals("0.3.0-rc1", Version.parse("0.3.0rc1").toString());
        assertTrue(Version.parse("0.3.0rc1").compareTo(Version.parse("0.3.0")) < 0);
    }

    @Test
    void runningVersionIsAlwaysParseable() {
        // In tests there is no jar, so this is the development marker; the point is that it never
        // throws and never compares as something unexpected.
        assertTrue(Version.running().toString().matches("[0-9A-Za-z.\\-]+"));
    }
}
