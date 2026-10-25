package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.impact.PeerImpactEngine;
import com.geek.association.model.ChangeRecord;
import com.geek.association.model.CodeIndex;
import com.geek.association.model.PeerImpact;
import com.geek.association.model.RepoSource;
import com.geek.association.parse.CodeIndexer;
import com.geek.association.report.ImpactReport;
import com.geek.association.report.ReportBuilder;
import com.geek.association.source.DirSource;
import com.geek.association.source.RevisionPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-program impact: a change here must be reported against the code there that uses the same
 * contract.
 *
 * The fixture is two unrelated Maven projects that only meet over HTTP: the provider declares
 * {@code GET /api/users/{id}}, the consumer calls {@code "/api/users/" + id}.
 */
class PeerImpactTest {

    private static final AppProperties PROPS = new AppProperties();

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static Path provider(Path root) throws IOException {
        write(root, "pom.xml", """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>user-service</artifactId><version>1.0</version>
                </project>
                """);
        write(root, "src/main/java/com/example/user/UserController.java", """
                package com.example.user;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class UserController {
                    @GetMapping("/api/users/{id}")
                    public String getUser(@PathVariable Long id) {
                        return "user-" + id;
                    }
                }
                """);
        return root;
    }

    private static Path consumer(Path root) throws IOException {
        write(root, "pom.xml", """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>order-service</artifactId><version>1.0</version>
                </project>
                """);
        write(root, "src/main/java/com/example/order/UserClient.java", """
                package com.example.order;

                import org.springframework.stereotype.Component;
                import org.springframework.web.client.RestTemplate;

                @Component
                public class UserClient {
                    private final RestTemplate restTemplate = new RestTemplate();

                    public String fetchUser(Long id) {
                        return restTemplate.getForObject("/api/users/" + id, String.class);
                    }
                }
                """);
        write(root, "src/main/java/com/example/order/OrderService.java", """
                package com.example.order;

                public class OrderService {
                    private final UserClient userClient;

                    public OrderService(UserClient userClient) {
                        this.userClient = userClient;
                    }

                    public boolean canOrder(Long userId) {
                        String user = userClient.fetchUser(userId);
                        return user != null && !user.isEmpty();
                    }
                }
                """);
        return root;
    }

    private static CodeIndex index(Path root) {
        return new CodeIndexer(PROPS.getMaxFiles())
                .index(new DirSource(root, "test", PROPS.getMaxFiles()), "head", "abc");
    }

    private static ChangeRecord apiChange() {
        ChangeRecord cr = new ChangeRecord();
        cr.id = 1;
        cr.changeType = "API_CONTRACT_CHANGED";
        cr.symbolKey = "api:/api/users/{}";
        cr.qualifiedName = "/api/users/{}";
        cr.semanticSummary = "API response type changed";
        cr.riskSeed = 0.85;
        cr.tags.add("API");
        return cr;
    }

    @Test
    void changeInOneProgramReachesTheConsumerInAnother(@TempDir Path tmp) throws IOException {
        CodeIndex own = index(provider(tmp.resolve("a")));
        CodeIndex peer = index(consumer(tmp.resolve("b")));

        PeerImpact info = new PeerImpact();
        info.peerSourceName = "order-service";
        PeerImpact result = new PeerImpactEngine(PROPS).analyze(List.of(apiChange()), own, peer, info);

        assertFalse(result.matches.isEmpty(), "the shared endpoint must be matched across programs");
        assertEquals("API", result.matches.get(0).kind);
        assertTrue(result.matches.get(0).peerSymbol.contains("fetchUser"),
                "the peer-side HTTP client is the consumer of the contract");

        assertFalse(result.impacts.isEmpty(), "the consumer is impacted by the provider's change");
        assertTrue(result.impacts.stream().anyMatch(i -> i.targetQualifiedName.contains("fetchUser")));
        assertTrue(result.impacts.stream().anyMatch(i -> i.targetQualifiedName.contains("canOrder")),
                "impact propagates on through the peer's own call graph");
        assertTrue(result.maxRisk > 0);
    }

    @Test
    void unrelatedProgramsProduceNoCrossImpact(@TempDir Path tmp) throws IOException {
        CodeIndex own = index(provider(tmp.resolve("a")));
        // A consumer of a different endpoint.
        Path other = tmp.resolve("c");
        write(other, "pom.xml", """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>billing</artifactId><version>1.0</version>
                </project>
                """);
        write(other, "src/main/java/com/example/billing/InvoiceClient.java", """
                package com.example.billing;

                import org.springframework.web.client.RestTemplate;

                public class InvoiceClient {
                    private final RestTemplate restTemplate = new RestTemplate();

                    public String fetch() {
                        return restTemplate.getForObject("/api/invoices/1", String.class);
                    }
                }
                """);
        CodeIndex peer = index(other);

        PeerImpact info = new PeerImpact();
        info.peerSourceName = "billing";
        PeerImpact result = new PeerImpactEngine(PROPS).analyze(List.of(apiChange()), own, peer, info);

        assertTrue(result.matches.isEmpty(), "different endpoints are not a shared contract");
        assertTrue(result.impacts.isEmpty());
        assertEquals(0, result.maxRisk);
    }

    /**
     * A changed SQL statement has no table symbol of its own: the change record points at the
     * statement, so the tables have to be read back from the statement metadata.
     */
    @Test
    void changedSqlStatementMatchesTheProgramReadingTheSameTable(@TempDir Path tmp) throws IOException {
        Path writer = tmp.resolve("w");
        write(writer, "pom.xml", "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>com.example</groupId><artifactId>writer</artifactId><version>1.0</version></project>");
        write(writer, "src/main/resources/mapper/OrderMapper.xml", "<mapper namespace=\"com.example.w.OrderMapper\">"
                + "<insert id=\"insertOrder\">INSERT INTO orders (id) VALUES (#{id})</insert>"
                + "</mapper>");
        CodeIndex own = index(writer);

        Path reader = tmp.resolve("r");
        write(reader, "pom.xml", "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>com.example</groupId><artifactId>reader</artifactId><version>1.0</version></project>");
        write(reader, "src/main/resources/mapper/OrderReader.xml", "<mapper namespace=\"com.example.r.OrderReader\">"
                + "<select id=\"loadOrder\">SELECT id FROM orders WHERE id = #{id}</select>"
                + "</mapper>");
        CodeIndex peer = index(reader);

        String statementKey = own.symbols.values().stream()
                .filter(x -> "SQL_STATEMENT".equals(x.type))
                .map(x -> x.key)
                .findFirst()
                .orElseThrow();
        ChangeRecord cr = new ChangeRecord();
        cr.id = 1;
        cr.changeType = "SQL_CHANGED";
        cr.symbolKey = statementKey;
        cr.qualifiedName = "insertOrder";
        cr.semanticSummary = "SQL statement changed";

        PeerImpact info = new PeerImpact();
        info.peerSourceName = "reader";
        PeerImpact result = new PeerImpactEngine(PROPS).analyze(List.of(cr), own, peer, info);

        assertTrue(result.matches.stream().anyMatch(m -> "DB_TABLE".equals(m.kind) && "orders".equals(m.contract)),
                "the shared table must be matched through the changed statement");
        assertFalse(result.impacts.isEmpty(), "the program reading the table is impacted");
    }

    /**
     * A configuration key is a contract too, but only when the peer actually reads it.
     */
    @Test
    void changedConfigKeyMatchesTheProgramReadingIt(@TempDir Path tmp) throws IOException {
        Path owner = tmp.resolve("o");
        write(owner, "pom.xml", "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>com.example</groupId><artifactId>owner</artifactId><version>1.0</version></project>");
        write(owner, "src/main/resources/application.yml", "app:\n  limit: 25\n");
        CodeIndex own = index(owner);

        Path consumer = tmp.resolve("c");
        write(consumer, "pom.xml", "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>com.example</groupId><artifactId>consumer</artifactId><version>1.0</version></project>");
        write(consumer, "src/main/java/com/example/c/LimitConfig.java",
                "package com.example.c;\n"
                + "import org.springframework.beans.factory.annotation.Value;\n"
                + "@Component\n"
                + "public class LimitConfig {\n"
                + "    @Value(\"${app.limit:10}\")" + "\n"
                + "    private int limit;\n"
                + "}\n");
        CodeIndex peer = index(consumer);

        ChangeRecord cr = new ChangeRecord();
        cr.id = 1;
        cr.changeType = "CONFIG_CHANGED";
        cr.symbolKey = "config:app.limit";
        cr.qualifiedName = "app.limit";
        cr.semanticSummary = "config value changed";

        PeerImpact info = new PeerImpact();
        info.peerSourceName = "consumer";
        PeerImpact result = new PeerImpactEngine(PROPS).analyze(List.of(cr), own, peer, info);

        assertTrue(result.matches.stream().anyMatch(m -> "CONFIG".equals(m.kind) && "app.limit".equals(m.contract)),
                "the peer reads the same configuration key");
        assertFalse(result.impacts.isEmpty());
    }

    @Test
    void peerWithNoChangesToCompareStillReportsCleanly(@TempDir Path tmp) throws IOException {
        CodeIndex own = index(provider(tmp.resolve("a")));
        CodeIndex peer = index(consumer(tmp.resolve("b")));

        PeerImpact info = new PeerImpact();
        info.peerSourceName = "order-service";
        PeerImpact result = new PeerImpactEngine(PROPS).analyze(List.of(), own, peer, info);

        assertTrue(result.matches.isEmpty());
        assertEquals(0, result.impactedSymbols);
    }

    /**
     * The report is about the peer side only. A module that only exists in the changed program
     * must never appear in module impacts, and the peer's own module must be identifiable.
     */
    @Test
    void reportSummarisesOnlyTheOtherProgram(@TempDir Path tmp) throws IOException {
        CodeIndex own = index(provider(tmp.resolve("a")));
        CodeIndex peer = index(consumer(tmp.resolve("b")));
        PeerImpact info = new PeerImpact();
        info.peerSourceName = "order-service";
        PeerImpact peerResult = new PeerImpactEngine(PROPS)
                .analyze(List.of(apiChange()), own, peer, info);

        RevisionPair revisions = new RevisionPair();
        revisions.note = "user-service";
        revisions.base = new DirSource(tmp.resolve("a"), "base", PROPS.getMaxFiles());
        revisions.head = new DirSource(tmp.resolve("a"), "head", PROPS.getMaxFiles());
        revisions.mode = "GIT_DIFF";
        revisions.baseRef = "base";
        revisions.headRef = "head";

        ImpactReport report = new ReportBuilder().build(revisions, own, own,
                List.of(apiChange()), List.of(), Map.of(), List.of(peerResult));

        assertEquals(1, report.summary.comparedPrograms);
        assertFalse(report.impacts.isEmpty());
        assertTrue(report.impacts.stream().allMatch(i -> "order-service".equals(i.peerSourceName)),
                "every reported impact must belong to the other program");
        assertTrue(report.impacts.stream().allMatch(i -> i.peerModule != null),
                "the module inside the other program must stay available");
        assertTrue(report.moduleImpacts.stream().allMatch(m -> "order-service".equals(m.peerSourceName)),
                "the changed program itself must not be listed as an impacted module");
        assertTrue(report.moduleGraph.stream().allMatch(e -> "CROSS_PROGRAM".equals(e.type)));
        assertTrue(report.blindSpots.isEmpty(), "own-program blind spots are out of scope");
    }

    @Test
    void analysisWithoutPeersIsRejected() {
        AppProperties properties = new AppProperties();
        properties.setDataDir(System.getProperty("java.io.tmpdir") + "/ca-no-peer-test");
        com.geek.association.store.Store store = new com.geek.association.store.Store(properties);
        RepoSource source = new RepoSource();
        source.name = "orders";
        source.type = "LOCAL_DIR";
        source.location = properties.dataPath().toString();
        store.saveSource(source);

        com.geek.association.service.AnalysisService service =
                new com.geek.association.service.AnalysisService(store, properties,
                        new com.geek.association.source.RepositoryPreparer(properties), null);
        var request = new com.geek.association.service.AnalysisService.RunRequest();
        request.sourceId = source.id;

        var error = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.submit(request));
        assertTrue(error.getMessage().contains("其他程序"));
    }
}
