package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.model.CodeIndex;
import com.geek.association.model.Sym;
import com.geek.association.parse.CodeIndexer;
import com.geek.association.source.DirSource;
import com.geek.association.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing details that decide whether a database object is real or an artefact of the scanner. */
class SqlParsingTest {

    private static final AppProperties PROPS = new AppProperties();

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static CodeIndex index(Path root) {
        return new CodeIndexer(PROPS.getMaxFiles())
                .index(new DirSource(root, "test", PROPS.getMaxFiles()), "head", "abc");
    }

    private static Path project(Path root) throws IOException {
        write(root, "pom.xml", """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>orders</artifactId><version>1.0</version>
                </project>
                """);
        return root;
    }

    @Test
    void commentedAndIndexDdlDoNotBecomeColumnContracts(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/db/migration/V1__orders.sql", """
                -- ALTER TABLE orders DROP COLUMN remark;
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    remark VARCHAR(64),
                    created_at DATETIME
                );
                ALTER TABLE orders ADD INDEX idx_remark (remark);
                """);

        CodeIndex index = index(root);

        assertTrue(index.columnTypes.containsKey("orders.remark"),
                "the real column must still be in the schema snapshot");
        assertTrue(index.columnTypes.get("orders.remark").startsWith("VARCHAR"),
                "the declared type must be kept: " + index.columnTypes.get("orders.remark"));
        assertNull(index.columnTypes.get("orders.index"),
                "ADD INDEX is not a column called INDEX");
        assertTrue(index.symbols.values().stream().noneMatch(s -> "SQL_STATEMENT".equals(s.type)
                        && "DROP_COLUMN".equals(s.meta.get("ddl"))),
                "a commented-out ALTER must not become a schema change");
    }

    @Test
    void entityMappingUsesExplicitAnnotationsAndSkipsTransientFields(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/java/com/example/Order.java", """
                package com.example;

                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                import jakarta.persistence.Transient;
                import java.time.Instant;

                @Entity
                @Table(name = "orders")
                public class Order {
                    @Column(name = "created_at")
                    @JsonFormat(pattern = "yyyy-MM-dd")
                    private Instant createTime;

                    @Transient
                    private String cache;

                    private String URLPath;

                    @Column(name = "user_id")
                    private Long userID;
                }
                """);

        CodeIndex index = index(root);

        assertTrue(index.symbol("db:column:orders.created_at") != null,
                "an annotation between @Column and the field must not break the mapping");
        assertTrue(index.symbol("db:column:orders.url_path") != null,
                "URLPath must become url_path, not u_r_l_path");
        assertTrue(index.symbol("db:column:orders.user_id") != null,
                "userID must become user_id");
        assertNull(index.symbol("db:column:orders.cache"), "@Transient is not a column");
        assertNull(index.symbol("db:column:orders.user_i_d"),
                "the old per-character snake case guessed user_i_d");
    }

    @Test
    void migrationsAreAppliedInNumericVersionOrder(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/db/migration/V2__create.sql",
                "CREATE TABLE orders (id BIGINT, remark VARCHAR(64));");
        write(root, "src/main/resources/db/migration/V10__narrow.sql",
                "ALTER TABLE orders MODIFY remark VARCHAR(32);");

        CodeIndex index = index(root);

        assertTrue(index.columnTypes.get("orders.remark").equalsIgnoreCase("VARCHAR(32)"),
                "V10 must run after V2, not before it: " + index.columnTypes.get("orders.remark"));
    }

    @Test
    void multiActionAlterDoesNotPolluteTheFirstType(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/db/migration/V1__orders.sql",
                "ALTER TABLE orders ADD COLUMN a INT, ADD COLUMN b VARCHAR(10);");

        CodeIndex index = index(root);

        assertTrue(index.columnTypes.containsKey("orders.a"));
        assertTrue(index.columnTypes.get("orders.a").equalsIgnoreCase("INT"),
                "the first action's type must not swallow the second action: "
                        + index.columnTypes.get("orders.a"));
    }

    @Test
    void semicolonInsideADefaultDoesNotSplitTheCreateTable(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/db/migration/V1__orders.sql",
                "CREATE TABLE orders (remark VARCHAR(64) DEFAULT 'a;b', status INT);");

        CodeIndex index = index(root);

        assertTrue(index.columnTypes.containsKey("orders.remark"));
        assertTrue(index.columnTypes.containsKey("orders.status"),
                "the column after the quoted semicolon must still be parsed");
    }

    @Test
    void ddlInsideAStringLiteralDoesNotPolluteTheSnapshot(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/db/migration/V1__audit.sql", """
                CREATE TABLE audit_log (msg VARCHAR(255));
                INSERT INTO audit_log (msg) VALUES ('create table fake (x int)');
                """);

        CodeIndex index = index(root);

        assertNull(index.columnTypes.get("fake.x"),
                "a CREATE TABLE inside a string literal is data, not schema");
        assertTrue(index.columnTypes.containsKey("audit_log.msg"));
    }

    @Test
    void nativeQueryWithTrailingAttributesIsIndexed(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/java/com/example/OrderMapper.java", """
                package com.example;

                import org.springframework.data.jpa.repository.Query;

                public interface OrderMapper {
                    @Query(value = "SELECT remark FROM orders WHERE id = #{id}", nativeQuery = true)
                    String remark(Long id);
                }
                """);

        CodeIndex index = index(root);

        assertTrue(index.symbol("db:column:orders.remark") != null,
                "nativeQuery = true after the SQL must not hide the statement");
    }

    @Test
    void duplicateKeyUpdateDoesNotInventATable(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/mapper/OrderMapper.xml", """
                <mapper namespace="com.example.OrderMapper">
                  <insert id="upsert">
                    INSERT INTO orders (id, status) VALUES (#{id}, #{status})
                    ON DUPLICATE KEY UPDATE status = #{status}
                  </insert>
                </mapper>
                """);

        CodeIndex index = index(root);

        assertTrue(index.symbol("db:table:orders") != null, "the target table is orders");
        assertNull(index.symbol("db:table:status"),
                "ON DUPLICATE KEY UPDATE status is an assignment, not a table");
        assertTrue(index.symbol("db:column:orders.status") != null,
                "the duplicate-key assignment still writes orders.status");
    }

    @Test
    void insertSelectUsesItsTargetColumnList(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/mapper/OrderMapper.xml", """
                <mapper namespace="com.example.OrderMapper">
                  <insert id="archive">
                    INSERT INTO order_archive (id, created_at)
                    SELECT id, created_at FROM orders
                  </insert>
                </mapper>
                """);

        CodeIndex index = index(root);

        assertTrue(index.symbol("db:column:order_archive.created_at") != null,
                "INSERT ... SELECT writes the named target columns");
        assertTrue(index.symbol("db:table:orders") != null, "and reads the source table");
    }

    @Test
    void hashCommentsAndEscapedQuotesDoNotSplitStatements(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/db/migration/V1__orders.sql", """
                # a MySQL comment; this semicolon must not split the script
                CREATE TABLE orders (
                    id BIGINT,
                    remark VARCHAR(64)
                );
                INSERT INTO orders (id, remark) VALUES (1, 'it\\'s fine; really');
                ALTER TABLE orders ADD COLUMN note VARCHAR(32); # trailing comment; with a semicolon
                """);

        CodeIndex index = index(root);

        assertTrue(index.columnTypes.containsKey("orders.note"),
                "the statement after a # comment must still be parsed: " + index.columnTypes);
        assertTrue(statements(index).stream().anyMatch(sql -> sql.startsWith("INSERT INTO orders")
                        && sql.contains("really")),
                "a backslash-escaped quote must not end the string: " + statements(index));
        assertTrue(statements(index).stream().noneMatch(sql -> sql.startsWith("it")),
                "the text after the escaped quote still belongs to the INSERT statement");
    }

    @Test
    void mybatisPlaceholdersAndJsonOperatorsAreNotComments(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/mapper/OrderMapper.xml", """
                <mapper namespace="com.example.OrderMapper">
                  <select id="find" resultType="com.example.Order">
                    SELECT id, remark FROM orders WHERE id = #{id}
                  </select>
                </mapper>
                """);
        write(root, "src/main/resources/db/migration/V1__json.sql", """
                SELECT data #> '{a,b}' FROM orders;
                """);

        CodeIndex index = index(root);

        assertTrue(statements(index).stream().anyMatch(sql -> sql.contains("#{id}")),
                "a MyBatis placeholder is not a comment: " + statements(index));
        assertTrue(statements(index).stream().anyMatch(sql -> sql.contains("#>")),
                "a PostgreSQL JSON path operator is not a comment: " + statements(index));
    }

    // ------------------------------------------------- multi-action ALTER TABLE (F1)

    @Test
    void multiActionAlterAppliesEveryActionAndKeepsLegacyKeysOnTheFirst(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/db/migration/V1__orders.sql", """
                CREATE TABLE orders (
                    id BIGINT,
                    c INT
                );
                ALTER TABLE orders ADD COLUMN a INT, ADD COLUMN b VARCHAR(32), MODIFY COLUMN c BIGINT;
                """);

        CodeIndex index = index(root);

        assertEquals("INT", index.columnTypes.get("orders.a"),
                "the first ADD action must still be applied: " + index.columnTypes);
        assertEquals("VARCHAR(32)", index.columnTypes.get("orders.b"),
                "the second ADD used to be swallowed by the first action: " + index.columnTypes);
        assertEquals("BIGINT", index.columnTypes.get("orders.c"),
                "the MODIFY in the same statement must reach the snapshot too: " + index.columnTypes);

        Sym alter = statement(index, "ALTER TABLE orders ADD COLUMN a INT");
        assertEquals("ADD_COLUMN", alter.meta.get("ddl"),
                "legacy ddl must describe the first action only, not the last one seen");
        assertEquals("a", alter.meta.get("ddlColumn"),
                "legacy ddlColumn must belong to the same action as ddl");
        assertEquals("INT", alter.meta.get("ddlType"),
                "legacy ddlType must not be overwritten by a later action");
        assertNull(alter.meta.get("ddlNullable"),
                "no action stated nullability, so the legacy key must stay absent");
        assertNull(alter.meta.get("ddlDefault"),
                "the MODIFY action must not leak its definition onto the legacy keys");

        List<Map<String, Object>> actions = actions(alter);
        assertEquals(3, actions.size(),
                "every top-level action must be reported, not only the first: " + actions);
        assertEquals(List.of("ADD_COLUMN", "ADD_COLUMN", "MODIFY_COLUMN"),
                actions.stream().map(a -> a.get("kind")).toList(),
                "the action list must preserve statement order: " + actions);
        assertEquals("orders", actions.get(1).get("table"),
                "actions carry the normalised table name, because that is the snapshot key");
        assertEquals("b", actions.get(1).get("column"));
        assertEquals("VARCHAR(32)", actions.get(1).get("type"));
        assertNull(actions.get(1).get("nullable"),
                "an omitted nullability is unknown, not 'nullable'");
        assertEquals("", actions.get(1).get("schema"), "an unqualified table has no schema");
        assertEquals("", actions.get(1).get("renamedTo"), "only rename actions carry a target name");
        assertTrue(actions.get(1).keySet().containsAll(
                        List.of("kind", "table", "column", "type", "nullable", "default", "schema", "renamedTo")),
                "the change detector reads this JSON as a contract: all 8 fields must always be present");
    }

    @Test
    void addAndDropInOneAlterBothTakeEffectWithoutPollutingLegacyKeys(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/db/migration/V1__orders.sql", """
                CREATE TABLE orders (b VARCHAR(10));
                ALTER TABLE orders ADD COLUMN a INT, DROP COLUMN b;
                """);

        CodeIndex index = index(root);

        assertEquals("INT", index.columnTypes.get("orders.a"),
                "the ADD must be applied even though a DROP follows it");
        assertFalse(index.columnTypes.containsKey("orders.b"),
                "the DROP in the same statement must also be applied: " + index.columnTypes);
        assertFalse(index.columnNullability.containsKey("orders.b"),
                "a dropped column must not keep a nullability entry");
        assertFalse(index.columnDefaults.containsKey("orders.b"),
                "a dropped column must not keep a default entry");

        Sym alter = statement(index, "ALTER TABLE orders ADD COLUMN a INT");
        assertEquals("ADD_COLUMN", alter.meta.get("ddl"),
                "legacy ddl must stay the first action: the old code let DROP overwrite it");
        assertEquals("a", alter.meta.get("ddlColumn"));
        assertEquals("INT", alter.meta.get("ddlType"),
                "the ADD type must not survive on a record that now says DROP");
        assertNull(alter.meta.get("ddlDefault"),
                "the DROP action has no default, so the legacy key must be absent");
        assertEquals(List.of("ADD_COLUMN", "DROP_COLUMN"),
                actions(alter).stream().map(a -> a.get("kind")).toList(),
                "both actions must be visible to the change detector");
    }

    @Test
    void indexActionsInATableAlterNeverBecomeColumnContracts(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/db/migration/V1__orders.sql", """
                CREATE TABLE orders (id BIGINT, a INT, b VARCHAR(10));
                ALTER TABLE orders ADD INDEX idx_a (a), ADD COLUMN c VARCHAR(32), DROP INDEX idx_a;
                ALTER TABLE orders ADD INDEX idx_b (b);
                """);

        CodeIndex index = index(root);

        assertEquals("VARCHAR(32)", index.columnTypes.get("orders.c"),
                "the column action between two index actions must still be applied");
        assertTrue(index.columnTypes.containsKey("orders.a"),
                "an index action does not remove the column it indexes: " + index.columnTypes);
        assertFalse(index.columnTypes.containsKey("orders.index"), "INDEX is not a column");
        assertFalse(index.columnTypes.containsKey("orders.idx_a"), "an index name is not a column");

        Sym mixed = statement(index, "ALTER TABLE orders ADD INDEX idx_a");
        assertEquals("ADD_COLUMN", mixed.meta.get("ddl"),
                "an ALTER whose first action is an index still reports its column action");
        assertEquals("c", mixed.meta.get("ddlColumn"));
        List<Map<String, Object>> actions = actions(mixed);
        assertEquals(1, actions.size(),
                "index actions are not columns and must not be reported as actions: " + actions);
        assertEquals("ADD_COLUMN", actions.get(0).get("kind"));

        Sym indexOnly = statement(index, "ALTER TABLE orders ADD INDEX idx_b");
        assertNull(indexOnly.meta.get("ddl"),
                "an ALTER with only index actions is not a column change");
        assertNull(indexOnly.meta.get("ddlActions"),
                "no legacy ddl means no action list either; otherwise a reader would invent a change");
    }

    @Test
    void columnRenameMovesTheSnapshotAndTheFollowingActionStillApplies(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/db/migration/V1__orders.sql", """
                CREATE TABLE orders (
                    id BIGINT,
                    remark VARCHAR(64) NOT NULL DEFAULT 'x'
                );
                ALTER TABLE orders RENAME COLUMN remark TO note, ADD COLUMN created_at DATETIME;
                """);

        CodeIndex index = index(root);

        assertEquals("VARCHAR(64)", index.columnTypes.get("orders.note"),
                "a rename keeps the column type instead of losing it: " + index.columnTypes);
        assertEquals("false", index.columnNullability.get("orders.note"),
                "a rename keeps the column nullability");
        assertEquals("'x'", index.columnDefaults.get("orders.note"),
                "a rename keeps the column default");
        assertFalse(index.columnTypes.containsKey("orders.remark"),
                "the old column name must not linger in the snapshot");
        assertEquals("DATETIME", index.columnTypes.get("orders.created_at"),
                "the action after the rename must still be applied");

        Sym alter = statement(index, "ALTER TABLE orders RENAME COLUMN remark");
        assertEquals("DROP_COLUMN", alter.meta.get("ddl"),
                "legacy readers still see a rename as a drop of the old name");
        assertEquals("remark", alter.meta.get("ddlColumn"));
        assertEquals("note", alter.meta.get("ddlRenamedTo"),
                "the target column name is the one piece a legacy reader needs");
        List<Map<String, Object>> actions = actions(alter);
        assertEquals(2, actions.size(), "both the rename and the ADD must be reported: " + actions);
        assertEquals("RENAME_COLUMN", actions.get(0).get("kind"),
                "the action list keeps a rename distinct from a real drop");
        assertEquals("note", actions.get(0).get("renamedTo"));
    }

    @Test
    void changeColumnRenamesAndRetypesTheSnapshot(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/db/migration/V1__orders.sql", """
                CREATE TABLE orders (legacy INT);
                ALTER TABLE orders CHANGE COLUMN legacy amount DECIMAL(10,2) NOT NULL;
                """);

        CodeIndex index = index(root);

        assertEquals("DECIMAL(10,2)", index.columnTypes.get("orders.amount"),
                "CHANGE COLUMN renames and retypes in one action: " + index.columnTypes);
        assertEquals("false", index.columnNullability.get("orders.amount"),
                "the new definition also states nullability");
        assertFalse(index.columnTypes.containsKey("orders.legacy"),
                "the old name disappears with CHANGE COLUMN");

        Sym alter = statement(index, "ALTER TABLE orders CHANGE COLUMN legacy amount");
        List<Map<String, Object>> actions = actions(alter);
        assertEquals("RENAME_COLUMN", actions.get(0).get("kind"),
                "CHANGE is a rename plus a redefinition, so the peer rule for renames applies");
        assertEquals("legacy", actions.get(0).get("column"));
        assertEquals("amount", actions.get(0).get("renamedTo"));
        assertEquals("DECIMAL(10,2)", actions.get(0).get("type"),
                "the new definition's type must ride along, not be dropped by the rename");
        assertEquals("DROP_COLUMN", alter.meta.get("ddl"),
                "legacy metadata keeps the historical drop spelling");
        assertEquals("legacy", alter.meta.get("ddlColumn"));
        assertEquals("amount", alter.meta.get("ddlRenamedTo"));
    }

    // ------------------------------------------------------ table rename (F2)

    @Test
    void alterTableRenameMovesTheWholeSnapshotToTheNewName(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/db/migration/V1__orders.sql", """
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    remark VARCHAR(64) NOT NULL DEFAULT 'x'
                );
                ALTER TABLE orders RENAME TO orders_v2;
                """);

        CodeIndex index = index(root);

        assertEquals("VARCHAR(64)", index.columnTypes.get("orders_v2.remark"),
                "a renamed table is the same table, so its columns must move: " + index.columnTypes);
        assertEquals("false", index.columnNullability.get("orders_v2.id"),
                "primary-key nullability must move with the table");
        assertEquals("false", index.columnNullability.get("orders_v2.remark"),
                "NOT NULL must move with the table");
        assertEquals("'x'", index.columnDefaults.get("orders_v2.remark"),
                "a default must move with the table");
        assertFalse(index.columnTypes.containsKey("orders.remark"),
                "the old table name must not stay in the snapshot, or a stale contract would linger");
        assertFalse(index.columnNullability.containsKey("orders.id"),
                "the old table name must not keep a nullability entry");
        assertFalse(index.columnDefaults.containsKey("orders.remark"),
                "the old table name must not keep a default entry");

        Sym alter = statement(index, "ALTER TABLE orders RENAME TO orders_v2");
        assertEquals("RENAME_TABLE", alter.meta.get("ddl"));
        assertEquals("orders", alter.meta.get("ddlTable"), "ddlTable is the table that disappears");
        assertEquals("orders_v2", alter.meta.get("ddlRenamedToTable"));
        assertNull(alter.meta.get("ddlColumn"), "a table rename has no column");
        assertNull(alter.meta.get("ddlType"), "a table rename has no column type");
        List<Map<String, Object>> actions = actions(alter);
        assertEquals(1, actions.size());
        assertEquals("RENAME_TABLE", actions.get(0).get("kind"));
        assertEquals("orders", actions.get(0).get("table"));
        assertEquals("orders_v2", actions.get(0).get("renamedTo"));
        assertEquals("", actions.get(0).get("column"));
    }

    @Test
    void renameTableStatementAppliesEveryPairInOrder(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/db/migration/V1__orders.sql", """
                CREATE TABLE orders (remark VARCHAR(64) NOT NULL DEFAULT 'x');
                RENAME TABLE orders TO orders_v2, orders_v2 TO orders_archive;
                """);

        CodeIndex index = index(root);

        assertEquals("VARCHAR(64)", index.columnTypes.get("orders_archive.remark"),
                "every pair of a RENAME TABLE statement must be applied in order: " + index.columnTypes);
        assertEquals("false", index.columnNullability.get("orders_archive.remark"));
        assertEquals("'x'", index.columnDefaults.get("orders_archive.remark"));
        assertFalse(index.columnTypes.containsKey("orders.remark"),
                "the first name must be gone after the chain of renames");
        assertFalse(index.columnTypes.containsKey("orders_v2.remark"),
                "an intermediate name must not keep a copy of the shape");

        Sym rename = statement(index, "RENAME TABLE orders TO orders_v2");
        assertEquals("RENAME_TABLE", rename.meta.get("ddl"));
        assertEquals("orders", rename.meta.get("ddlTable"));
        assertEquals("orders_v2", rename.meta.get("ddlRenamedToTable"));
        assertNotNull(rename.meta.get("ddlSql"), "the statement records the SQL that made the change");
        List<Map<String, Object>> actions = actions(rename);
        assertEquals(2, actions.size(), "a comma-separated RENAME TABLE lists several renames: " + actions);
        assertEquals(List.of("orders", "orders_v2"),
                actions.stream().map(a -> a.get("table")).toList());
        assertEquals(List.of("orders_v2", "orders_archive"),
                actions.stream().map(a -> a.get("renamedTo")).toList());
    }

    @Test
    void schemaQualifiedDdlKeepsTheSnapshotKeyAndTheSchemaOnTheAction(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/db/migration/V1__orders.sql", """
                CREATE TABLE public.orders (id BIGINT, b VARCHAR(10));
                ALTER TABLE public.orders ADD COLUMN a INT, ADD COLUMN note VARCHAR(32);
                ALTER TABLE public.orders RENAME TO public.orders_v2;
                """);

        CodeIndex index = index(root);

        assertEquals("INT", index.columnTypes.get("orders_v2.a"),
                "a schema-qualified statement still keys the snapshot by table name: " + index.columnTypes);
        assertEquals("VARCHAR(32)", index.columnTypes.get("orders_v2.note"),
                "the schema-qualified rename must move the shape as well");
        assertFalse(index.columnTypes.containsKey("public.orders_v2.a"),
                "the schema must not become part of the snapshot key");

        Sym alter = statement(index, "ALTER TABLE public.orders ADD COLUMN a INT");
        assertEquals("public", alter.meta.get("ddlSchema"),
                "an explicit schema is evidence the database layer compares");
        assertEquals("public", actions(alter).get(0).get("schema"),
                "every action carries the schema of its table");
        assertEquals("orders", actions(alter).get(0).get("table"),
                "the action's table is the normalised name without schema");

        Sym rename = statement(index, "ALTER TABLE public.orders RENAME TO public.orders_v2");
        assertEquals("RENAME_TABLE", rename.meta.get("ddl"));
        assertEquals("public", rename.meta.get("ddlSchema"));
        assertEquals("public.orders", rename.meta.get("ddlTable"),
                "the legacy ddlTable keeps the reference as written");
        assertEquals("orders_v2", rename.meta.get("ddlRenamedToTable"),
                "the rename target is normalised, like every other action table");
    }

    @Test
    void addColumnNotNullAndQuotedDefaultReachTheActionContract(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/db/migration/V1__orders.sql", """
                CREATE TABLE orders (id BIGINT);
                ALTER TABLE orders ADD COLUMN flag INT NOT NULL, ADD COLUMN tag VARCHAR(8) DEFAULT 'a,b';
                """);

        CodeIndex index = index(root);

        List<Map<String, Object>> actions = actions(statement(index, "ALTER TABLE orders ADD COLUMN flag INT"));
        assertEquals(2, actions.size(),
                "a comma inside a quoted default must not split the action: " + actions);
        assertEquals("false", actions.get(0).get("nullable"),
                "NOT NULL is what makes writers fail, so it must be visible on the action");
        assertNull(actions.get(0).get("default"),
                "no default was stated, and a missing default is exactly what makes an ADD NOT NULL breaking");
        assertEquals("'a,b'", actions.get(1).get("default"),
                "the quoted default keeps its comma, quotes included");
        assertEquals("'a,b'", index.columnDefaults.get("orders.tag"));
        assertEquals("VARCHAR(8)", index.columnTypes.get("orders.tag"));
    }

    // --------------------------------------------------- legacy metadata (F1)

    @Test
    void singleActionStatementsKeepTheirPreviousMetadata(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/db/migration/V2__create.sql",
                "CREATE TABLE orders (remark VARCHAR(64));");
        write(root, "src/main/resources/db/migration/V3__narrow.sql",
                "ALTER TABLE orders MODIFY remark VARCHAR(32);");

        CodeIndex index = index(root);

        Sym modify = statement(index, "ALTER TABLE orders MODIFY remark VARCHAR(32)");
        assertEquals("MODIFY_COLUMN", modify.meta.get("ddl"));
        assertEquals("orders", modify.meta.get("ddlTable"));
        assertEquals("remark", modify.meta.get("ddlColumn"));
        assertEquals("VARCHAR(32)", modify.meta.get("ddlType"));
        assertEquals(List.of("MODIFY_COLUMN"),
                actions(modify).stream().map(a -> a.get("kind")).toList(),
                "a single-action statement writes a one-element action list");

        Sym create = statement(index, "CREATE TABLE orders");
        assertEquals("CREATE_TABLE", create.meta.get("ddl"));
        assertEquals(List.of("CREATE_TABLE"),
                actions(create).stream().map(a -> a.get("kind")).toList(),
                "CREATE TABLE is one action and must still be listed explicitly");
        assertEquals("orders", actions(create).get(0).get("table"));
    }

    @Test
    void dropTableCarriesOneActionAndNoColumn(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/db/migration/V1__create.sql",
                "CREATE TABLE orders (id BIGINT);");
        write(root, "src/main/resources/db/migration/V2__drop.sql",
                "DROP TABLE IF EXISTS orders;");

        CodeIndex index = index(root);

        Sym drop = statement(index, "DROP TABLE IF EXISTS orders");
        assertEquals("DROP_TABLE", drop.meta.get("ddl"));
        assertEquals("orders", drop.meta.get("ddlTable"));
        assertNull(drop.meta.get("ddlColumn"), "a table drop has no column");
        List<Map<String, Object>> actions = actions(drop);
        assertEquals(1, actions.size());
        assertEquals("DROP_TABLE", actions.get(0).get("kind"));
        assertEquals("orders", actions.get(0).get("table"));
    }

    @Test
    void unparsableAlterActionsAreIgnoredInsteadOfInvented(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/db/migration/V1__orders.sql", """
                CREATE TABLE orders (id BIGINT);
                ALTER TABLE orders ADD;
                ALTER TABLE orders MODIFY;
                ALTER TABLE orders DROP INDEX idx_x;
                """);

        CodeIndex index = index(root);

        for (String prefix : List.of("ALTER TABLE orders ADD", "ALTER TABLE orders MODIFY",
                "ALTER TABLE orders DROP INDEX idx_x")) {
            Sym sym = statement(index, prefix);
            assertNull(sym.meta.get("ddl"), "an action that cannot be parsed must not become a guess: " + prefix);
            assertNull(sym.meta.get("ddlActions"),
                    "a partial action list would make the change detector report things that never happened: "
                            + prefix);
        }
        assertEquals(List.of("orders.id"),
                index.columnTypes.keySet().stream().sorted().toList(),
                "no column may be invented by an unparsable action: " + index.columnTypes);
    }

    @Test
    void everyStatementOfAScriptKeepsItsOwnLineKey(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/db/migration/V1__orders.sql", """
                CREATE TABLE orders (id BIGINT);
                CREATE TABLE order_archive (id BIGINT);
                ALTER TABLE orders ADD COLUMN note VARCHAR(32);
                """);

        CodeIndex index = index(root);

        List<String> sql = statements(index);
        assertEquals(3, sql.size(),
                "a statement that starts right after a semicolon used to take the previous line, "
                        + "and its key then overwrote the earlier statement: " + sql);
        assertTrue(sql.stream().anyMatch(s -> s.startsWith("CREATE TABLE orders")),
                "the first statement must survive in the index: " + sql);
        assertTrue(sql.stream().anyMatch(s -> s.startsWith("CREATE TABLE order_archive")),
                "the second statement must survive in the index: " + sql);
        assertEquals(3, index.symbols.keySet().stream().filter(k -> k.startsWith("sql:")).count(),
                "symbol keys are file#line, so two statements on different lines must not share one");
        assertEquals(List.of(1, 2, 3), index.symbols.values().stream()
                        .filter(s -> "SQL_STATEMENT".equals(s.type))
                        .map(s -> s.startLine)
                        .sorted()
                        .toList(),
                "each statement must report the line it actually starts on: " + sql);
        assertTrue(index.columnTypes.containsKey("order_archive.id"),
                "both CREATE TABLE statements must reach the snapshot: " + index.columnTypes);
        assertEquals("VARCHAR(32)", index.columnTypes.get("orders.note"));
    }

    // ------------------------------ database-filled columns vs required columns (F3)

    @Test
    void databaseFilledColumnsAreNotRequiredWriteSideColumns(@TempDir Path tmp) throws IOException {
        Path root = project(tmp);
        write(root, "src/main/resources/db/migration/V1__orders.sql", """
                CREATE TABLE orders (id BIGINT);
                ALTER TABLE orders ADD COLUMN seq BIGINT NOT NULL AUTO_INCREMENT;
                ALTER TABLE orders ADD COLUMN g INT NOT NULL GENERATED ALWAYS AS (id + 1) STORED;
                ALTER TABLE orders ADD COLUMN label VARCHAR(16) NOT NULL COMMENT 'auto generated identity';
                ALTER TABLE orders ADD COLUMN plain INT NOT NULL;
                """);

        CodeIndex index = index(root);

        assertNull(firstAction(index, "ALTER TABLE orders ADD COLUMN seq").get("nullable"),
                "AUTO_INCREMENT fills the column, so an INSERT may omit it: a NOT NULL here would "
                        + "claim a write-side break that no caller can actually hit");
        assertNull(firstAction(index, "ALTER TABLE orders ADD COLUMN g INT").get("nullable"),
                "a GENERATED column is computed by the database and may be omitted by an INSERT");
        assertEquals("false", firstAction(index, "ALTER TABLE orders ADD COLUMN label").get("nullable"),
                "the words in a COMMENT string are text, not evidence of a generated column");
        assertEquals("false", firstAction(index, "ALTER TABLE orders ADD COLUMN plain INT").get("nullable"),
                "a genuinely required column must keep NOT NULL, or the required-column rule never fires");

        assertNull(statement(index, "ALTER TABLE orders ADD COLUMN seq").meta.get("ddlNullable"),
                "the legacy key must follow the same decision: no false NOT NULL");
        assertEquals("false", statement(index, "ALTER TABLE orders ADD COLUMN plain INT").meta.get("ddlNullable"),
                "a required column keeps its legacy NOT NULL flag");
        assertEquals("false", index.columnNullability.get("orders.plain"),
                "a required column is still stored as NOT NULL in the snapshot");
    }

    private static Sym statement(CodeIndex index, String prefix) {
        return index.symbols.values().stream()
                .filter(s -> "SQL_STATEMENT".equals(s.type))
                .filter(s -> s.meta.getOrDefault("sql", "").startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no SQL statement starting with '" + prefix + "': " + statements(index)));
    }

    private static List<Map<String, Object>> actions(Sym sym) {
        String json = sym.meta.get("ddlActions");
        assertNotNull(json, "a statement with a legacy ddl key must also carry the full action list");
        return Json.readList(json);
    }

    private static Map<String, Object> firstAction(CodeIndex index, String prefix) {
        return actions(statement(index, prefix)).get(0);
    }

    private static List<String> statements(CodeIndex index) {
        return index.symbols.values().stream()
                .filter(s -> "SQL_STATEMENT".equals(s.type))
                .map(s -> s.meta.getOrDefault("sql", ""))
                .toList();
    }
}
