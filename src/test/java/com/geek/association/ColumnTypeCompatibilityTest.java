package com.geek.association;

import com.geek.association.util.ColumnTypeCompatibility;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A type change is only a contract break when existing values or bindings can stop working. */
class ColumnTypeCompatibilityTest {

    @Test
    void wideningKeepsExistingValuesValid() {
        assertTrue(ColumnTypeCompatibility.safe(ColumnTypeCompatibility.compare("VARCHAR(32)", "VARCHAR(64)")));
        assertTrue(ColumnTypeCompatibility.safe(ColumnTypeCompatibility.compare("INT", "BIGINT")));
        assertTrue(ColumnTypeCompatibility.safe(ColumnTypeCompatibility.compare("DECIMAL(10,2)", "DECIMAL(12,2)")));
        assertTrue(ColumnTypeCompatibility.safe(ColumnTypeCompatibility.compare("VARCHAR(64)", "TEXT")));
        assertTrue(ColumnTypeCompatibility.safe(ColumnTypeCompatibility.compare("DATE", "DATETIME")));
        assertTrue(ColumnTypeCompatibility.safe(ColumnTypeCompatibility.compare("INT", "DECIMAL(12,0)")));
        assertTrue(ColumnTypeCompatibility.safe(ColumnTypeCompatibility.compare("TINYTEXT", "TEXT")));
    }

    @Test
    void narrowingAndFamilyChangesAreBreaks() {
        assertEquals(ColumnTypeCompatibility.Result.NARROWING,
                ColumnTypeCompatibility.compare("VARCHAR(64)", "VARCHAR(32)"));
        assertEquals(ColumnTypeCompatibility.Result.NARROWING,
                ColumnTypeCompatibility.compare("BIGINT", "INT"));
        assertEquals(ColumnTypeCompatibility.Result.NARROWING,
                ColumnTypeCompatibility.compare("DECIMAL(12,2)", "DECIMAL(10,2)"));
        assertEquals(ColumnTypeCompatibility.Result.INCOMPATIBLE,
                ColumnTypeCompatibility.compare("INT", "VARCHAR(32)"));
        assertEquals(ColumnTypeCompatibility.Result.NARROWING,
                ColumnTypeCompatibility.compare("BIGINT", "DECIMAL(10,0)"),
                "BIGINT needs 19 integer digits, DECIMAL(10,0) has 10");
        assertEquals(ColumnTypeCompatibility.Result.NARROWING,
                ColumnTypeCompatibility.compare("LONGTEXT", "TINYTEXT"));
        assertFalse(ColumnTypeCompatibility.safe(
                ColumnTypeCompatibility.compare("VARCHAR(64)", "VARCHAR(32)")));
    }

    @Test
    void unknownOldTypeIsNotSilentlyTreatedAsSafe() {
        assertEquals(ColumnTypeCompatibility.Result.UNKNOWN,
                ColumnTypeCompatibility.compare("", "VARCHAR(64)"));
        assertEquals(ColumnTypeCompatibility.Result.UNKNOWN,
                ColumnTypeCompatibility.compare("SOMETYPE(1)", "OTHERTYPE(2)"));
        assertFalse(ColumnTypeCompatibility.safe(
                ColumnTypeCompatibility.compare("", "VARCHAR(64)")));
    }

    @Test
    void nullabilityNarrowingOnlyMattersToWriters() {
        assertEquals(ColumnTypeCompatibility.Result.NARROWING,
                ColumnTypeCompatibility.compareNullability("true", "false"));
        assertEquals(ColumnTypeCompatibility.Result.WIDENING,
                ColumnTypeCompatibility.compareNullability("false", "true"));
        assertEquals(ColumnTypeCompatibility.Result.SAME,
                ColumnTypeCompatibility.compareNullability("false", "false"));
        assertEquals(ColumnTypeCompatibility.Result.UNKNOWN,
                ColumnTypeCompatibility.compareNullability("", "false"));
    }
}
