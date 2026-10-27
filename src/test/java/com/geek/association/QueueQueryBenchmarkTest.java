package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.diff.ChangeDetector;
import com.geek.association.diff.FileDiffCollector;
import com.geek.association.impact.ImpactEngine;
import com.geek.association.impact.ImpactGraph;
import com.geek.association.impact.PeerImpactEngine;
import com.geek.association.model.Analysis;
import com.geek.association.model.ChangeRecord;
import com.geek.association.model.CodeIndex;
import com.geek.association.model.FileChange;
import com.geek.association.model.ImpactResult;
import com.geek.association.model.PeerImpact;
import com.geek.association.parse.CodeIndexer;
import com.geek.association.report.ImpactReport;
import com.geek.association.report.ReportBuilder;
import com.geek.association.source.DirSource;
import com.geek.association.source.RevisionPair;
import com.geek.association.store.Store;
import com.geek.association.util.Json;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Measures the cost of the review-queue path: how big a report becomes, and how long the store
 * takes to answer the queries the UI makes while a queue of that size is open.
 *
 * The analysis stages are timed by {@link PerformanceBenchmarkTest}; this one answers "why is the
 * review page slow once there are thousands of findings".
 */
class QueueQueryBenchmarkTest {

    private static final int MODULES = Integer.getInteger("bench.modules", 8);
    private static final int CLASSES = Integer.getInteger("bench.classes", 40);
    private static final int METHODS = Integer.getInteger("bench.methods", 8);
    private static final int BIG = Integer.getInteger("bench.big", 2);

    @Test
    void measure() throws Exception {
        Path work = Files.createTempDirectory("ca-queue");
        Path base = work.resolve("base");
        Path head = work.resolve("head");
        new SyntheticProject(base).generate(MODULES, CLASSES, METHODS, BIG);
        copyTree(base, head);
        mutate(head);

        RevisionPair revisions = new RevisionPair();
        revisions.mode = "BENCH";
        revisions.base = new DirSource(base, "base", 200000);
        revisions.head = new DirSource(head, "head", 200000);

        CodeIndex baseIndex = new CodeIndexer(200000).index(revisions.base, "base", "base");
        CodeIndex headIndex = new CodeIndexer(200000).index(revisions.head, "head", "head");
        List<FileChange> files = new FileDiffCollector(revisions.base, revisions.head).collect();
        List<ChangeRecord> changes = new ChangeDetector(baseIndex, headIndex).detect(files);
        long seq = 1;
        for (ChangeRecord c : changes) {
            c.id = seq++;
        }

        AppProperties properties = new AppProperties();
        properties.setMaxDepth(8);
        // Peers make the report the size a real run produces.
        List<PeerImpact> peers = new ArrayList<>();
        long firstImpactId = 1;
        for (int p = 0; p < 3; p++) {
            CodeIndex peerIndex = new CodeIndexer(200000).index(revisions.head, "peer" + p, "peer" + p);
            PeerImpact info = new PeerImpact();
            info.peerSourceId = 100 + p;
            info.peerSourceName = "peer-" + p;
            peers.add(new PeerImpactEngine(properties).analyze(changes, headIndex, peerIndex, info, firstImpactId));
            firstImpactId += 200000;
        }

        ImpactReport report = new ReportBuilder().build(revisions, headIndex, baseIndex, changes,
                List.of(), Map.of(), peers);

        long t0 = System.nanoTime();
        String reportJson = Json.write(report);
        long tSerialize = System.nanoTime();

        Path dataDir = work.resolve("data");
        AppProperties storeProps = new AppProperties();
        storeProps.setDataDir(dataDir.toString());
        Store store = new Store(storeProps);
        store.init();
        store.saveSource(source(1, "bench"));
        Analysis analysis = new Analysis();
        analysis.sourceId = 1;
        analysis.sourceName = "bench";
        analysis.sourceType = "LOCAL_DIR";
        analysis.mode = "GIT_DIFF";
        store.createAnalysis(analysis);
        store.finishAnalysis(analysis.id, Analysis.DONE, null, 1000,
                Map.of("impactedSymbols", report.summary.impactedSymbols), report);
        long tPersist = System.nanoTime();

        // The queries the UI makes while the queue is open.
        long q0 = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            store.analyses(30);
        }
        long tList = System.nanoTime();

        long q1 = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            store.analysis(analysis.id);
        }
        long tSingle = System.nanoTime();

        long q2 = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            store.analysesOfSource(1);
        }
        long tBySource = System.nanoTime();

        // Report read, raw: the cost of pulling the blob out of SQLite and parsing it, with no
        // caching in between. This is the lower bound the endpoint path can ever reach.
        long q3 = System.nanoTime();
        ImpactReport parsed = null;
        for (int i = 0; i < 10; i++) {
            Analysis a = store.analysisWithReport(analysis.id);
            parsed = Json.read(a.reportJson, ImpactReport.class);
        }
        long tReportRead = System.nanoTime();

        // Report read, as the UI pays it: GET /api/analyses/{id}/report goes through the service,
        // which caches the parsed report because a DONE report never changes.
        com.geek.association.service.AnalysisService service =
                new com.geek.association.service.AnalysisService(store, storeProps,
                        new com.geek.association.source.RepositoryPreparer(storeProps),
                        new com.geek.association.report.AiExplainer(storeProps));
        service.report(analysis.id);
        long q4 = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            parsed = service.report(analysis.id);
        }
        long tReportEndpoint = System.nanoTime();

        System.out.println();
        System.out.println("=== queue query benchmark ===");
        System.out.println("impacts       : " + report.impacts.size());
        System.out.println("changes       : " + report.changes.size());
        System.out.println("moduleImpacts : " + report.moduleImpacts.size());
        System.out.println("report json   : " + (reportJson.length() / 1024) + " KB");
        System.out.printf("serialize     : %8.1f ms  (once per run)%n", ms(t0, tSerialize));
        System.out.printf("persist       : %8.1f ms  (once per run)%n", ms(tSerialize, tPersist));
        System.out.printf("analyses(30)  : %8.2f ms/op (x20)%n", ms(q0, tList) / 20);
        System.out.printf("analysis(id)  : %8.2f ms/op (x20)%n", ms(q1, tSingle) / 20);
        System.out.printf("bySource      : %8.2f ms/op (x20)%n", ms(q2, tBySource) / 20);
        System.out.printf("report read   : %8.1f ms/op (x10)  -> %d impacts  (raw store+parse, no cache)%n",
                ms(q3, tReportRead) / 10, parsed == null ? 0 : parsed.impacts.size());
        System.out.printf("report GET    : %8.3f ms/op (x10)  -> %d impacts  (service path, cached)%n",
                ms(q4, tReportEndpoint) / 10, parsed == null ? 0 : parsed.impacts.size());
        System.out.println();
    }

    private static com.geek.association.model.RepoSource source(long id, String name) {
        com.geek.association.model.RepoSource s = new com.geek.association.model.RepoSource();
        s.id = id;
        s.name = name;
        s.type = "LOCAL_DIR";
        s.location = "/tmp/bench";
        return s;
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

    /**
     * A delta that produces a large review queue, which is the situation this benchmark is about.
     *
     * Every module's mapper statement changes, so every shared table becomes a database contract
     * that the peer programs use - that is what turns "a few changed methods" into thousands of
     * findings in the peer scope.
     */
    private static void mutate(Path root) throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String text = Files.readString(p, StandardCharsets.UTF_8);
                if (p.toString().endsWith(".xml") && text.contains("where status = 1")) {
                    Files.writeString(p, text.replace("where status = 1", "where status = 2"),
                            StandardCharsets.UTF_8);
                } else if (p.toString().endsWith(".java") && text.contains("public int get(@PathVariable int id)")) {
                    Files.writeString(p, text.replace("public int get(@PathVariable int id)",
                            "public long get(@PathVariable int id)"), StandardCharsets.UTF_8);
                }
            }
        }
    }

    private static double ms(long from, long to) {
        return (to - from) / 1_000_000.0;
    }
}
