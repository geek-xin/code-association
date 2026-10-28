package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.model.CodeIndex;
import com.geek.association.parse.CodeIndexer;
import com.geek.association.source.DirSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    private static List<String> statements(CodeIndex index) {
        return index.symbols.values().stream()
                .filter(s -> "SQL_STATEMENT".equals(s.type))
                .map(s -> s.meta.getOrDefault("sql", ""))
                .toList();
    }
}
