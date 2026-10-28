package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.diff.ChangeDetector;
import com.geek.association.diff.FileDiffCollector;
import com.geek.association.impact.PeerImpactEngine;
import com.geek.association.model.ChangeRecord;
import com.geek.association.model.CodeIndex;
import com.geek.association.model.FileChange;
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

    // ------------------------------------------------------- database contracts
    //
    // A shared table is not a contract; a shared column is. The cases below are the acceptance
    // criteria of that rule, and the first one is the reason it exists: reading one more column of
    // a shared table must not drag the other program into the review queue.

    private static final String WRITER_POM = "<project><modelVersion>4.0.0</modelVersion>"
            + "<groupId>com.example</groupId><artifactId>writer</artifactId><version>1.0</version></project>";

    /** Indexes two revisions of the same program and returns the head index plus detected changes. */
    private static final class Change {
        CodeIndex head;
        List<ChangeRecord> records;
    }

    private static Change detectChanges(Path base, Path head) {
        RevisionPair revisions = new RevisionPair();
        revisions.base = new DirSource(base, "base", PROPS.getMaxFiles());
        revisions.head = new DirSource(head, "head", PROPS.getMaxFiles());
        CodeIndex baseIndex = new CodeIndexer(PROPS.getMaxFiles()).index(revisions.base, "base", "base");
        CodeIndex headIndex = new CodeIndexer(PROPS.getMaxFiles()).index(revisions.head, "head", "head");
        List<FileChange> files = new FileDiffCollector(revisions.base, revisions.head).collect();
        Change change = new Change();
        change.head = headIndex;
        change.records = new ChangeDetector(baseIndex, headIndex).detect(files);
        long seq = 1;
        for (ChangeRecord record : change.records) {
            record.id = seq++;
        }
        return change;
    }

    /** One program whose only changed file is a MyBatis mapper with a single statement. */
    private static Change mapperChange(Path tmp, String before, String after) throws IOException {
        return mapperChange(tmp, before, after, null);
    }

    /** Same, with a declared JDBC URL so database identity can be compared. */
    private static Change mapperChange(Path tmp, String before, String after, String ownUrl) throws IOException {
        Path base = tmp.resolve("base");
        write(base, "pom.xml", WRITER_POM);
        write(base, "src/main/resources/mapper/OrderMapper.xml", "<mapper namespace=\"com.example.w.OrderMapper\">"
                + before + "</mapper>");
        if (ownUrl != null) {
            write(base, "src/main/resources/application.yml", datasource(ownUrl));
        }
        Path head = tmp.resolve("head");
        write(head, "pom.xml", WRITER_POM);
        write(head, "src/main/resources/mapper/OrderMapper.xml", "<mapper namespace=\"com.example.w.OrderMapper\">"
                + after + "</mapper>");
        if (ownUrl != null) {
            write(head, "src/main/resources/application.yml", datasource(ownUrl));
        }
        return detectChanges(base, head);
    }

    private static String datasource(String url) {
        return "spring:\n  datasource:\n    url: " + url + "\n";
    }

    private static CodeIndex readerOfOrders(Path root, String statement) throws IOException {
        return readerOfOrders(root, statement, null);
    }

    private static CodeIndex readerOfOrders(Path root, String statement, String url) throws IOException {
        write(root, "pom.xml", "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>com.example</groupId><artifactId>reader</artifactId><version>1.0</version></project>");
        write(root, "src/main/resources/mapper/OrderReader.xml", "<mapper namespace=\"com.example.r.OrderReader\">"
                + "<select id=\"loadOrder\">" + statement + "</select></mapper>");
        if (url != null) {
            write(root, "src/main/resources/application.yml", datasource(url));
        }
        return index(root);
    }

    private static CodeIndex writerOfOrders(Path root) throws IOException {
        write(root, "pom.xml", "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>com.example</groupId><artifactId>other-writer</artifactId><version>1.0</version></project>");
        write(root, "src/main/resources/mapper/OtherWriter.xml",
                "<mapper namespace=\"com.example.o.OtherWriter\">"
                        + "<update id=\"touchRemark\">UPDATE orders SET remark = #{remark} WHERE id = #{id}</update>"
                        + "</mapper>");
        return index(root);
    }

    private static PeerImpact analyzeAgainst(Change change, CodeIndex peer) {
        PeerImpact info = new PeerImpact();
        info.peerSourceName = "reader";
        return new PeerImpactEngine(PROPS).analyze(change.records, change.head, peer, info);
    }

    /**
     * The headline rule: selecting one more column is this program's own business. The other
     * program reads the same table, but nothing it can observe has changed.
     */
    @Test
    void readingOneMoreColumnDoesNotReachTheProgramUsingTheSameTable(@TempDir Path tmp) throws IOException {
        Change change = mapperChange(tmp,
                "<select id=\"loadOrder\">SELECT id, status FROM orders WHERE id = #{id}</select>",
                "<select id=\"loadOrder\">SELECT id, status, remark FROM orders WHERE id = #{id}</select>");
        assertTrue(change.records.stream().anyMatch(c -> ChangeDetector.SQL_CHANGED.equals(c.changeType)),
                "the statement itself did change");

        CodeIndex peer = readerOfOrders(tmp.resolve("r"), "SELECT id, status FROM orders WHERE id = #{id}");
        PeerImpact result = analyzeAgainst(change, peer);

        assertTrue(result.matches.isEmpty(),
                "an extra selected column is invisible to the other program, so it must not match");
        assertTrue(result.impacts.isEmpty());
    }

    /** The same edit on the write side does change what the other program reads. */
    @Test
    void writingOneMoreColumnReachesTheReaderOfThatColumnOnly(@TempDir Path tmp) throws IOException {
        Change change = mapperChange(tmp,
                "<insert id=\"insertOrder\">INSERT INTO orders (id, status) VALUES (#{id}, #{status})</insert>",
                "<insert id=\"insertOrder\">INSERT INTO orders (id, status, remark) "
                        + "VALUES (#{id}, #{status}, #{remark})</insert>");
        CodeIndex peer = readerOfOrders(tmp.resolve("r"), "SELECT remark FROM orders WHERE id = #{id}");

        PeerImpact result = analyzeAgainst(change, peer);

        assertTrue(result.matches.stream().anyMatch(m -> "DB_COLUMN".equals(m.kind)
                        && "orders.remark".equals(m.contract) && "reader".equals(m.usage)),
                "the column this program started writing is the contract: " + describe(result));
        assertFalse(result.impacts.isEmpty());
    }

    /**
     * A program that writes its own value into that column is not affected by this program starting
     * to write it too - only readers observe the change.
     */
    @Test
    void writingOneMoreColumnDoesNotReachTheWriterOfThatColumn(@TempDir Path tmp) throws IOException {
        Change change = mapperChange(tmp,
                "<insert id=\"insertOrder\">INSERT INTO orders (id, status) VALUES (#{id}, #{status})</insert>",
                "<insert id=\"insertOrder\">INSERT INTO orders (id, status, remark) "
                        + "VALUES (#{id}, #{status}, #{remark})</insert>");

        Path writerPeer = tmp.resolve("w2");
        write(writerPeer, "pom.xml", "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>com.example</groupId><artifactId>other-writer</artifactId><version>1.0</version></project>");
        write(writerPeer, "src/main/resources/mapper/OtherWriter.xml",
                "<mapper namespace=\"com.example.o.OtherWriter\">"
                        + "<update id=\"touchRemark\">UPDATE orders SET remark = #{remark} WHERE id = #{id}</update>"
                        + "</mapper>");
        PeerImpact result = analyzeAgainst(change, index(writerPeer));

        assertTrue(result.matches.stream().noneMatch(m -> "writer".equals(m.usage)),
                "two programs writing the same column do not conflict through this change: " + describe(result));
    }

    // ---------------------------------------------------- database identity
    //
    // A table name is not an identity: orders exists in almost every schema. A match is only
    // allowed when the two programs can actually see the same database object; unknown identity
    // stays a match with lower confidence, because "I could not read the URL" is not evidence of
    // a different database.

    @Test
    void sameTableInDifferentDatabasesIsNotASharedContract(@TempDir Path tmp) throws IOException {
        Change change = mapperChange(tmp,
                "<insert id=\"insertOrder\">INSERT INTO orders (id) VALUES (#{id})</insert>",
                "<insert id=\"insertOrder\">INSERT INTO orders (id, remark) VALUES (#{id}, #{remark})</insert>",
                "jdbc:mysql://db-a:3306/order_db");
        CodeIndex peer = readerOfOrders(tmp.resolve("r"),
                "SELECT remark FROM orders WHERE id = #{id}", "jdbc:mysql://db-b:3306/billing_db");

        PeerImpact result = analyzeAgainst(change, peer);

        assertTrue(result.matches.isEmpty(),
                "same table name in another database is not a contract: " + describe(result));
        assertEquals(1, result.dbMismatchSuppressed,
                "the suppressed candidate must be counted, not silently dropped");
    }

    @Test
    void sameDatabaseNameOnDifferentHostsIsStillTheSameContract(@TempDir Path tmp) throws IOException {
        Change change = mapperChange(tmp,
                "<insert id=\"insertOrder\">INSERT INTO orders (id) VALUES (#{id})</insert>",
                "<insert id=\"insertOrder\">INSERT INTO orders (id, remark) VALUES (#{id}, #{remark})</insert>",
                "jdbc:mysql://db-a:3306/order_db");
        CodeIndex peer = readerOfOrders(tmp.resolve("r"),
                "SELECT remark FROM orders WHERE id = #{id}", "jdbc:mysql://db-b:3306/order_db");

        PeerImpact result = analyzeAgainst(change, peer);

        assertFalse(result.matches.isEmpty(),
                "the database name, not the host, is the identity: " + describe(result));
        assertEquals(0, result.dbMismatchSuppressed);
    }

    @Test
    void unknownDatabaseIdentityKeepsTheMatchAtLowerConfidence(@TempDir Path tmp) throws IOException {
        Change change = mapperChange(tmp,
                "<insert id=\"insertOrder\">INSERT INTO orders (id) VALUES (#{id})</insert>",
                "<insert id=\"insertOrder\">INSERT INTO orders (id, remark) VALUES (#{id}, #{remark})</insert>");
        CodeIndex peer = readerOfOrders(tmp.resolve("r"), "SELECT remark FROM orders WHERE id = #{id}");

        PeerImpact result = analyzeAgainst(change, peer);

        assertFalse(result.matches.isEmpty(), "unknown identity is not a reason to drop a match");
        assertTrue(result.matches.get(0).confidence < 0.85,
                "an unverified identity must lower confidence: " + result.matches.get(0).confidence);
        assertEquals(1, result.dbIdentityUnknownMatches,
                "the report must say that the database identity was not verified");
    }

    @Test
    void explicitSchemasInDifferentDatabasesAreNotTheSameContract(@TempDir Path tmp) throws IOException {
        Change change = mapperChange(tmp,
                "<insert id=\"insertOrder\">INSERT INTO order_db.orders (id) VALUES (#{id})</insert>",
                "<insert id=\"insertOrder\">INSERT INTO order_db.orders (id, remark) "
                        + "VALUES (#{id}, #{remark})</insert>");
        CodeIndex peer = readerOfOrders(tmp.resolve("r"),
                "SELECT remark FROM billing_db.orders WHERE id = #{id}");

        PeerImpact result = analyzeAgainst(change, peer);

        assertTrue(result.matches.isEmpty(),
                "order_db.orders and billing_db.orders are different objects: " + describe(result));
        assertEquals(1, result.dbMismatchSuppressed);
    }

    /** A new table cannot already be a dependency of another program. */
    @Test
    void createTableDoesNotReachCodeThatAlreadyUsesTheTable(@TempDir Path tmp) throws IOException {
        Path base = tmp.resolve("base");
        write(base, "pom.xml", WRITER_POM);
        write(base, "src/main/resources/db/migration/V1__orders.sql", "CREATE TABLE orders (id BIGINT);");
        Path head = tmp.resolve("head");
        write(head, "pom.xml", WRITER_POM);
        write(head, "src/main/resources/db/migration/V1__orders.sql",
                "CREATE TABLE orders (id BIGINT);\nCREATE TABLE order_archive (id BIGINT);");
        Change change = detectChanges(base, head);

        CodeIndex peer = readerOfOrders(tmp.resolve("r"), "SELECT id FROM order_archive WHERE id = #{id}");
        PeerImpact result = analyzeAgainst(change, peer);

        assertTrue(result.matches.isEmpty(),
                "a new table cannot already be a dependency: " + describe(result));
    }

    /** Deleting a migration script does not roll back a migration that already ran. */
    @Test
    void deletingAMigrationScriptIsNotASchemaRollback(@TempDir Path tmp) throws IOException {
        Path base = tmp.resolve("base");
        write(base, "pom.xml", WRITER_POM);
        write(base, "src/main/resources/db/migration/V1__orders.sql",
                "CREATE TABLE orders (id BIGINT, remark VARCHAR(64));");
        Path head = tmp.resolve("head");
        write(head, "pom.xml", WRITER_POM);
        Change change = detectChanges(base, head);

        assertTrue(change.records.stream().anyMatch(c -> ChangeDetector.DDL_CHANGED.equals(c.changeType)
                        && "true".equals(String.valueOf(c.extra.get("ddlRemoved")))),
                "removing a migration script is still a file change: " + change.records);

        CodeIndex peer = readerOfOrders(tmp.resolve("r"), "SELECT remark FROM orders WHERE id = #{id}");
        PeerImpact result = analyzeAgainst(change, peer);

        assertTrue(result.matches.isEmpty(),
                "deleting the script does not undo the migration: " + describe(result));
    }

    @Test
    void wideningAColumnDoesNotReachItsReaders(@TempDir Path tmp) throws IOException {
        Change change = createTableTypeChange(tmp, "VARCHAR(32)", "VARCHAR(64)");
        assertTrue(change.records.stream().anyMatch(c -> ChangeDetector.DDL_CHANGED.equals(c.changeType)
                        && "MODIFY_COLUMN".equals(c.extra.get("ddl"))
                        && "orders.remark".equals(c.extra.get("table") + "." + c.extra.get("column"))),
                "the type change must be detected: " + change.records);

        CodeIndex peer = readerOfOrders(tmp.resolve("r"), "SELECT remark FROM orders WHERE id = #{id}");
        PeerImpact result = analyzeAgainst(change, peer);

        assertTrue(result.matches.isEmpty(),
                "a widened column keeps every existing value valid: " + describe(result));
        assertEquals(1, result.dbCompatibilitySuppressed,
                "the compatible change must be counted, not silently dropped");
    }

    @Test
    void narrowingAColumnReachesItsReaders(@TempDir Path tmp) throws IOException {
        Change change = createTableTypeChange(tmp, "VARCHAR(64)", "VARCHAR(32)");
        CodeIndex peer = readerOfOrders(tmp.resolve("r"), "SELECT remark FROM orders WHERE id = #{id}");

        PeerImpact result = analyzeAgainst(change, peer);

        assertTrue(result.matches.stream().anyMatch(m -> "DB_COLUMN".equals(m.kind)
                        && "orders.remark".equals(m.contract)),
                "a narrowed column can truncate values: " + describe(result));
    }

    @Test
    void makingAColumnNotNullReachesWritersOnly(@TempDir Path tmp) throws IOException {
        Change change = createTableTypeChange(tmp, "VARCHAR(64) NULL", "VARCHAR(64) NOT NULL");

        CodeIndex reader = readerOfOrders(tmp.resolve("r"), "SELECT remark FROM orders WHERE id = #{id}");
        PeerImpact readSide = analyzeAgainst(change, reader);
        assertTrue(readSide.matches.isEmpty(),
                "readers still see the same values after a NOT NULL change: " + describe(readSide));

        Path writerDir = tmp.resolve("w");
        write(writerDir, "pom.xml", "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>com.example</groupId><artifactId>other-writer</artifactId><version>1.0</version></project>");
        write(writerDir, "src/main/resources/mapper/OtherWriter.xml",
                "<mapper namespace=\"com.example.o.OtherWriter\">"
                        + "<update id=\"touchRemark\">UPDATE orders SET remark = #{remark} WHERE id = #{id}</update>"
                        + "</mapper>");
        PeerImpact writeSide = analyzeAgainst(change, index(writerDir));
        assertTrue(writeSide.matches.stream().anyMatch(m -> "writer".equals(m.usage)),
                "an existing writer can fail when the column becomes NOT NULL: " + describe(writeSide));
    }

    @Test
    void alterNotNullReachesWritersNotReaders(@TempDir Path tmp) throws IOException {
        Path base = tmp.resolve("base");
        write(base, "pom.xml", WRITER_POM);
        write(base, "src/main/resources/db/migration/V1__orders.sql",
                "CREATE TABLE orders (id BIGINT, remark VARCHAR(64) NULL);");
        Path head = tmp.resolve("head");
        write(head, "pom.xml", WRITER_POM);
        write(head, "src/main/resources/db/migration/V1__orders.sql",
                "CREATE TABLE orders (id BIGINT, remark VARCHAR(64) NULL);\n"
                        + "ALTER TABLE orders MODIFY COLUMN remark VARCHAR(64) NOT NULL;");
        Change change = detectChanges(base, head);

        assertTrue(change.records.stream().anyMatch(c -> ChangeDetector.DDL_CHANGED.equals(c.changeType)
                        && "MODIFY_COLUMN".equals(c.extra.get("ddl"))
                        && "NULLABILITY".equals(c.extra.get("schemaChange"))),
                "an ALTER that only changes nullability must say so: " + change.records);

        CodeIndex reader = readerOfOrders(tmp.resolve("r"), "SELECT remark FROM orders WHERE id = #{id}");
        assertTrue(analyzeAgainst(change, reader).matches.isEmpty(),
                "readers still see the same values");

        Path writerDir = tmp.resolve("w");
        write(writerDir, "pom.xml", "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>com.example</groupId><artifactId>other-writer</artifactId><version>1.0</version></project>");
        write(writerDir, "src/main/resources/mapper/OtherWriter.xml",
                "<mapper namespace=\"com.example.o.OtherWriter\">"
                        + "<update id=\"touchRemark\">UPDATE orders SET remark = #{remark} WHERE id = #{id}</update>"
                        + "</mapper>");
        PeerImpact writeSide = analyzeAgainst(change, index(writerDir));
        assertTrue(writeSide.matches.stream().anyMatch(m -> "writer".equals(m.usage)),
                "an existing writer can fail when the column becomes NOT NULL: " + describe(writeSide));
    }

    @Test
    void postgresSetNotNullReachesWriters(@TempDir Path tmp) throws IOException {
        Change change = alterChange(tmp,
                "CREATE TABLE orders (id BIGINT, remark VARCHAR(64));",
                "ALTER TABLE orders ALTER COLUMN remark SET NOT NULL;");

        assertTrue(change.records.stream().anyMatch(c -> ChangeDetector.DDL_CHANGED.equals(c.changeType)
                        && "NULLABILITY".equals(c.extra.get("schemaChange"))),
                "ALTER COLUMN ... SET NOT NULL must be a nullability change: " + change.records);

        CodeIndex reader = readerOfOrders(tmp.resolve("r"), "SELECT remark FROM orders WHERE id = #{id}");
        assertTrue(analyzeAgainst(change, reader).matches.isEmpty(), "readers still see the same values");

        PeerImpact writeSide = analyzeAgainst(change, writerOfOrders(tmp.resolve("w")));
        assertTrue(writeSide.matches.stream().anyMatch(m -> "writer".equals(m.usage)),
                "an existing writer can fail when the column becomes NOT NULL: " + describe(writeSide));
    }

    @Test
    void ifExistsColumnDdlKeepsTheRealColumnName(@TempDir Path tmp) throws IOException {
        Change added = alterChange(tmp,
                "CREATE TABLE orders (id BIGINT);",
                "ALTER TABLE orders ADD COLUMN IF NOT EXISTS remark VARCHAR(64);");
        assertTrue(added.head.columnTypes.containsKey("orders.remark"),
                "ADD COLUMN IF NOT EXISTS must add remark, not a column called IF");
        assertFalse(added.head.columnTypes.containsKey("orders.if"));

        Change dropped = alterChange(tmp,
                "CREATE TABLE orders (id BIGINT, remark VARCHAR(64));",
                "ALTER TABLE orders DROP COLUMN IF EXISTS remark;");
        assertFalse(dropped.head.columnTypes.containsKey("orders.remark"),
                "DROP COLUMN IF EXISTS must drop remark, not a column called IF");
    }

    @Test
    void wideningTypeAndNotNullAreBothReported(@TempDir Path tmp) throws IOException {
        Change change = alterChange(tmp,
                "CREATE TABLE orders (id BIGINT, remark VARCHAR(64) NULL);",
                "ALTER TABLE orders MODIFY COLUMN remark VARCHAR(128) NOT NULL;");

        assertTrue(change.records.stream().anyMatch(c -> ChangeDetector.DDL_CHANGED.equals(c.changeType)
                        && "TYPE".equals(c.extra.get("schemaChange"))),
                "the widening must be recorded: " + change.records);
        assertTrue(change.records.stream().anyMatch(c -> ChangeDetector.DDL_CHANGED.equals(c.changeType)
                        && "NULLABILITY".equals(c.extra.get("schemaChange"))),
                "the NOT NULL tightening must not be swallowed by the widening: " + change.records);

        PeerImpact writeSide = analyzeAgainst(change, writerOfOrders(tmp.resolve("w")));
        assertTrue(writeSide.matches.stream().anyMatch(m -> "writer".equals(m.usage)),
                "the write-side break must survive the safe widening: " + describe(writeSide));
    }

    @Test
    void aLaterNotNullIsNotHiddenByAnEarlierModifyWithoutNullability(@TempDir Path tmp) throws IOException {
        Path base = tmp.resolve("base");
        write(base, "pom.xml", WRITER_POM);
        write(base, "src/main/resources/db/migration/V1__orders.sql",
                "CREATE TABLE c (v INT NOT NULL);\nALTER TABLE c MODIFY v INT;");
        Path head = tmp.resolve("head");
        write(head, "pom.xml", WRITER_POM);
        write(head, "src/main/resources/db/migration/V1__orders.sql",
                "CREATE TABLE c (v INT NOT NULL);\nALTER TABLE c MODIFY v INT;\n"
                        + "ALTER TABLE c MODIFY v INT NOT NULL;");
        Change change = detectChanges(base, head);

        assertTrue(change.records.stream().anyMatch(c -> ChangeDetector.DDL_CHANGED.equals(c.changeType)
                        && "NULLABILITY".equals(c.extra.get("schemaChange"))),
                "an earlier MODIFY without NOT NULL must clear the stale false, not hide the later true: "
                        + change.records);
    }

    @Test
    void inPlaceModifyWithoutCreateTableStillProducesADbDelta(@TempDir Path tmp) throws IOException {
        Path base = tmp.resolve("base");
        write(base, "pom.xml", WRITER_POM);
        write(base, "src/main/resources/db/migration/V1__orders.sql",
                "ALTER TABLE orders MODIFY remark VARCHAR(64);");
        Path head = tmp.resolve("head");
        write(head, "pom.xml", WRITER_POM);
        write(head, "src/main/resources/db/migration/V1__orders.sql",
                "ALTER TABLE orders MODIFY remark VARCHAR(32);");
        Change change = detectChanges(base, head);

        assertTrue(change.records.stream().anyMatch(c -> ChangeDetector.DDL_CHANGED.equals(c.changeType)
                        && "MODIFY_COLUMN".equals(c.extra.get("ddl"))),
                "an in-place MODIFY is a DDL change even without a CREATE TABLE in the repository: "
                        + change.records);

        CodeIndex reader = readerOfOrders(tmp.resolve("r"), "SELECT remark FROM orders WHERE id = #{id}");
        PeerImpact result = analyzeAgainst(change, reader);
        assertTrue(result.matches.stream().anyMatch(m -> "DB_COLUMN".equals(m.kind)
                        && "orders.remark".equals(m.contract)),
                "the narrowed column must reach its reader: " + describe(result));
    }

    @Test
    void renamingAColumnReachesReadersOfTheOldName(@TempDir Path tmp) throws IOException {
        Change change = alterChange(tmp,
                "CREATE TABLE orders (id BIGINT, remark VARCHAR(64));",
                "ALTER TABLE orders RENAME COLUMN remark TO note;");

        assertTrue(change.records.stream().anyMatch(c -> ChangeDetector.DDL_CHANGED.equals(c.changeType)
                        && "DROP_COLUMN".equals(c.extra.get("ddl"))
                        && "remark".equals(c.extra.get("column"))),
                "a rename breaks code that still reads the old name: " + change.records);

        CodeIndex reader = readerOfOrders(tmp.resolve("r"), "SELECT remark FROM orders WHERE id = #{id}");
        PeerImpact result = analyzeAgainst(change, reader);

        assertTrue(result.matches.stream().anyMatch(m -> "DB_COLUMN".equals(m.kind)
                        && "orders.remark".equals(m.contract)),
                "the old column name is the contract: " + describe(result));
    }

    @Test
    void databaseNameAndExplicitSchemaMatchAcrossLevelsWithLowerConfidence(@TempDir Path tmp) throws IOException {
        Change change = mapperChange(tmp,
                "<insert id=\"insertOrder\">INSERT INTO orders (id) VALUES (#{id})</insert>",
                "<insert id=\"insertOrder\">INSERT INTO orders (id, remark) VALUES (#{id}, #{remark})</insert>",
                "jdbc:mysql://db:3306/order_db");
        CodeIndex peer = readerOfOrders(tmp.resolve("r"),
                "SELECT remark FROM order_db.orders WHERE id = #{id}");

        PeerImpact result = analyzeAgainst(change, peer);

        assertFalse(result.matches.isEmpty(),
                "a database name and an explicit schema can denote the same object: " + describe(result));
        assertEquals(1, result.dbIdentityUnknownMatches,
                "cross-level comparison is uncertain and must be counted");
        assertTrue(result.matches.get(0).confidence < 0.85,
                "cross-level identity must lower confidence: " + result.matches.get(0).confidence);
    }

    private static Change alterChange(Path tmp, String createSql, String alterSql) throws IOException {
        Path base = tmp.resolve("base");
        write(base, "pom.xml", WRITER_POM);
        write(base, "src/main/resources/db/migration/V1__orders.sql", createSql);
        Path head = tmp.resolve("head");
        write(head, "pom.xml", WRITER_POM);
        write(head, "src/main/resources/db/migration/V1__orders.sql", createSql + "\n" + alterSql);
        return detectChanges(base, head);
    }

    private static Change createTableTypeChange(Path tmp, String beforeType, String afterType) throws IOException {
        Path base = tmp.resolve("base");
        write(base, "pom.xml", WRITER_POM);
        write(base, "src/main/resources/db/migration/V1__orders.sql",
                "CREATE TABLE orders (id BIGINT, remark " + beforeType + ");");
        Path head = tmp.resolve("head");
        write(head, "pom.xml", WRITER_POM);
        write(head, "src/main/resources/db/migration/V1__orders.sql",
                "CREATE TABLE orders (id BIGINT, remark " + afterType + ");");
        return detectChanges(base, head);
    }

    /** Dynamic SQL must not degrade to a table-level contract: its parsed column list is a lower bound. */
    @Test
    void dynamicWriteDoesNotFallBackToATableContract(@TempDir Path tmp) throws IOException {
        Change change = mapperChange(tmp,
                "<select id=\"noop\">SELECT 1</select>",
                "<select id=\"noop\">SELECT 1</select>"
                        + "<insert id=\"archive\">INSERT INTO order_archive <include refid=\"cols\"/> "
                        + "VALUES (#{id})</insert>");
        CodeIndex peer = readerOfOrders(tmp.resolve("r"), "SELECT id FROM order_archive WHERE id = #{id}");

        PeerImpact result = analyzeAgainst(change, peer);

        assertTrue(result.matches.isEmpty(),
                "an unresolved include is a blind spot, not a table-level contract: " + describe(result));
    }

    @Test
    void testMapperIsNotAProductionConsumer(@TempDir Path tmp) throws IOException {
        Change change = mapperChange(tmp,
                "<insert id=\"insertOrder\">INSERT INTO orders (id) VALUES (#{id})</insert>",
                "<insert id=\"insertOrder\">INSERT INTO orders (id, remark) VALUES (#{id}, #{remark})</insert>");
        Path peerDir = tmp.resolve("r");
        write(peerDir, "pom.xml", "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>com.example</groupId><artifactId>reader</artifactId><version>1.0</version></project>");
        write(peerDir, "src/test/resources/mapper/OrderReader.xml",
                "<mapper namespace=\"com.example.r.OrderReader\">"
                        + "<select id=\"loadOrder\">SELECT remark FROM orders WHERE id = #{id}</select></mapper>");
        CodeIndex peer = index(peerDir);

        PeerImpact result = analyzeAgainst(change, peer);

        assertTrue(result.matches.isEmpty(),
                "test SQL is not a production consumer: " + describe(result));
    }

    @Test
    void movingAStatementToAnotherMapperIsNotAWriteSetChange(@TempDir Path tmp) throws IOException {
        Path base = tmp.resolve("base");
        write(base, "pom.xml", WRITER_POM);
        write(base, "src/main/resources/mapper/A.xml", "<mapper namespace=\"com.example.w.A\">"
                + "<insert id=\"insertOrder\">INSERT INTO orders (id, remark) "
                + "VALUES (#{id}, #{remark})</insert></mapper>");
        Path head = tmp.resolve("head");
        write(head, "pom.xml", WRITER_POM);
        write(head, "src/main/resources/mapper/B.xml", "<mapper namespace=\"com.example.w.B\">"
                + "<insert id=\"insertOrder\">INSERT INTO orders (id, remark) "
                + "VALUES (#{id}, #{remark})</insert></mapper>");
        Change change = detectChanges(base, head);

        assertTrue(change.records.stream().noneMatch(c -> ChangeDetector.SQL_REMOVED.equals(c.changeType)
                        || ChangeDetector.SQL_ADDED.equals(c.changeType)),
                "a moved statement is not a contract change: " + change.records);

        CodeIndex peer = readerOfOrders(tmp.resolve("r"), "SELECT remark FROM orders WHERE id = #{id}");
        PeerImpact result = analyzeAgainst(change, peer);

        assertTrue(result.matches.isEmpty(),
                "moving a mapper must not look like a write-set change: " + describe(result));
    }

    /** A dropped column breaks both sides, so the read side and the write side both match. */
    @Test
    void droppingAColumnReachesReadersAndWriters(@TempDir Path tmp) throws IOException {
        Path base = tmp.resolve("base");
        write(base, "pom.xml", WRITER_POM);
        write(base, "src/main/resources/db/migration/V1__orders.sql",
                "CREATE TABLE orders (id BIGINT PRIMARY KEY, status INT, remark VARCHAR(64));");
        Path head = tmp.resolve("head");
        write(head, "pom.xml", WRITER_POM);
        write(head, "src/main/resources/db/migration/V1__orders.sql",
                "CREATE TABLE orders (id BIGINT PRIMARY KEY, status INT, remark VARCHAR(64));\n"
                        + "ALTER TABLE orders DROP COLUMN remark;");
        Change change = detectChanges(base, head);

        assertTrue(change.records.stream().anyMatch(c -> ChangeDetector.DDL_CHANGED.equals(c.changeType)
                        && "orders.remark".equals(c.extra.get("table") + "." + c.extra.get("column"))),
                "a DDL change in a .sql script must produce a schema change record: " + change.records);

        CodeIndex reader = readerOfOrders(tmp.resolve("r"), "SELECT remark FROM orders WHERE id = #{id}");
        PeerImpact readSide = analyzeAgainst(change, reader);
        assertTrue(readSide.matches.stream().anyMatch(m -> "DB_COLUMN".equals(m.kind)
                        && "orders.remark".equals(m.contract)),
                "the program reading the dropped column is impacted: " + describe(readSide));

        Path writerPeer = tmp.resolve("w2");
        write(writerPeer, "pom.xml", "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>com.example</groupId><artifactId>other-writer</artifactId><version>1.0</version></project>");
        write(writerPeer, "src/main/resources/mapper/OtherWriter.xml",
                "<mapper namespace=\"com.example.o.OtherWriter\">"
                        + "<update id=\"touchRemark\">UPDATE orders SET remark = #{remark} WHERE id = #{id}</update>"
                        + "</mapper>");
        PeerImpact writeSide = analyzeAgainst(change, index(writerPeer));
        assertTrue(writeSide.matches.stream().anyMatch(m -> "DB_COLUMN".equals(m.kind)
                        && "orders.remark".equals(m.contract) && "writer".equals(m.usage)),
                "the program writing the dropped column is impacted too: " + describe(writeSide));
    }

    /** Dropping a whole table leaves no column to name, so the table itself becomes the contract. */
    @Test
    void droppingATableFallsBackToTheTableContract(@TempDir Path tmp) throws IOException {
        Path base = tmp.resolve("base");
        write(base, "pom.xml", WRITER_POM);
        write(base, "src/main/resources/db/migration/V1__orders.sql", "CREATE TABLE orders (id BIGINT);");
        Path head = tmp.resolve("head");
        write(head, "pom.xml", WRITER_POM);
        write(head, "src/main/resources/db/migration/V1__orders.sql",
                "CREATE TABLE orders (id BIGINT);\nDROP TABLE orders;");
        Change change = detectChanges(base, head);

        CodeIndex peer = readerOfOrders(tmp.resolve("r"), "SELECT id FROM orders WHERE id = #{id}");
        PeerImpact result = analyzeAgainst(change, peer);

        assertTrue(result.matches.stream().anyMatch(m -> "DB_TABLE".equals(m.kind) && "orders".equals(m.contract)),
                "a dropped table is matched as a table: " + describe(result));
        assertFalse(result.impacts.isEmpty());
    }

    /**
     * Removing an entity field is not dropping a column. The column can still exist and be read by
     * SQL; the mapping is this program's private view. Treating it as a schema change used to pull
     * every reader and writer of the column into the report on every entity refactor.
     */
    @Test
    void removedEntityMappingDoesNotReachSqlReaders(@TempDir Path tmp) throws IOException {
        String entityBefore = """
                package com.example.w;

                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;

                @Entity
                @Table(name = "orders")
                public class Order {
                    @Column(name = "id")
                    private Long id;

                    @Column(name = "remark")
                    private String remark;
                }
                """;
        String entityAfter = """
                package com.example.w;

                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;

                @Entity
                @Table(name = "orders")
                public class Order {
                    @Column(name = "id")
                    private Long id;
                }
                """;
        Path base = tmp.resolve("base");
        write(base, "pom.xml", WRITER_POM);
        write(base, "src/main/java/com/example/w/Order.java", entityBefore);
        Path head = tmp.resolve("head");
        write(head, "pom.xml", WRITER_POM);
        write(head, "src/main/java/com/example/w/Order.java", entityAfter);
        Change change = detectChanges(base, head);

        assertTrue(change.records.stream().anyMatch(c -> ChangeDetector.FIELD_REMOVED.equals(c.changeType)),
                "removing the field must be detected: " + change.records);

        CodeIndex peer = readerOfOrders(tmp.resolve("r"), "SELECT remark FROM orders WHERE id = #{id}");
        PeerImpact result = analyzeAgainst(change, peer);

        assertTrue(result.matches.isEmpty(),
                "removing an entity field is not dropping a column: " + describe(result));
    }

    /**
     * A peer statement that touches the table but names no column cannot be judged either way. It
     * must be counted, because an empty match list would otherwise read as "nothing to check".
     */
    @Test
    void peerSelectStarIsCountedAsAColumnBlindSpot(@TempDir Path tmp) throws IOException {
        Change change = mapperChange(tmp,
                "<insert id=\"insertOrder\">INSERT INTO orders (id) VALUES (#{id})</insert>",
                "<insert id=\"insertOrder\">INSERT INTO orders (id, remark) VALUES (#{id}, #{remark})</insert>");
        CodeIndex peer = readerOfOrders(tmp.resolve("r"), "SELECT * FROM orders WHERE id = #{id}");

        PeerImpact result = analyzeAgainst(change, peer);

        assertTrue(result.matches.isEmpty(), "SELECT * names no column, so it cannot match one");
        assertEquals(1, result.unparsedColumnStatements,
                "the blind spot must still be counted, not silently dropped");
        assertFalse(result.unparsedColumnFiles.isEmpty(), "and it must be locatable");
    }

    /**
     * A write that names no column ({@code insert into orders values (...)}) has no column to be the
     * contract, so it stays table-scoped - and only for the table it started writing.
     */
    /**
     * A statement that starts writing a table without naming any column has no column to be the
     * contract, so the table itself is what the other side sees change.
     */
    @Test
    void columnLessWriteFallsBackToTheTableContract(@TempDir Path tmp) throws IOException {
        Change change = mapperChange(tmp,
                "<select id=\"countOrders\">SELECT count(*) FROM orders</select>",
                "<select id=\"countOrders\">SELECT count(*) FROM orders</select>"
                        + "<insert id=\"insertOrder\">INSERT INTO order_archive VALUES (#{id})</insert>");
        CodeIndex peer = readerOfOrders(tmp.resolve("r"), "SELECT id FROM order_archive WHERE id = #{id}");

        PeerImpact result = analyzeAgainst(change, peer);

        assertTrue(result.matches.stream().anyMatch(m -> "DB_TABLE".equals(m.kind)
                        && "order_archive".equals(m.contract)),
                "a write with no column list is matched at table level: " + describe(result));
    }

    /**
     * A new column is invisible to a statement that names its columns. Reporting it as an impact
     * was the database equivalent of reporting an unused method addition: the schema moved, the
     * contract a named-column consumer depends on did not.
     */
    @Test
    void addingAColumnDoesNotReachTheReaderOfNamedColumns(@TempDir Path tmp) throws IOException {
        Path base = tmp.resolve("base");
        write(base, "pom.xml", WRITER_POM);
        write(base, "src/main/resources/db/migration/V1__orders.sql", "CREATE TABLE orders (id BIGINT);");
        Path head = tmp.resolve("head");
        write(head, "pom.xml", WRITER_POM);
        write(head, "src/main/resources/db/migration/V1__orders.sql",
                "CREATE TABLE orders (id BIGINT);\nALTER TABLE orders ADD COLUMN remark VARCHAR(64);");
        Change change = detectChanges(base, head);

        CodeIndex peer = readerOfOrders(tmp.resolve("r"), "SELECT remark FROM orders WHERE id = #{id}");
        PeerImpact result = analyzeAgainst(change, peer);

        assertTrue(result.matches.isEmpty(),
                "a named-column reader cannot observe a new column: " + describe(result));
        assertTrue(result.impacts.isEmpty());
    }

    /** The same column addition is still visible to a statement whose column list is unknown. */
    @Test
    void addingAColumnSurfacesAsABlindSpotForSelectStar(@TempDir Path tmp) throws IOException {
        Path base = tmp.resolve("base");
        write(base, "pom.xml", WRITER_POM);
        write(base, "src/main/resources/db/migration/V1__orders.sql", "CREATE TABLE orders (id BIGINT);");
        Path head = tmp.resolve("head");
        write(head, "pom.xml", WRITER_POM);
        write(head, "src/main/resources/db/migration/V1__orders.sql",
                "CREATE TABLE orders (id BIGINT);\nALTER TABLE orders ADD COLUMN remark VARCHAR(64);");
        Change change = detectChanges(base, head);

        CodeIndex peer = readerOfOrders(tmp.resolve("r"), "SELECT * FROM orders WHERE id = #{id}");
        PeerImpact result = analyzeAgainst(change, peer);

        assertTrue(result.matches.isEmpty(), "SELECT * names no column, so it cannot match one");
        assertEquals(1, result.unparsedColumnStatements,
                "the added column is a blind spot for SELECT *, not a clean result");
    }

    /**
     * A column of a joined statement belongs to the table it is qualified with, not to every table
     * of the statement - otherwise a change to {@code order_item.remark} would match readers of
     * {@code orders}.
     */
    @Test
    void joinedColumnsAreAttributedToTheirOwnTable(@TempDir Path tmp) throws IOException {
        Change change = mapperChange(tmp,
                "<insert id=\"insertOrder\">INSERT INTO orders (id, status) VALUES (#{id}, #{status})</insert>",
                "<insert id=\"insertOrder\">INSERT INTO orders (id, status, remark) "
                        + "VALUES (#{id}, #{status}, #{remark})</insert>");

        Path peerDir = tmp.resolve("r");
        write(peerDir, "pom.xml", "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>com.example</groupId><artifactId>joined</artifactId><version>1.0</version></project>");
        write(peerDir, "src/main/resources/mapper/Joined.xml", "<mapper namespace=\"com.example.j.Joined\">"
                + "<select id=\"load\">SELECT o.id, i.remark FROM orders o "
                + "JOIN order_item i ON i.order_id = o.id</select></mapper>");
        CodeIndex peer = index(peerDir);
        // Attribution happens at index time: the qualifier decides the owning table.
        assertTrue(peer.symbol("db:column:order_item.remark") != null,
                "i.remark must be indexed as order_item.remark");
        assertTrue(peer.symbol("db:column:orders.remark") == null,
                "and must not be attributed to orders as well");
        assertTrue(peer.symbol("db:column:orders.id") != null, "o.id still belongs to orders");

        PeerImpact result = analyzeAgainst(change, peer);

        assertTrue(result.matches.isEmpty(),
                "the peer reads order_item.remark, which this change did not touch: " + describe(result));
    }

    private static String describe(PeerImpact impact) {
        return impact.matches.stream().map(m -> m.kind + ":" + m.contract + ":" + m.usage).toList().toString();
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
