package com.geek.association.update;

import com.geek.association.util.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * A semantic version as published by the GitHub releases of this project.
 *
 * Hand-rolled rather than pulled from a dependency: the rules we actually need are three numeric
 * parts plus an optional pre-release suffix, and the pom stays free of a new library for that.
 *
 * Two deliberate choices:
 *
 * <ul>
 *   <li><b>Numbers are {@link BigInteger}.</b> A tag such as {@code 99999999999999999999.0.0} must
 *       still compare instead of overflowing;</li>
 *   <li><b>Segments beyond the third are compared, not dropped.</b> {@code 1.2.3.4} therefore sorts
 *       above {@code 1.2.3} while {@code 1.2} and {@code 1.2.0} stay equal, which keeps a
 *       four-part tag from being silently treated as its three-part prefix.</li>
 * </ul>
 */
public final class Version implements Comparable<Version> {

    private static final Logger log = LoggerFactory.getLogger(Version.class);

    /** Shown when the process was not started from a packaged jar and there is no build number. */
    private static final String DEV_VERSION = "0.0.0-dev";

    private static final Version RUNNING = resolveRunning();

    private final List<BigInteger> numbers;
    private final String preRelease;
    private final String normalized;

    private Version(List<BigInteger> numbers, String preRelease) {
        this.numbers = List.copyOf(numbers);
        this.preRelease = preRelease;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < numbers.size(); i++) {
            if (i > 0) {
                text.append('.');
            }
            text.append(numbers.get(i));
        }
        if (preRelease != null) {
            text.append('-').append(preRelease);
        }
        this.normalized = text.toString();
    }

    /**
     * Parses a tag such as {@code 0.3.0}, {@code v0.3.0}, {@code 1.2} or {@code 1.2.3-beta.1}.
     *
     * @throws IllegalArgumentException when the text carries no usable number at all
     */
    public static Version parse(String raw) {
        String text = Texts.trim(raw);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("版本号不能为空");
        }
        if (text.charAt(0) == 'v' || text.charAt(0) == 'V') {
            text = text.substring(1).strip();
            if (text.isEmpty()) {
                throw new IllegalArgumentException("版本号缺少数字部分：" + raw);
            }
        }
        // Build metadata ("1.2.3+build.7") never changes precedence in semver, so drop it early
        // instead of letting it leak into the pre-release comparison.
        int plus = text.indexOf('+');
        if (plus >= 0) {
            text = text.substring(0, plus);
        }
        String preRelease = null;
        int dash = text.indexOf('-');
        if (dash >= 0) {
            String suffix = text.substring(dash + 1).strip();
            text = text.substring(0, dash);
            if (!suffix.isEmpty()) {
                preRelease = suffix;
            }
        }
        if (text.isEmpty()) {
            throw new IllegalArgumentException("版本号缺少数字部分：" + raw);
        }
        List<BigInteger> numbers = new ArrayList<>();
        for (String segment : text.split("\\.")) {
            String part = segment.strip();
            int digits = 0;
            while (digits < part.length() && Character.isDigit(part.charAt(digits))) {
                digits++;
            }
            if (digits == 0) {
                throw new IllegalArgumentException("版本号格式不正确：" + raw);
            }
            numbers.add(new BigInteger(part.substring(0, digits)));
            if (digits < part.length()) {
                // Tolerate tags like "1.2.3rc1": trailing letters behave like a pre-release marker.
                // Only the first such suffix is kept; anything after it cannot be a number anyway.
                if (preRelease == null) {
                    preRelease = part.substring(digits);
                }
                break;
            }
        }
        if (numbers.isEmpty()) {
            // ".." and friends split into nothing at all; without this they would silently become
            // 0.0.0 and look like a real release.
            throw new IllegalArgumentException("版本号格式不正确：" + raw);
        }
        while (numbers.size() < 3) {
            numbers.add(BigInteger.ZERO);
        }
        return new Version(numbers, preRelease);
    }

    /** Parses without throwing, for tag lists where one malformed entry must not hide the rest. */
    public static Version tryParse(String raw) {
        try {
            return parse(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The version this process is running.
     *
     * Read from the manifest of the jar that loaded this class. In an IDE or a test there is no
     * such jar, and a fabricated number would be worse than an obviously development-looking one,
     * so the caller sees {@code 0.0.0-dev} and every version from GitHub counts as newer.
     */
    public static Version running() {
        return RUNNING;
    }

    private static Version resolveRunning() {
        String fromManifest = JarLocation.manifestVersion();
        Version parsed = fromManifest == null ? null : tryParse(fromManifest);
        if (parsed == null) {
            // A label is better than a lie: 0.0.0-dev makes every real release look newer, which is
            // the safe direction to be wrong in for an installation that has no build number.
            log.debug("no implementation version in the running jar, reporting {}", DEV_VERSION);
        }
        return parsed != null ? parsed : parse(DEV_VERSION);
    }

    @Override
    public int compareTo(Version other) {
        int length = Math.max(numbers.size(), other.numbers.size());
        for (int i = 0; i < length; i++) {
            BigInteger left = i < numbers.size() ? numbers.get(i) : BigInteger.ZERO;
            BigInteger right = i < other.numbers.size() ? other.numbers.get(i) : BigInteger.ZERO;
            int comparison = left.compareTo(right);
            if (comparison != 0) {
                return comparison;
            }
        }
        return comparePreRelease(preRelease, other.preRelease);
    }

    private static int comparePreRelease(String left, String right) {
        if (left == null && right == null) {
            return 0;
        }
        // A pre-release is by definition not the release: 1.0.0-rc1 < 1.0.0.
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        String[] leftParts = left.split("[.-]");
        String[] rightParts = right.split("[.-]");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            if (i >= leftParts.length) {
                return -1;
            }
            if (i >= rightParts.length) {
                return 1;
            }
            int comparison = compareIdentifier(leftParts[i], rightParts[i]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static int compareIdentifier(String left, String right) {
        boolean leftNumeric = isDigits(left);
        boolean rightNumeric = isDigits(right);
        if (leftNumeric && rightNumeric) {
            return new BigInteger(left).compareTo(new BigInteger(right));
        }
        if (leftNumeric) {
            return -1;
        }
        if (rightNumeric) {
            return 1;
        }
        return left.compareToIgnoreCase(right);
    }

    private static boolean isDigits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public boolean isNewerThan(Version other) {
        return other != null && compareTo(other) > 0;
    }

    /** True when this version is a pre-release of something, e.g. {@code 1.0.0-rc1}. */
    public boolean isPreRelease() {
        return preRelease != null;
    }

    @Override
    public String toString() {
        return normalized;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Version version && normalized.equals(version.normalized);
    }

    @Override
    public int hashCode() {
        return normalized.hashCode();
    }
}
