package com.geek.association.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Texts {

    private Texts() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static String trim(String s) {
        return s == null ? "" : s.strip();
    }

    public static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replaceAll("\\s+", " ").strip();
        return v;
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    public static boolean containsAny(String haystack, String... needles) {
        String h = lower(haystack);
        for (String n : needles) {
            if (h.contains(lower(n))) {
                return true;
            }
        }
        return false;
    }

    public static String join(List<String> values, String sep) {
        return String.join(sep, values);
    }

    /** Splits a dotted config key into its parents, e.g. a.b.c -> [a.b, a]. */
    public static List<String> keyParents(String key) {
        List<String> out = new ArrayList<>();
        int idx = key.lastIndexOf('.');
        while (idx > 0) {
            out.add(key.substring(0, idx));
            idx = key.lastIndexOf('.', idx - 1);
        }
        return out;
    }

    public static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
