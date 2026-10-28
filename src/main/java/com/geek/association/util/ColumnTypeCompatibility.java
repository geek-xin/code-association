package com.geek.association.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a column type change can break code that reads or writes the column.
 *
 * Not every {@code ALTER TABLE ... MODIFY} is a break. Widening {@code VARCHAR(64)} to
 * {@code VARCHAR(128)}, or {@code INT} to {@code BIGINT}, keeps every existing value and every
 * existing binding valid. Reporting those as cross-program impact is the database equivalent of
 * reporting a method body comment change: the text moved, the contract did not.
 *
 * The comparison is intentionally conservative. When the old type is unknown, or the two types are
 * from different families without a safe promotion, the result is {@link Result#UNKNOWN} and the
 * caller keeps the match (with lower confidence) rather than silently dropping a real risk.
 */
public final class ColumnTypeCompatibility {

    public enum Result {
        /** The type did not change. */
        SAME,
        /** Every value and binding that was valid before is still valid. */
        WIDENING,
        /** Values can be truncated, rounded or rejected. */
        NARROWING,
        /** A different type family: reads/writes can fail or be silently converted. */
        INCOMPATIBLE,
        /** Not enough information to decide. */
        UNKNOWN
    }

    private static final Pattern BASE = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9_]*)");
    private static final Pattern ARGS = Pattern.compile("\\(([^)]*)\\)");

    private ColumnTypeCompatibility() {
    }

    public static Result compare(String oldType, String newType) {
        String before = normalize(oldType);
        String after = normalize(newType);
        if (before.isEmpty() || after.isEmpty()) {
            return Result.UNKNOWN;
        }
        if (before.equals(after)) {
            return Result.SAME;
        }
        Family oldFamily = family(before);
        Family newFamily = family(after);
        if (oldFamily == Family.UNKNOWN || newFamily == Family.UNKNOWN) {
            return Result.UNKNOWN;
        }
        if (oldFamily == newFamily) {
            return compareSameFamily(oldFamily, before, after);
        }
        return compareDifferentFamilies(oldFamily, newFamily, before, after);
    }

    /** True when the change cannot invalidate an existing read or write. */
    public static boolean safe(Result result) {
        return result == Result.SAME || result == Result.WIDENING;
    }

    /**
     * Nullability compatibility for writers: turning a nullable column into NOT NULL can reject an
     * existing insert/update that omitted the value; the reverse direction is always safe.
     */
    public static Result compareNullability(String oldNullable, String newNullable) {
        if (oldNullable == null || newNullable == null || oldNullable.isBlank() || newNullable.isBlank()) {
            return Result.UNKNOWN;
        }
        boolean before = Boolean.parseBoolean(oldNullable);
        boolean after = Boolean.parseBoolean(newNullable);
        if (before == after) {
            return Result.SAME;
        }
        return after ? Result.WIDENING : Result.NARROWING;
    }

    public static String normalize(String type) {
        if (type == null) {
            return "";
        }
        String value = type.toUpperCase(Locale.ROOT)
                .replace("UNSIGNED", "")
                .replace("ZEROFILL", "")
                .replaceAll("\\s+", " ")
                .trim();
        return value;
    }

    private static Family family(String type) {
        Matcher m = BASE.matcher(type);
        if (!m.find()) {
            return Family.UNKNOWN;
        }
        String base = m.group(1);
        return switch (base) {
            case "TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT" -> Family.INTEGER;
            case "DECIMAL", "NUMERIC", "NUMBER" -> Family.DECIMAL;
            case "FLOAT", "REAL", "DOUBLE", "BINARY_FLOAT", "BINARY_DOUBLE" -> Family.FLOAT;
            case "CHAR", "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2", "NCHAR" -> Family.STRING;
            case "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "CLOB", "NCLOB" -> Family.TEXT;
            case "BLOB", "BINARY", "VARBINARY", "BYTEA", "RAW", "IMAGE" -> Family.BINARY;
            case "DATE" -> Family.DATE;
            case "TIME" -> Family.TIME;
            case "DATETIME", "TIMESTAMP", "TIMESTAMPTZ", "SMALLDATETIME" -> Family.DATETIME;
            case "BOOLEAN", "BOOL", "BIT" -> Family.BOOLEAN;
            case "JSON", "JSONB" -> Family.JSON;
            case "UUID", "UNIQUEIDENTIFIER" -> Family.UUID;
            case "ENUM", "SET" -> Family.ENUM;
            default -> Family.UNKNOWN;
        };
    }

    private static Result compareSameFamily(Family family, String before, String after) {
        return switch (family) {
            case STRING -> compareLength(before, after);
            case TEXT -> compareRank(textRank(before), textRank(after));
            case INTEGER -> compareRank(integerRank(before), integerRank(after));
            case FLOAT -> compareRank(floatRank(before), floatRank(after));
            case DECIMAL -> compareDecimal(before, after);
            case BINARY -> compareLength(before, after);
            case DATE, TIME, BOOLEAN, JSON, UUID, ENUM -> Result.SAME;
            case DATETIME -> Result.SAME;
            case UNKNOWN -> Result.UNKNOWN;
        };
    }

    private static Result compareDifferentFamilies(Family before, Family after,
                                                   String oldType, String newType) {
        // Safe promotions that keep every existing value representable.
        if (before == Family.INTEGER && after == Family.DECIMAL) {
            int[] decimal = args(newType);
            int digits = integerDigits(oldType);
            if (decimal.length == 0 || digits <= 0) {
                return Result.UNKNOWN;
            }
            int precision = decimal[0];
            int scale = decimal.length > 1 ? decimal[1] : 0;
            // DECIMAL(p,s) has p-s integer digits. BIGINT needs 19 of them; INT needs 10.
            return precision - scale >= digits ? Result.WIDENING : Result.NARROWING;
        }
        if (before == Family.INTEGER && after == Family.FLOAT) {
            return Result.UNKNOWN; // large integers lose precision in floating point
        }
        if ((before == Family.STRING || before == Family.TEXT) && after == Family.TEXT) {
            return Result.WIDENING;
        }
        if (before == Family.TEXT && (after == Family.STRING || after == Family.BINARY)) {
            return Result.NARROWING;
        }
        if (before == Family.STRING && after == Family.BINARY) {
            return Result.INCOMPATIBLE;
        }
        if (before == Family.DATE && after == Family.DATETIME) {
            return Result.WIDENING;
        }
        if (before == Family.DATETIME && after == Family.DATE) {
            return Result.NARROWING;
        }
        if (before == Family.DATETIME && after == Family.TIME) {
            return Result.NARROWING;
        }
        if (before == Family.BOOLEAN && after == Family.INTEGER) {
            return Result.UNKNOWN;
        }
        return Result.INCOMPATIBLE;
    }

    private static Result compareLength(String before, String after) {
        Integer oldLength = firstArg(before);
        Integer newLength = firstArg(after);
        if (oldLength == null || newLength == null) {
            // A missing length is not a licence to guess; e.g. VARCHAR(64) -> VARCHAR.
            return Result.UNKNOWN;
        }
        if (newLength.equals(oldLength)) {
            return Result.SAME;
        }
        return newLength > oldLength ? Result.WIDENING : Result.NARROWING;
    }

    private static Result compareRank(int before, int after) {
        if (before <= 0 || after <= 0) {
            return Result.UNKNOWN;
        }
        if (before == after) {
            return Result.SAME;
        }
        return after > before ? Result.WIDENING : Result.NARROWING;
    }

    private static Result compareDecimal(String before, String after) {
        int[] oldArgs = args(before);
        int[] newArgs = args(after);
        if (oldArgs.length == 0 || newArgs.length == 0) {
            return Result.UNKNOWN;
        }
        int oldPrecision = oldArgs[0];
        int oldScale = oldArgs.length > 1 ? oldArgs[1] : 0;
        int newPrecision = newArgs[0];
        int newScale = newArgs.length > 1 ? newArgs[1] : 0;
        if (newPrecision >= oldPrecision && newScale >= oldScale) {
            return newPrecision == oldPrecision && newScale == oldScale ? Result.SAME : Result.WIDENING;
        }
        return Result.NARROWING;
    }

    private static int integerRank(String type) {
        Matcher m = BASE.matcher(type);
        if (!m.find()) {
            return -1;
        }
        return switch (m.group(1)) {
            case "TINYINT" -> 1;
            case "SMALLINT" -> 2;
            case "MEDIUMINT" -> 3;
            case "INT", "INTEGER" -> 4;
            case "BIGINT" -> 5;
            default -> -1;
        };
    }

    private static int textRank(String type) {
        Matcher m = BASE.matcher(type);
        if (!m.find()) {
            return -1;
        }
        return switch (m.group(1)) {
            case "TINYTEXT" -> 1;
            case "TEXT" -> 2;
            case "MEDIUMTEXT" -> 3;
            case "LONGTEXT", "CLOB", "NCLOB" -> 4;
            default -> -1;
        };
    }

    private static int integerDigits(String type) {
        Matcher m = BASE.matcher(type);
        if (!m.find()) {
            return -1;
        }
        return switch (m.group(1)) {
            case "TINYINT" -> 3;
            case "SMALLINT" -> 5;
            case "MEDIUMINT" -> 8;
            case "INT", "INTEGER" -> 10;
            case "BIGINT" -> 19;
            default -> -1;
        };
    }

    private static int floatRank(String type) {
        Matcher m = BASE.matcher(type);
        if (!m.find()) {
            return -1;
        }
        return switch (m.group(1)) {
            case "REAL" -> 1;
            case "FLOAT" -> 2;
            case "DOUBLE", "BINARY_DOUBLE" -> 3;
            default -> -1;
        };
    }

    private static Integer firstArg(String type) {
        int[] values = args(type);
        return values.length == 0 ? null : values[0];
    }

    private static int[] args(String type) {
        Matcher m = ARGS.matcher(type);
        if (!m.find()) {
            return new int[0];
        }
        String[] parts = m.group(1).split(",");
        int[] out = new int[parts.length];
        int count = 0;
        for (String part : parts) {
            try {
                out[count++] = Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                return new int[0];
            }
        }
        return java.util.Arrays.copyOf(out, count);
    }

    private enum Family {
        INTEGER, DECIMAL, FLOAT, STRING, TEXT, BINARY, DATE, TIME, DATETIME, BOOLEAN, JSON, UUID, ENUM, UNKNOWN
    }
}
