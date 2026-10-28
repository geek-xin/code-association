package com.geek.association.parse;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The identity of the database a program talks to, read from its configuration.
 *
 * Cross-program database impact is only possible when both programs can see the same database
 * object. Table names alone are not an identity: {@code orders} exists in almost every schema, and
 * matching on it used to pull unrelated programs into the review queue. A JDBC URL (or an explicit
 * schema qualifier in SQL) is the strongest evidence available without connecting to the database.
 *
 * The rules are deliberately conservative:
 * <ul>
 *   <li>no declaration, or several different declarations, means <em>unknown</em> - never a guess;</li>
 *   <li>embedded databases (H2 mem, HSQLDB, Derby, SQLite) are process-local, so they are unknown
 *       too: two programs naming their in-memory database {@code testdb} share nothing;</li>
 *   <li>only the database name is compared, not the host: the same schema is commonly reached
 *       through different hosts in different environments.</li>
 * </ul>
 */
public final class DatabaseIdentity {

    private static final Pattern JDBC = Pattern.compile("^jdbc:([a-zA-Z0-9]+):(.*)$", Pattern.DOTALL);
    private static final Pattern R2DBC = Pattern.compile("^r2dbc:([a-zA-Z0-9]+):(.*)$", Pattern.DOTALL);
    private static final Pattern SQLSERVER_DATABASE = Pattern.compile(
            "(?:^|;)\\s*databaseName\\s*=\\s*([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORACLE_SID = Pattern.compile("@[^:]+:[0-9]+:([^/?]+)");
    private static final Pattern ORACLE_SERVICE = Pattern.compile("@//[^/]+/([^?;]+)");

    private static final Set<String> EMBEDDED = Set.of("h2", "hsqldb", "derby", "sqlite", "sqlite3");

    private DatabaseIdentity() {
    }

    /** The single database this program declares, or null when absent/ambiguous/embedded. */
    public static String databaseName(Map<String, String> config) {
        if (config == null || config.isEmpty()) {
            return null;
        }
        Set<String> names = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : config.entrySet()) {
            if (!isDatabaseUrlKey(entry.getKey())) {
                continue;
            }
            String name = databaseOf(entry.getValue());
            if (name != null && !name.isBlank()) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return names.size() == 1 ? names.iterator().next() : null;
    }

    /** The default schema/catalog this program declares, or null when absent/ambiguous. */
    public static String defaultSchema(Map<String, String> config) {
        if (config == null || config.isEmpty()) {
            return null;
        }
        Set<String> names = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : config.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (value.isEmpty()) {
                continue;
            }
            boolean schemaKey = key.endsWith(".schema") || key.endsWith(".default-schema")
                    || key.endsWith(".default_schema") || key.endsWith(".default-schema-name")
                    || key.endsWith(".default_catalog") || key.endsWith(".default-catalog");
            // spring.datasource.schema / data are SQL init script locations, not a schema name.
            boolean initScript = key.equals("spring.datasource.schema") || key.equals("spring.datasource.data")
                    || key.contains("schema-locations") || key.contains("data-locations")
                    || value.endsWith(".sql") || value.contains("/");
            if (!schemaKey || initScript || value.startsWith("classpath:") || value.contains("${")) {
                continue;
            }
            names.add(value.toLowerCase(Locale.ROOT));
        }
        return names.size() == 1 ? names.iterator().next() : null;
    }

    /**
     * The database part of a JDBC / R2DBC URL, or null when it cannot be determined.
     *
     * Supported shapes: MySQL/PostgreSQL/MariaDB/Kingbase/DM style {@code //host:port/db},
     * SQL Server {@code ;databaseName=db}, Oracle {@code @host:port:SID} and
     * {@code @//host/service}, and generic URLs whose last path segment is the database.
     */
    public static String databaseOf(String rawUrl) {
        if (rawUrl == null) {
            return null;
        }
        String url = rawUrl.trim();
        if (url.length() >= 2 && ((url.startsWith("\"") && url.endsWith("\""))
                || (url.startsWith("'") && url.endsWith("'")))) {
            url = url.substring(1, url.length() - 1).trim();
        }
        Matcher jdbc = JDBC.matcher(url);
        Matcher r2dbc = R2DBC.matcher(url);
        String vendor;
        String rest;
        if (jdbc.matches()) {
            vendor = jdbc.group(1).toLowerCase(Locale.ROOT);
            rest = jdbc.group(2);
        } else if (r2dbc.matches()) {
            vendor = r2dbc.group(1).toLowerCase(Locale.ROOT);
            rest = r2dbc.group(2);
        } else {
            return null;
        }
        if (EMBEDDED.contains(vendor)) {
            return null;
        }
        if (vendor.contains("sqlserver")) {
            Matcher m = SQLSERVER_DATABASE.matcher(rest);
            return m.find() ? clean(m.group(1)) : null;
        }
        if (vendor.contains("oracle")) {
            Matcher service = ORACLE_SERVICE.matcher(rest);
            if (service.find()) {
                return clean(service.group(1));
            }
            Matcher sid = ORACLE_SID.matcher(rest);
            if (sid.find()) {
                return clean(sid.group(1));
            }
            return null;
        }
        // Strip query string, then take the last path segment.
        int query = rest.indexOf('?');
        String path = query >= 0 ? rest.substring(0, query) : rest;
        path = path.replaceFirst("^//[^/]*", ""); // remove authority
        path = path.replaceFirst("^/+", "");
        path = path.replaceFirst("/+$", "");
        if (path.isEmpty()) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int colon = name.lastIndexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        int semicolon = name.indexOf(';');
        if (semicolon >= 0) {
            name = name.substring(0, semicolon);
        }
        return clean(name);
    }

    /**
     * Whether two known identities can denote the same database.
     *
     * Unknown on either side is not evidence of a mismatch: it stays a match, with lower confidence
     * applied by the caller. Only two known and different names suppress the match.
     */
    public static boolean compatible(String left, String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return true;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    /** Both sides known? Used to decide whether the match deserves full confidence. */
    public static boolean known(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isDatabaseUrlKey(String rawKey) {
        if (rawKey == null) {
            return false;
        }
        String key = rawKey.toLowerCase(Locale.ROOT);
        if (!(key.endsWith(".url") || key.endsWith(".jdbc-url") || key.endsWith(".jdbc_url"))) {
            return false;
        }
        return key.contains("datasource") || key.startsWith("jdbc.") || key.contains("jdbc.url")
                || key.contains("r2dbc") || key.contains("connection.url");
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String out = value.trim();
        if (out.length() >= 2 && ((out.startsWith("\"") && out.endsWith("\""))
                || (out.startsWith("'") && out.endsWith("'")))) {
            out = out.substring(1, out.length() - 1);
        }
        // An unresolved placeholder is not a database name. Treating "${DB_NAME}" as an
        // identity would make two programs look like different databases and suppress a real
        // match; unknown is the honest answer.
        if (out.contains("${") || out.contains("#{") || out.contains("{{")) {
            return null;
        }
        return out.isBlank() ? null : out;
    }
}
