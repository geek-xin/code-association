package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.diff.ChangeDetector;
import com.geek.association.model.ChangeRecord;
import com.geek.association.model.CodeIndex;
import com.geek.association.model.FileChange;
import com.geek.association.model.Sym;
import com.geek.association.parse.CodeIndexer;
import com.geek.association.source.DirSource;
import com.geek.association.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DDL detection: one statement can carry several actions, and each action is a separate contract.
 *
 * The symbols are built by hand - a {@code ddlActions} array alongside the historical single-action
 * metadata - so these cases describe the detection rules themselves instead of what the SQL parser
 * happens to produce for one spelling of a statement. The end-to-end cases at the bottom run a real
 * parsed migration to make sure the hand-built symbols are not lying.
 */
class DdlDetectionTest {

    private static final AppProperties PROPS = new AppProperties();
    private static final String MIGRATION = "src/main/resources/db/migration/V1__orders.sql";
    private static final String RENAME_MIGRATION = "src/main/resources/db/migration/V2__rename_amount.sql";
    private static final String SQL_KEY = "sql:" + MIGRATION + "#2";
    private static final String POM = """
            <project><modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId><artifactId>order-service</artifactId><version>1.0</version>
            </project>
            """;

    // ---------------------------------------------------------------- helpers

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static CodeIndex index(Path root) {
        return new CodeIndexer(PROPS.getMaxFiles())
                .index(new DirSource(root, "test", PROPS.getMaxFiles()), "head", "abc");
    }

    /** A SQL statement symbol as the analyzer shapes it: keyed by file and line, metadata in meta. */
    private static Sym statement(String key, int line, Map<String, String> meta) {
        Sym sym = new Sym();
        sym.key = key;
        sym.type = "SQL_STATEMENT";
        sym.qualifiedName = "sql " + key;
        sym.filePath = MIGRATION;
        sym.startLine = line;
        sym.endLine = line;
        sym.modulePath = "";
        sym.bodyHash = "body-" + key;
        sym.meta.putAll(meta);
        return sym;
    }

    private static CodeIndex index(Sym... syms) {
        CodeIndex index = new CodeIndex();
        for (Sym sym : syms) {
            index.addSymbol(sym);
        }
        return index;
    }

    private static List<ChangeRecord> detect(CodeIndex base, CodeIndex head) {
        return new ChangeDetector(base, head).detect(List.of(FileChange.of("MODIFIED", MIGRATION)));
    }

    private static List<ChangeRecord> ddl(List<ChangeRecord> records) {
        List<ChangeRecord> out = new ArrayList<>();
        for (ChangeRecord record : records) {
            if (ChangeDetector.DDL_CHANGED.equals(record.changeType)) {
                out.add(record);
            }
        }
        return out;
    }

    private static ChangeRecord only(List<ChangeRecord> records) {
        assertEquals(1, records.size(), "exactly one DDL record expected, got: " + describe(records));
        return records.get(0);
    }

    private static String describe(List<ChangeRecord> records) {
        StringBuilder out = new StringBuilder();
        for (ChangeRecord record : records) {
            out.append('[').append(record.changeType)
                    .append(' ').append(record.extra.get("ddl"))
                    .append(' ').append(record.extra.get("table")).append('.').append(record.extra.get("column"))
                    .append(" risk=").append(record.riskSeed)
                    .append(" ").append(record.semanticSummary).append(']');
        }
        return out.toString();
    }

    /** One {@code ddlActions} element exactly as the analyzer writes it: eight fields, in order. */
    private static Map<String, Object> action(String kind, String table, String column, String type,
                                              String nullable, String defaultValue, String renamedTo) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("kind", kind);
        action.put("table", table);
        action.put("column", column);
        action.put("type", type);
        action.put("nullable", nullable);
        action.put("default", defaultValue);
        action.put("schema", "");
        action.put("renamedTo", renamedTo);
        return action;
    }

    private static String actions(List<Map<String, Object>> items) {
        return Json.write(items);
    }

    /** The historical single-action metadata, the fallback for a symbol without {@code ddlActions}. */
    private static Map<String, String> legacy(String kind, String table, String column) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("ddl", kind);
        meta.put("ddlTable", table);
        if (column != null) {
            meta.put("ddlColumn", column);
        }
        return meta;
    }

    private static Map<String, String> copy(Map<String, String> meta) {
        return new LinkedHashMap<>(meta);
    }

    // ------------------------------------------------------ several actions

    @Test
    void everyActionOfOneAlterIsReported() {
        Map<String, String> before = legacy("ADD_COLUMN", "orders", "a");
        before.put("ddlType", "INT");
        before.put("ddlActions", actions(List.of(
                action("ADD_COLUMN", "orders", "a", "INT", null, null, ""))));

        Map<String, String> after = legacy("ADD_COLUMN", "orders", "a");
        after.put("ddlType", "INT");
        after.put("ddlActions", actions(List.of(
                action("ADD_COLUMN", "orders", "a", "INT", null, null, ""),
                action("ADD_COLUMN", "orders", "b", "VARCHAR(64)", null, null, ""))));

        List<ChangeRecord> records = ddl(detect(index(statement(SQL_KEY, 2, before)),
                index(statement(SQL_KEY, 2, after))));

        ChangeRecord record = only(records);
        assertEquals("ADD_COLUMN", record.extra.get("ddl"));
        assertEquals("orders", record.extra.get("table"));
        assertEquals("b", record.extra.get("column"),
                "the second action is the one that moved; reporting only the first one is the bug: "
                        + describe(records));
        assertEquals("", record.extra.get("oldType"), "the column did not exist in the base revision");
        assertEquals("VARCHAR(64)", record.extra.get("newType"));
        assertTrue(record.extra.containsKey("oldDefault") && record.extra.containsKey("newDefault"),
                "the required-column rule needs both defaults on every DDL record: " + record.extra);
        assertEquals(0.4, record.riskSeed, 0.0001,
                "a nullable addition stays low risk, exactly like a single-action statement");
    }

    @Test
    void mixedActionsDoNotLeakFieldsBetweenRecords() {
        Map<String, String> after = legacy("ADD_COLUMN", "orders", "created_at");
        after.put("ddlType", "TIMESTAMP");
        after.put("ddlActions", actions(List.of(
                action("ADD_COLUMN", "orders", "created_at", "TIMESTAMP", null, null, ""),
                action("DROP_COLUMN", "orders", "legacy_flag", null, null, null, ""))));

        CodeIndex base = index(statement(SQL_KEY, 2, new LinkedHashMap<>()));
        base.columnTypes.put("orders.legacy_flag", "TINYINT");

        List<ChangeRecord> records = ddl(detect(base, index(statement(SQL_KEY, 2, after))));

        assertEquals(2, records.size(), "both actions must be reported: " + describe(records));
        ChangeRecord added = records.get(0);
        ChangeRecord dropped = records.get(1);
        assertEquals("ADD_COLUMN", added.extra.get("ddl"));
        assertEquals("created_at", added.extra.get("column"));
        assertEquals("TIMESTAMP", added.extra.get("newType"));
        assertEquals("DROP_COLUMN", dropped.extra.get("ddl"));
        assertEquals("legacy_flag", dropped.extra.get("column"));
        assertEquals("TINYINT", dropped.extra.get("oldType"),
                "a dropped column keeps its own old type: " + dropped.extra);
        assertEquals("", dropped.extra.get("newType"),
                "the added column's type must not leak into the drop record, which was the mixed-action bug: "
                        + describe(records));
    }

    @Test
    void anActionRemovedFromTheScriptIsNotASchemaRollback() {
        Map<String, String> before = legacy("ADD_COLUMN", "orders", "a");
        before.put("ddlActions", actions(List.of(
                action("ADD_COLUMN", "orders", "a", "INT", null, null, ""),
                action("ADD_COLUMN", "orders", "b", "INT", null, null, ""))));
        Map<String, String> after = legacy("ADD_COLUMN", "orders", "a");
        after.put("ddlActions", actions(List.of(
                action("ADD_COLUMN", "orders", "a", "INT", null, null, ""))));

        ChangeRecord record = only(ddl(detect(index(statement(SQL_KEY, 2, before)),
                index(statement(SQL_KEY, 2, after)))));

        assertEquals("ADD_COLUMN", record.extra.get("ddl"), "the removed action keeps its own kind");
        assertEquals("b", record.extra.get("column"));
        assertEquals("true", record.extra.get("ddlRemoved"),
                "removing the declaration is a script edit, not a schema rollback: " + record.extra);
        assertTrue(record.semanticSummary.contains("移除 DDL 动作"),
                "the summary must say the action disappeared: " + record.semanticSummary);
        assertTrue(record.semanticSummary.contains("不会回滚"),
                "an operator must not read it as a schema change: " + record.semanticSummary);
    }

    @Test
    void renamingTablesReportsTheOldTableAsTheContract() {
        Map<String, String> after = legacy("RENAME_TABLE", "orders", null);
        after.put("ddlRenamedToTable", "orders_v2");
        after.put("ddlActions", actions(List.of(
                action("RENAME_TABLE", "orders", null, null, null, null, "orders_v2"),
                action("RENAME_TABLE", "users", null, null, null, null, "members"))));

        List<ChangeRecord> records = ddl(detect(index(statement(SQL_KEY, 2, new LinkedHashMap<>())),
                index(statement(SQL_KEY, 2, after))));

        assertEquals(2, records.size(),
                "a RENAME TABLE with several pairs is several table contracts: " + describe(records));
        ChangeRecord orders = records.get(0);
        assertEquals("RENAME_TABLE", orders.extra.get("ddl"));
        assertEquals("orders", orders.extra.get("table"),
                "the old table name is what the other program still uses: " + orders.extra);
        assertEquals("", orders.extra.get("column"), "a table rename has no column");
        assertEquals("orders_v2", orders.extra.get("renamedToTable"));
        assertEquals(0.9, orders.riskSeed, 0.0001, "every use of the old name is affected");
        assertEquals("重命名数据库表：orders → orders_v2", orders.semanticSummary);
        assertEquals("members", records.get(1).extra.get("renamedToTable"));
    }

    // ------------------------------------------------- required columns (F3)

    private static ChangeRecord addedColumn(String nullable, String defaultValue) {
        Map<String, String> meta = legacy("ADD_COLUMN", "orders", "phone");
        meta.put("ddlType", "VARCHAR(32)");
        meta.put("ddlActions", actions(List.of(
                action("ADD_COLUMN", "orders", "phone", "VARCHAR(32)", nullable, defaultValue, ""))));
        return only(ddl(detect(index(statement(SQL_KEY, 2, new LinkedHashMap<>())),
                index(statement(SQL_KEY, 2, meta)))));
    }

    @Test
    void aNotNullColumnWithoutADefaultIsAWriteSideBreak() {
        ChangeRecord required = addedColumn("false", null);
        assertEquals("false", required.extra.get("newNullable"));
        assertEquals("", required.extra.get("newDefault"));
        assertEquals(0.7, required.riskSeed, 0.0001,
                "an INSERT that omits the column is rejected, so this is not an invisible addition");
        assertTrue(required.semanticSummary.endsWith("（NOT NULL 且无默认值，写入方需补列）"),
                "the reader has to be told why the seed went up: " + required.semanticSummary);
    }

    @Test
    void aNullableOrDefaultedAdditionKeepsTheLowRisk() {
        ChangeRecord nullable = addedColumn("true", null);
        assertEquals(0.4, nullable.riskSeed, 0.0001, "code that names its columns cannot observe it");
        assertFalse(nullable.semanticSummary.contains("NOT NULL"), nullable.semanticSummary);

        ChangeRecord withDefault = addedColumn("false", "''");
        assertEquals(0.4, withDefault.riskSeed, 0.0001,
                "a default keeps a columnless INSERT valid, so the addition stays invisible");
        assertFalse(withDefault.semanticSummary.contains("NOT NULL"), withDefault.semanticSummary);
    }

    // ------------------------------------------------- single action compat

    @Test
    void anUnchangedDdlStatementStaysSilent() {
        Map<String, String> meta = legacy("MODIFY_COLUMN", "orders", "remark");
        meta.put("ddlType", "VARCHAR(64)");

        List<ChangeRecord> records = ddl(detect(index(statement(SQL_KEY, 2, meta)),
                index(statement(SQL_KEY, 2, copy(meta)))));

        assertTrue(records.isEmpty(),
                "an action that did not move must not produce a record: " + describe(records));
    }

    @Test
    void aLegacyModifyStillProducesTheHistoricalRecord() {
        Map<String, String> before = legacy("MODIFY_COLUMN", "orders", "remark");
        before.put("ddlType", "VARCHAR(64)");
        Map<String, String> after = legacy("MODIFY_COLUMN", "orders", "remark");
        after.put("ddlType", "VARCHAR(32)");

        ChangeRecord record = only(ddl(detect(index(statement(SQL_KEY, 2, before)),
                index(statement(SQL_KEY, 2, after)))));

        assertEquals("MODIFY_COLUMN", record.extra.get("ddl"));
        assertEquals("TYPE", record.extra.get("schemaChange"));
        assertEquals("VARCHAR(64)", record.extra.get("oldType"));
        assertEquals("VARCHAR(32)", record.extra.get("newType"));
        assertEquals(0.9, record.riskSeed, 0.0001, "a narrowing type can reject existing values");
        assertEquals("MODIFY_COLUMN orders.remark VARCHAR(64)", record.oldValue);
        assertEquals("MODIFY_COLUMN orders.remark VARCHAR(32)", record.newValue);
        assertEquals("修改数据库字段类型：orders.remark（VARCHAR(64) → VARCHAR(32)）",
                record.semanticSummary,
                "a symbol without ddlActions must keep the exact record wording it had before");
    }

    @Test
    void aLegacyColumnRenameIsStillTheDropOfTheOldName() {
        Map<String, String> after = legacy("DROP_COLUMN", "orders", "remark");
        after.put("ddlRenamedTo", "note");

        ChangeRecord record = only(ddl(detect(index(statement(SQL_KEY, 2, new LinkedHashMap<>())),
                index(statement(SQL_KEY, 2, after)))));

        assertEquals("DROP_COLUMN", record.extra.get("ddl"),
                "the old column name is the contract other programs break on: " + record.extra);
        assertEquals("remark", record.extra.get("column"));
        assertEquals("note", record.extra.get("renamedTo"));
        assertEquals("重命名数据库字段：orders.remark → orders.note", record.semanticSummary);
    }

    @Test
    void aRenameColumnActionKeepsTheDropColumnContract() {
        Map<String, String> after = new LinkedHashMap<>();
        after.put("ddlActions", actions(List.of(
                action("RENAME_COLUMN", "orders", "remark", null, null, null, "note"))));

        ChangeRecord record = only(ddl(detect(index(statement(SQL_KEY, 2, new LinkedHashMap<>())),
                index(statement(SQL_KEY, 2, after)))));

        assertEquals("DROP_COLUMN", record.extra.get("ddl"),
                "a rename is a drop of the old name plus an add of the new one, so the peer rules "
                        + "that already handle drops keep working: " + record.extra);
        assertEquals("remark", record.extra.get("column"));
        assertEquals("note", record.extra.get("renamedTo"),
                "the new name is informational: it cannot already be a dependency");
        assertTrue(record.semanticSummary.startsWith("重命名数据库字段："), record.semanticSummary);
    }

    @Test
    void aModifyIsAlignedPerColumnAndNamesTheChangedFacet() {
        Map<String, String> before = new LinkedHashMap<>();
        before.put("ddlActions", actions(List.of(
                action("MODIFY_COLUMN", "orders", "remark", "VARCHAR(64)", "true", null, ""),
                action("MODIFY_COLUMN", "orders", "status", "VARCHAR(16)", "true", null, ""))));
        Map<String, String> after = new LinkedHashMap<>();
        after.put("ddlActions", actions(List.of(
                action("MODIFY_COLUMN", "orders", "remark", "VARCHAR(64)", "false", null, ""),
                action("MODIFY_COLUMN", "orders", "status", "VARCHAR(16)", "true", null, ""))));

        ChangeRecord record = only(ddl(detect(index(statement(SQL_KEY, 2, before)),
                index(statement(SQL_KEY, 2, after)))));

        assertEquals("remark", record.extra.get("column"),
                "only the column whose nullability moved is a change: " + describe(List.of(record)));
        assertEquals("NULLABILITY", record.extra.get("schemaChange"),
                "the type did not move, so the nullability is the facet that changed");
        assertEquals("true", record.extra.get("oldNullable"));
        assertEquals("false", record.extra.get("newNullable"));
        assertEquals(0.8, record.riskSeed, 0.0001, "tightening NOT NULL hits existing writers");
    }

    @Test
    void repeatedActionForTheSameColumnUsesTheLastDeclaration() {
        Map<String, String> after = new LinkedHashMap<>();
        after.put("ddlActions", actions(List.of(
                action("MODIFY_COLUMN", "orders", "remark", "VARCHAR(32)", "true", null, ""),
                action("MODIFY_COLUMN", "orders", "remark", "VARCHAR(128)", "true", null, ""))));

        ChangeRecord record = only(ddl(detect(index(statement(SQL_KEY, 2, new LinkedHashMap<>())),
                index(statement(SQL_KEY, 2, after)))));

        assertEquals("VARCHAR(128)", record.extra.get("newType"),
                "the last declaration is the state the schema snapshot ends up with: " + record.extra);
    }

    @Test
    void aDdlChangeSupersedesThePlainSqlChange() {
        Map<String, String> before = legacy("ADD_COLUMN", "orders", "a");
        before.put("ddlType", "INT");
        before.put("ddlActions", actions(List.of(
                action("ADD_COLUMN", "orders", "a", "INT", null, null, ""))));
        Map<String, String> after = legacy("ADD_COLUMN", "orders", "a");
        after.put("ddlType", "BIGINT");
        after.put("ddlActions", actions(List.of(
                action("ADD_COLUMN", "orders", "a", "BIGINT", null, null, ""))));

        Sym base = statement(SQL_KEY, 2, before);
        base.bodyHash = "before";
        Sym head = statement(SQL_KEY, 2, after);
        head.bodyHash = "after";

        List<ChangeRecord> records = detect(index(base), index(head));

        assertEquals(1, records.size(),
                "the DDL record already explains the edit; a second SQL_CHANGED would double count it: "
                        + describe(records));
        assertEquals(ChangeDetector.DDL_CHANGED, records.get(0).changeType);
    }

    // ------------------------------------------------------ snapshot dedup

    @Test
    void theSnapshotDiffDoesNotRepeatAnActionRecord() {
        Map<String, String> create = legacy("CREATE_TABLE", "orders", null);
        Map<String, String> add = legacy("ADD_COLUMN", "orders", "remark");
        add.put("ddlType", "VARCHAR(64)");
        add.put("ddlActions", actions(List.of(
                action("ADD_COLUMN", "orders", "remark", "VARCHAR(64)", null, null, ""))));

        CodeIndex base = index(statement("sql:" + MIGRATION + "#1", 1, create));
        base.columnTypes.put("orders.id", "BIGINT");
        CodeIndex head = index(statement("sql:" + MIGRATION + "#1", 1, copy(create)),
                statement("sql:" + MIGRATION + "#3", 3, add));
        head.columnTypes.put("orders.id", "BIGINT");
        head.columnTypes.put("orders.remark", "VARCHAR(64)");

        List<ChangeRecord> records = ddl(detect(base, head));

        assertEquals(1, records.size(),
                "the statement record and the schema snapshot describe the same added column and "
                        + "must not be reported twice: " + describe(records));
        assertEquals("ADD_COLUMN", records.get(0).extra.get("ddl"));
        assertEquals("orders.remark",
                records.get(0).extra.get("table") + "." + records.get(0).extra.get("column"));
    }

    @Test
    void aSnapshotOnlyRequiredAdditionCarriesTheWriteSideRisk() {
        Map<String, String> create = legacy("CREATE_TABLE", "orders", null);

        CodeIndex base = index(statement("sql:" + MIGRATION + "#1", 1, create));
        base.columnTypes.put("orders.id", "BIGINT");
        CodeIndex head = index(statement("sql:" + MIGRATION + "#1", 1, copy(create)));
        head.columnTypes.put("orders.id", "BIGINT");
        head.columnTypes.put("orders.vip", "INT");
        head.columnNullability.put("orders.vip", "false");

        ChangeRecord record = only(ddl(detect(base, head)));

        assertEquals("ADD_COLUMN", record.extra.get("ddl"));
        assertEquals("false", record.extra.get("newNullable"),
                "the snapshot diff has to carry the nullability, or the required-column rule cannot fire");
        assertEquals("", record.extra.get("newDefault"));
        assertEquals(0.7, record.riskSeed, 0.0001,
                "a NOT NULL column without a default rejects a columnless INSERT, whatever detected it");
        assertTrue(record.semanticSummary.contains("NOT NULL 且无默认值"), record.semanticSummary);
    }

    // --------------------------------------------------------- end to end

    @Test
    void aColumnRenameDoesNotCountTheNewNameAsAnAddedColumn(@TempDir Path tmp) throws IOException {
        String create = "CREATE TABLE orders (id BIGINT, amount DECIMAL(10,2));";
        Path base = tmp.resolve("base");
        write(base, "pom.xml", POM);
        write(base, MIGRATION, create);
        Path head = tmp.resolve("head");
        write(head, "pom.xml", POM);
        write(head, MIGRATION, create);
        write(head, RENAME_MIGRATION, "ALTER TABLE orders RENAME COLUMN amount TO total;");

        List<ChangeRecord> records = ddl(new ChangeDetector(index(base), index(head))
                .detect(List.of(FileChange.of("MODIFIED", MIGRATION),
                        FileChange.of("ADDED", RENAME_MIGRATION))));

        List<ChangeRecord> renamed = new ArrayList<>();
        for (ChangeRecord record : records) {
            if ("DROP_COLUMN".equals(record.extra.get("ddl"))
                    && "orders".equals(record.extra.get("table"))
                    && "amount".equals(record.extra.get("column"))) {
                renamed.add(record);
            }
        }
        assertEquals(1, renamed.size(),
                "a column rename is one record on the old name; the snapshot drop must not repeat it: "
                        + describe(records));
        assertEquals("total", renamed.get(0).extra.get("renamedTo"),
                "the new name is informational, the old name is the contract");

        assertFalse(records.stream().anyMatch(record -> "ADD_COLUMN".equals(record.extra.get("ddl"))
                        && "total".equals(record.extra.get("column"))),
                "the new name is the same column, not a column addition: " + describe(records));
        assertFalse(records.stream().anyMatch(record -> "true".equals(String.valueOf(record.extra.get("schemaDiff")))
                        && "total".equals(record.extra.get("column"))),
                "the schema snapshot must not turn a rename target into an invisible added column, "
                        + "or the report counts a change no consumer can observe: " + describe(records));
    }

    @Test
    void aParsedSingleActionMigrationKeepsItsHistoricalRecord(@TempDir Path tmp) throws IOException {
        Path base = tmp.resolve("base");
        write(base, "pom.xml", POM);
        write(base, MIGRATION, "CREATE TABLE orders (id BIGINT);\n"
                + "ALTER TABLE orders MODIFY remark VARCHAR(64);");
        Path head = tmp.resolve("head");
        write(head, "pom.xml", POM);
        write(head, MIGRATION, "CREATE TABLE orders (id BIGINT);\n"
                + "ALTER TABLE orders MODIFY remark VARCHAR(32);");

        List<ChangeRecord> records = ddl(new ChangeDetector(index(base), index(head))
                .detect(List.of(FileChange.of("MODIFIED", MIGRATION))));

        ChangeRecord record = only(records);
        assertEquals("MODIFY_COLUMN", record.extra.get("ddl"));
        assertEquals("orders.remark",
                record.extra.get("table") + "." + record.extra.get("column"));
        assertEquals(0.9, record.riskSeed, 0.0001, "the narrowing is a real break for readers/writers");
    }

    @Test
    void aParsedMultiActionAlterReportsEveryAction(@TempDir Path tmp) throws IOException {
        Path base = tmp.resolve("base");
        write(base, "pom.xml", POM);
        write(base, MIGRATION, "CREATE TABLE orders (id BIGINT, legacy_flag TINYINT);");
        Path head = tmp.resolve("head");
        write(head, "pom.xml", POM);
        write(head, MIGRATION, "CREATE TABLE orders (id BIGINT, legacy_flag TINYINT);"
                + "\nALTER TABLE orders ADD COLUMN phone VARCHAR(32) NOT NULL, DROP COLUMN legacy_flag;");

        List<ChangeRecord> records = ddl(new ChangeDetector(index(base), index(head))
                .detect(List.of(FileChange.of("MODIFIED", MIGRATION))));

        List<ChangeRecord> additions = new ArrayList<>();
        List<ChangeRecord> drops = new ArrayList<>();
        for (ChangeRecord record : records) {
            if ("ADD_COLUMN".equals(record.extra.get("ddl"))) {
                additions.add(record);
            }
            if ("DROP_COLUMN".equals(record.extra.get("ddl"))) {
                drops.add(record);
            }
        }
        assertEquals(1, additions.size(),
                "the parser and the detector together must see the added column once: " + describe(records));
        assertEquals(1, drops.size(),
                "one ALTER with ADD and DROP is two contracts, and the dropped column is the dangerous one: "
                        + describe(records));
        assertEquals("phone", additions.get(0).extra.get("column"));
        assertEquals(0.7, additions.get(0).riskSeed, 0.0001,
                "NOT NULL without a default rejects an INSERT that omits the column");
        assertTrue(additions.get(0).semanticSummary.contains("写入方需补列"),
                additions.get(0).semanticSummary);
        assertEquals("legacy_flag", drops.get(0).extra.get("column"));
        assertEquals("TINYINT", drops.get(0).extra.get("oldType"), "the drop keeps its own old type");
    }

    @Test
    void aParsedRequiredColumnAdditionKeepsTheHigherRisk(@TempDir Path tmp) throws IOException {
        Path base = tmp.resolve("base");
        write(base, "pom.xml", POM);
        write(base, MIGRATION, "CREATE TABLE orders (id BIGINT);");
        Path head = tmp.resolve("head");
        write(head, "pom.xml", POM);
        write(head, MIGRATION, "CREATE TABLE orders (id BIGINT);\n"
                + "ALTER TABLE orders ADD COLUMN phone VARCHAR(32) NOT NULL;");

        List<ChangeRecord> records = ddl(new ChangeDetector(index(base), index(head))
                .detect(List.of(FileChange.of("MODIFIED", MIGRATION))));

        List<ChangeRecord> additions = new ArrayList<>();
        for (ChangeRecord record : records) {
            if ("ADD_COLUMN".equals(record.extra.get("ddl")) && "phone".equals(record.extra.get("column"))) {
                additions.add(record);
            }
        }
        assertEquals(1, additions.size(),
                "the parsed action and the schema snapshot must agree on one record: " + describe(records));
        assertEquals(0.7, additions.get(0).riskSeed, 0.0001,
                "a real parsed ADD COLUMN ... NOT NULL without a default is a write-side break");
        assertTrue(additions.get(0).semanticSummary.contains("写入方需补列"),
                additions.get(0).semanticSummary);
    }
}
