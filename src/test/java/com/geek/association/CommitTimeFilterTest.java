package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.model.CommitInfo;
import com.geek.association.model.RepoSource;
import com.geek.association.source.RepositoryPreparer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Narrowing the commit picker by a committer-time window.
 *
 * The run form no longer requires a start commit: the user browses a branch and ticks the commits
 * they want, and a date range is only there to keep a long history from being scrolled through.
 * That makes the filter a form helper rather than a query language, so these tests pin the three
 * things that make it trustworthy: a day means the whole day (inclusive at both ends, in the JVM's
 * zone), the filter reads the same clock the list displays, and bad input produces a sentence
 * instead of an exception.
 *
 * The fixtures commit with fixed dates so the assertions are about the filter and not about when
 * the suite ran, and every instant is built from a local date in the JVM's zone - writing the
 * expected values as UTC strings would make the test pass only for the author's timezone.
 */
class CommitTimeFilterTest {

    private static final AppProperties PROPERTIES = new AppProperties();

    /** The zone the filter is defined in, i.e. the one the expected values are derived from. */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    @Test
    void onlySinceKeepsTheDayItStartsOnAndEverythingAfter(@TempDir Path root) throws Exception {
        dailyRepo(root);

        Map<String, Object> out = commits(root, null, "master", 30, "2024-03-12", null);

        // change 3 is committed at noon on the 12th: "since the 12th" includes it, because a date
        // bound is a day bound and not an instant bound.
        assertEquals(List.of("change 5", "change 4", "change 3"), subjects(out));
        assertEquals(3, out.get("count"));
        assertEquals("2024-03-12", out.get("since"), "a parsed filter is echoed back so the form can show it");
        assertEquals("", out.get("until"), "an absent filter must not look like an applied one");
        assertEquals(Boolean.FALSE, out.get("more"));
    }

    @Test
    void onlyUntilDropsTheNewerCommitsWithoutEndingTheWalk(@TempDir Path root) throws Exception {
        dailyRepo(root);

        Map<String, Object> out = commits(root, null, "master", 30, null, "2024-03-12");

        // The two newest commits are outside the window and must be skipped, not treated as the end
        // of the history: stopping at the first one would answer "no commits" for any until filter
        // that hides the branch head.
        assertEquals(List.of("change 3", "change 2", "change 1"), subjects(out));
        assertEquals("2024-03-12", out.get("until"));
        assertEquals("", out.get("since"));
    }

    @Test
    void bothBoundsCoverTheWholeOfEachDay(@TempDir Path root) throws Exception {
        boundaryRepo(root);

        Map<String, Object> out = commits(root, null, "master", 30, "2024-04-01", "2024-04-01");

        // 00:00:00 and 23:59:59 on the same day are both inside; the commits just before and just
        // after the day are not. This is the case a "00:00 to 00:00" reading of the dates gets wrong.
        assertEquals(List.of("last second of day", "mid day", "start of day"), subjects(out));
        assertEquals(3, out.get("count"));
        assertEquals("2024-04-01", out.get("since"));
        assertEquals("2024-04-01", out.get("until"));
    }

    @Test
    void aWindowOutsideTheHistoryIsAnEmptyListNotAnError(@TempDir Path root) throws Exception {
        boundaryRepo(root);

        // Everything is older than this since date, so the walk stops at once. The picker has to
        // answer "nothing in this window" - an error would block the form for a valid request.
        Map<String, Object> out = commits(root, null, "master", 30, "2024-04-03", null);

        assertTrue(subjects(out).isEmpty());
        assertEquals(0, out.get("count"));
        assertEquals("", out.get("note"));
        assertEquals(Boolean.FALSE, out.get("more"));
    }

    @Test
    void aRangeAndATimeWindowCompose(@TempDir Path root) throws Exception {
        dailyRepo(root);

        // master~4 is commit 1, so the range covers commits 2..5; the window then keeps the newest
        // two of those. Range first, dates second: neither alone answers "what merged this week".
        Map<String, Object> out = commits(root, "master~4", "master", 30, "2024-03-13", null);

        assertEquals(List.of("change 5", "change 4"), subjects(out), "only the range's commits in the window");
        assertEquals("master", out.get("ref"), "the ref the form asked for is echoed unchanged");
        assertEquals(8, String.valueOf(out.get("base")).length(), "and the resolved base is echoed by sha");
        assertEquals(8, String.valueOf(out.get("target")).length(), "so is the resolved head");
        assertEquals("2024-03-13", out.get("since"));
    }

    @Test
    void anInvalidDateIsReportedInsteadOfThrown(@TempDir Path root) throws Exception {
        dailyRepo(root);

        Map<String, Object> badSince = commits(root, null, "master", 30, "2024-02-30", null);
        assertTrue(String.valueOf(badSince.get("note")).contains("时间范围格式不正确"),
                String.valueOf(badSince.get("note")));
        assertTrue(String.valueOf(badSince.get("note")).contains("2024-02-30"),
                "the note names the value that could not be read");
        assertTrue(((List<?>) badSince.get("commits")).isEmpty(), "no commits rather than a guess");
        assertEquals(0, badSince.get("count"));
        assertEquals("", badSince.get("since"), "an unparsed value is not echoed as if it had been applied");

        // The same answer for the other end, and for text that is not a date at all.
        Map<String, Object> badUntil = commits(root, null, "master", 30, null, "next-week");
        assertTrue(String.valueOf(badUntil.get("note")).contains("时间范围格式不正确"),
                String.valueOf(badUntil.get("note")));
        assertEquals("", badUntil.get("until"));
        assertTrue(((List<?>) badUntil.get("commits")).isEmpty());
    }

    @Test
    void anInvertedWindowIsRefusedWithASentence(@TempDir Path root) throws Exception {
        dailyRepo(root);

        Map<String, Object> out = commits(root, null, "master", 30, "2024-03-14", "2024-03-10");

        assertEquals("起始日期不能晚于结束日期", out.get("note"));
        assertTrue(((List<?>) out.get("commits")).isEmpty());
        // Both ends parsed, so both are echoed: the form can mark which field to fix.
        assertEquals("2024-03-14", out.get("since"));
        assertEquals("2024-03-10", out.get("until"));
    }

    @Test
    void moreSaysWhetherMatchingCommitsWereHeldBack(@TempDir Path root) throws Exception {
        dailyRepo(root);

        // Four commits match "since the 11th", three are listed: more has to be exact, because the
        // picker uses it to offer "load older" and a false positive would make that a no-op.
        Map<String, Object> truncated = commits(root, null, "master", 3, "2024-03-11", null);
        assertEquals(3, truncated.get("count"));
        assertEquals(List.of("change 5", "change 4", "change 3"), subjects(truncated));
        assertEquals(Boolean.TRUE, truncated.get("more"), "four matching commits, only three listed");

        // Exactly three match and three are listed: nothing was held back, so the flag stays false.
        // Deriving it from "count == limit" would get this wrong.
        Map<String, Object> exact = commits(root, null, "master", 3, "2024-03-12", null);
        assertEquals(3, exact.get("count"));
        assertEquals(Boolean.FALSE, exact.get("more"));

        Map<String, Object> roomy = commits(root, null, "master", 30, "2024-03-12", null);
        assertEquals(Boolean.FALSE, roomy.get("more"));
    }

    @Test
    void withoutATimeWindowTheAnswerIsUnchanged(@TempDir Path root) throws Exception {
        dailyRepo(root);
        RepositoryPreparer preparer = new RepositoryPreparer(PROPERTIES);

        // Callers that predate the window keep their four-argument method, and it must produce the
        // same list as the new one with both bounds absent.
        Map<String, Object> legacy = preparer.commits(source(root), null, "master", 30);
        Map<String, Object> explicit = preparer.commits(source(root), null, "master", 30, null, null);

        assertEquals(subjects(legacy), subjects(explicit));
        assertEquals(List.of("change 5", "change 4", "change 3", "change 2", "change 1"),
                subjects(legacy), "the newest commits, newest first");
        assertEquals(legacy.get("count"), explicit.get("count"));
        assertEquals("", explicit.get("since"));
        assertEquals("", explicit.get("until"));

        // more is about the whole matching history, not about the window: a bare limit truncates too.
        Map<String, Object> limited = commits(root, null, "master", 2, null, null);
        assertEquals(2, limited.get("count"));
        assertEquals(Boolean.TRUE, limited.get("more"));
        assertFalse(((List<?>) limited.get("commits")).isEmpty());
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private static Map<String, Object> commits(Path root, String base, String target, int limit,
                                               String since, String until) {
        return new RepositoryPreparer(PROPERTIES)
                .commits(source(root), base, target, limit, since, until);
    }

    private static RepoSource source(Path root) {
        RepoSource source = new RepoSource();
        source.type = "LOCAL_GIT";
        source.location = root.toString();
        source.name = "time-filter-fixture";
        return source;
    }

    /**
     * Five commits, one per day at noon local time, each touching its own file.
     *
     * One file per commit on purpose: the subject then identifies a commit, so "the window kept
     * exactly these three" is asserted directly instead of inferred from a count.
     */
    private static Path dailyRepo(Path root) throws Exception {
        init(root);
        for (int i = 1; i <= 5; i++) {
            commitAt(root, noon(LocalDate.of(2024, 3, 9 + i)), "change " + i, "Demo" + i + ".java");
        }
        return root;
    }

    /**
     * Commits placed exactly on, and just outside, the edges of one local day.
     *
     * The edges are the reason this fixture exists: the inclusive-day semantics only show up in a
     * commit at 00:00:00 or 23:59:59, and a fixture built from UTC strings would land on different
     * local days in another timezone.
     */
    private static Path boundaryRepo(Path root) throws Exception {
        init(root);
        LocalDate day = LocalDate.of(2024, 4, 1);
        commitAt(root, noon(day.minusDays(1)), "before the window", "Before.java");
        commitAt(root, day.atStartOfDay(ZONE), "start of day", "Start.java");
        commitAt(root, noon(day), "mid day", "Mid.java");
        commitAt(root, day.atTime(23, 59, 59).atZone(ZONE), "last second of day", "End.java");
        commitAt(root, day.plusDays(1).atStartOfDay(ZONE), "next day", "Next.java");
        return root;
    }

    private static ZonedDateTime noon(LocalDate day) {
        return day.atTime(12, 0).atZone(ZONE);
    }

    private static void init(Path root) throws IOException, InterruptedException {
        git(root, null, "init", "-q", "-b", "master");
        git(root, null, "config", "user.email", "t@example.com");
        git(root, null, "config", "user.name", "t");
    }

    private static void commitAt(Path root, ZonedDateTime when, String subject, String file) throws Exception {
        Path src = Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.writeString(src.resolve(file),
                "package demo;\npublic class Demo { /* " + subject + " */ }\n");
        git(root, when, "add", ".");
        git(root, when, "commit", "-qm", subject);
    }

    /**
     * Runs git with fixed author and committer dates, so history order is decided by the test
     * rather than by the clock; {@code when == null} leaves the environment alone for setup calls.
     */
    private static void git(Path dir, ZonedDateTime when, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(dir.toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (when != null) {
            String iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(when);
            builder.environment().put("GIT_AUTHOR_DATE", iso);
            builder.environment().put("GIT_COMMITTER_DATE", iso);
        }
        Process p = builder.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "git " + String.join(" ", args) + " -> " + output);
    }

    @SuppressWarnings("unchecked")
    private static List<String> subjects(Map<String, Object> out) {
        List<CommitInfo> commits = (List<CommitInfo>) out.get("commits");
        return commits.stream().map(c -> c.subject).toList();
    }
}
