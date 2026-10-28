package com.geek.association;

import com.geek.association.parse.DatabaseIdentity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Database identity is evidence, not a guess: only one unambiguous declaration counts. */
class DatabaseIdentityTest {

    @Test
    void readsTheDatabaseFromCommonJdbcShapes() {
        assertEquals("order_db", DatabaseIdentity.databaseOf(
                "jdbc:mysql://db:3306/order_db?useSSL=false"));
        assertEquals("order_db", DatabaseIdentity.databaseOf(
                "jdbc:postgresql://db:5432/order_db"));
        assertEquals("order_db", DatabaseIdentity.databaseOf(
                "jdbc:sqlserver://db:1433;databaseName=order_db;encrypt=false"));
        assertEquals("ORCL", DatabaseIdentity.databaseOf(
                "jdbc:oracle:thin:@db:1521:ORCL"));
        assertEquals("order_db", DatabaseIdentity.databaseOf(
                "r2dbc:mysql://db:3306/order_db"));
    }

    @Test
    void embeddedDatabasesAreUnknownBecauseTheyAreProcessLocal() {
        assertNull(DatabaseIdentity.databaseOf("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1"));
        assertNull(DatabaseIdentity.databaseOf("jdbc:hsqldb:mem:test"));
        assertNull(DatabaseIdentity.databaseOf("jdbc:sqlite:./data/test.db"));
    }

    @Test
    void aPlaceholderOrMissingDatabaseIsUnknown() {
        assertNull(DatabaseIdentity.databaseOf("${DB_URL}"));
        assertNull(DatabaseIdentity.databaseOf("jdbc:mysql://db:3306/"));
        assertNull(DatabaseIdentity.databaseOf(null));
        assertNull(DatabaseIdentity.databaseOf("jdbc:mysql://db:3306/" + "$" + "{DB_NAME}"),
                "an unresolved database name is unknown, not an identity");
        assertNull(DatabaseIdentity.databaseOf("jdbc:postgresql://h/?currentSchema=x"),
                "a schema-only URL does not name a database");
    }

    @Test
    void oneUnambiguousDeclarationIsRequired() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("spring.datasource.url", "jdbc:mysql://db:3306/order_db");
        assertEquals("order_db", DatabaseIdentity.databaseName(config));

        config.put("spring.datasource.secondary.url", "jdbc:mysql://db:3306/billing_db");
        assertNull(DatabaseIdentity.databaseName(config),
                "two different databases are ambiguous, not a reason to pick one");
    }

    @Test
    void defaultSchemaIsReadFromTheUsualKeys() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("spring.jpa.properties.hibernate.default_schema", "orders");
        assertEquals("orders", DatabaseIdentity.defaultSchema(config));

        config.put("spring.datasource.hikari.schema", "billing");
        assertNull(DatabaseIdentity.defaultSchema(config));

        Map<String, String> initScript = new LinkedHashMap<>();
        initScript.put("spring.datasource.schema", "schema.sql");
        assertNull(DatabaseIdentity.defaultSchema(initScript),
                "spring.datasource.schema is a SQL init script, not a schema name");
    }

    @Test
    void unknownOnEitherSideIsNotAMismatch() {
        assertTrue(DatabaseIdentity.compatible(null, "order_db"));
        assertTrue(DatabaseIdentity.compatible("order_db", ""));
        assertTrue(DatabaseIdentity.compatible("order_db", "ORDER_DB"));
        assertFalse(DatabaseIdentity.compatible("order_db", "billing_db"));
    }
}
