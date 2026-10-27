package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.diff.ChangeDetector;
import com.geek.association.diff.FileDiffCollector;
import com.geek.association.impact.ImpactEngine;
import com.geek.association.impact.ImpactGraph;
import com.geek.association.impact.PeerImpactEngine;
import com.geek.association.model.ChangeRecord;
import com.geek.association.model.CodeIndex;
import com.geek.association.model.FileChange;
import com.geek.association.model.ImpactResult;
import com.geek.association.model.PeerImpact;
import com.geek.association.parse.ApiAnalyzer;
import com.geek.association.parse.CodeIndexer;
import com.geek.association.parse.ConfigAnalyzer;
import com.geek.association.parse.JavaIndexer;
import com.geek.association.parse.MavenParser;
import com.geek.association.parse.SqlAnalyzer;
import com.geek.association.parse.TestLinker;
import com.geek.association.parse.messaging.MessagingAnalyzer;
import com.geek.association.report.ImpactReport;
import com.geek.association.report.ReportBuilder;
import com.geek.association.source.DirSource;
import com.geek.association.source.FileSource;
import com.geek.association.source.RevisionPair;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Measures every stage of the pipeline on a generated multi-module project, with a modified head
 * revision so change detection, impact propagation and peer matching all do real work.
 *
 * This is not an assertion test: it prints a table so a change can be judged by numbers rather
 * than by intuition.
 */
class PerformanceBenchmarkTest {

    private static final int MODULES = Integer.getInteger("bench.modules", 8);
    private static final int CLASSES = Integer.getInteger("bench.classes", 40);
    private static final int METHODS = Integer.getInteger("bench.methods", 8);
    private static final int BIG = Integer.getInteger("bench.big", 2);
    private static final int PEERS = Integer.getInteger("bench.peers", 2);

    @Test
    void measure() throws Exception {
        Path work = Files.createTempDirectory("ca-bench");
        Path base = work.resolve("base");
        Path head = work.resolve("head");
        new SyntheticProject(base).generate(MODULES, CLASSES, METHODS, BIG);
        copyTree(base, head);
        mutate(head);

        RevisionPair revisions = new RevisionPair();
        revisions.mode = "BENCH";
        revisions.base = new DirSource(base, "base", 200000);
        revisions.head = new DirSource(head, "head", 200000);

        long t0 = System.nanoTime();
        long[] indexSplit = new long[6];
        CodeIndex baseIndex = timedIndex(revisions.base, "base", indexSplit);
        long tBase = System.nanoTime();
        long[] headSplit = new long[6];
        CodeIndex headIndex = timedIndex(revisions.head, "head", headSplit);
        long tHead = System.nanoTime();

        List<FileChange> files = new FileDiffCollector(revisions.base, revisions.head).collect();
        long tDiff = System.nanoTime();
        Map<String, String> diffs = new FileDiffCollector(revisions.base, revisions.head)
                .unifiedDiffs(files, 80, 200);
        long tUnified = System.nanoTime();

        List<ChangeRecord> changes = new ChangeDetector(baseIndex, headIndex).detect(files);
        long tDetect = System.nanoTime();
        long seq = 1;
        for (ChangeRecord c : changes) {
            c.id = seq++;
        }

        ImpactGraph graph = new ImpactGraph(baseIndex, headIndex);
        long tGraph = System.nanoTime();
        AppProperties properties = new AppProperties();
        properties.setMaxDepth(8);
        List<ImpactResult> impacts = new ImpactEngine(properties)
                .analyze(graph, changes, ImpactEngine.options(properties, false));
        long tImpact = System.nanoTime();
        long impactSeq = 1;
        for (ImpactResult i : impacts) {
            i.id = impactSeq++;
        }

        // Peer scan: the second half of a real run, and the part that grows with registry size.
        long tPeer0 = System.nanoTime();
        List<PeerImpact> peers = new ArrayList<>();
        long firstImpactId = 1;
        for (int p = 0; p < PEERS; p++) {
            CodeIndex peerIndex = new CodeIndexer(200000).index(revisions.head, "peer" + p, "peer" + p);
            PeerImpact info = new PeerImpact();
            info.peerSourceId = 100 + p;
            info.peerSourceName = "peer-" + p;
            peers.add(new PeerImpactEngine(properties).analyze(changes, headIndex, peerIndex, info, firstImpactId));
            firstImpactId += 100000;
        }
        long tPeer = System.nanoTime();

        ImpactReport report = new ReportBuilder().build(revisions, headIndex, baseIndex, changes,
                impacts, diffs, peers);
        long tReport = System.nanoTime();

        System.out.println();
        System.out.println("=== performance benchmark ===");
        System.out.println("project      : " + MODULES + " modules x " + CLASSES + " classes x "
                + METHODS + " methods, " + BIG + " huge classes each");
        System.out.println("files        : " + revisions.head.files().size());
        System.out.println("parsed       : " + headIndex.parsedFiles);
        System.out.println("symbols      : " + headIndex.symbols.size());
        System.out.println("edges        : " + headIndex.outgoing.values().stream().mapToInt(List::size).sum());
        System.out.println("changes      : " + changes.size());
        System.out.println("impacts      : " + impacts.size());
        System.out.println("peer impacts : " + peers.stream().mapToInt(p -> p.impacts.size()).sum());
        System.out.printf("index base   : %8.1f ms  [maven %s | java %s | sql %s | api %s | cfg+msg+test %s]%n",
                ms(t0, tBase), ms(indexSplit[0]), ms(indexSplit[1]), ms(indexSplit[2]),
                ms(indexSplit[3]), ms(indexSplit[4]));
        System.out.printf("index head   : %8.1f ms  [maven %s | java %s | sql %s | api %s | cfg+msg+test %s]%n",
                ms(tBase, tHead), ms(headSplit[0]), ms(headSplit[1]), ms(headSplit[2]),
                ms(headSplit[3]), ms(headSplit[4]));
        System.out.printf("file diff    : %8.1f ms%n", ms(tHead, tDiff));
        System.out.printf("unified diff : %8.1f ms%n", ms(tDiff, tUnified));
        System.out.printf("detect       : %8.1f ms%n", ms(tUnified, tDetect));
        System.out.printf("impact graph : %8.1f ms%n", ms(tDetect, tGraph));
        System.out.printf("impact       : %8.1f ms%n", ms(tGraph, tImpact));
        System.out.printf("peers (%d)    : %8.1f ms%n", PEERS, ms(tImpact, tPeer));
        System.out.printf("report       : %8.1f ms%n", ms(tPeer, tReport));
        System.out.printf("TOTAL        : %8.1f ms%n", ms(t0, tReport));
        System.out.println();
    }

    /** Mirrors CodeIndexer.index but times each analyzer so the hotspot is named, not guessed. */
    private static CodeIndex timedIndex(FileSource source, String label, long[] split) {
        long a = System.nanoTime();
        MavenParser maven = new MavenParser();
        maven.parse(source);
        CodeIndex index = new CodeIndex();
        index.revisionLabel = label;
        index.revisionId = label;
        index.moduleDependencies = new ArrayList<>(maven.moduleDependencies);
        long b = System.nanoTime();
        new JavaIndexer(source, maven, index, 200000, null).build();
        long c = System.nanoTime();
        new SqlAnalyzer(source, maven, index).analyze();
        long d = System.nanoTime();
        new ApiAnalyzer(source, index, maven).analyze();
        long e = System.nanoTime();
        new ConfigAnalyzer(source, index, maven).analyze();
        new MessagingAnalyzer().analyze(source, index);
        new TestLinker(source, index, maven).analyze();
        long f = System.nanoTime();
        split[0] = b - a;
        split[1] = c - b;
        split[2] = d - c;
        split[3] = e - d;
        split[4] = f - e;
        return index;
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
    }

    /** A realistic delta: a few methods change, one API signature changes, one config flips. */
    private static void mutate(Path root) throws Exception {
        int touched = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p) || !p.toString().endsWith(".java")) {
                    continue;
                }
                String name = p.getFileName().toString();
                if (!name.startsWith("Service") || name.contains("Test")) {
                    continue;
                }
                if (touched++ % 7 != 0) {
                    continue;
                }
                String text = Files.readString(p, StandardCharsets.UTF_8);
                text = text.replace("total = total - threshold;", "total = total - threshold - 1;");
                Files.writeString(p, text, StandardCharsets.UTF_8);
            }
        }
        Path controller = root.resolve("mod-0/src/main/java/com/example/m0/Api0Controller.java");
        if (Files.exists(controller)) {
            String text = Files.readString(controller, StandardCharsets.UTF_8);
            text = text.replace("public int get(@PathVariable int id)", "public long get(@PathVariable int id)");
            Files.writeString(controller, text, StandardCharsets.UTF_8);
        }
        Path yml = root.resolve("mod-1/src/main/resources/application.yml");
        if (Files.exists(yml)) {
            String text = Files.readString(yml, StandardCharsets.UTF_8);
            Files.writeString(yml, text.replace("enabled: true", "enabled: false"), StandardCharsets.UTF_8);
        }
    }

    private static double ms(long from, long to) {
        return (to - from) / 1_000_000.0;
    }

    /** Elapsed nanoseconds as milliseconds, for the per-analyzer split. */
    private static String ms(long nanos) {
        return String.format("%.1f", nanos / 1_000_000.0);
    }
}
