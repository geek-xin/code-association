package com.geek.association.model;

import java.util.ArrayList;
import java.util.List;

/**
 * How a change in one program reaches another program.
 *
 * Two repositories cannot share a call graph: they are compiled separately and only meet at
 * contracts - HTTP endpoints, database tables, configuration keys, published Maven artifacts.
 * Each entry here is one such meeting point plus everything that became reachable behind it.
 */
public class PeerImpact {

    public long peerSourceId;
    public String peerSourceName;
    public String peerType;
    public String peerLocation;
    /** Set when the peer could not be indexed; the reason is shown instead of results. */
    public String note;
    public List<ContractMatch> matches = new ArrayList<>();
    public List<ImpactResult> impacts = new ArrayList<>();
    public int impactedSymbols;
    public int impactedModules;
    public int highRisk;
    public int maxRisk;
    public String riskLevel = "INFO";
    public int indexedFiles;
    public long indexMillis;
    /**
     * Files in this program that were too large to read, so they are absent from the index.
     *
     * A peer that "has no impact" may simply have had its relevant file skipped; naming the files
     * keeps that reading honest.
     */
    public List<com.geek.association.source.FileSource.SkippedFile> skippedFiles = new ArrayList<>();

    /**
     * Statements in this program that touch a table this side changed but name no column
     * ({@code SELECT *}, dynamic SQL).
     *
     * Database contracts are matched per column, so these statements cannot be judged: they are
     * counted and shown instead of being reported as "not affected". Reading a gap as a clean
     * result is the mistake this field exists to prevent.
     */
    public int unparsedColumnStatements;
    /** One line per statement behind {@link #unparsedColumnStatements}, capped for display. */
    public List<String> unparsedColumnFiles = new ArrayList<>();

    /**
     * Database candidates excluded because the two programs declare different databases.
     *
     * A table name is not a contract when it lives in another database. These are counted instead
     * of silently disappearing, so "no impact" never hides a suppressed candidate.
     */
    public int dbMismatchSuppressed;
    /** A few examples behind {@link #dbMismatchSuppressed}, capped for display. */
    public List<String> dbMismatchExamples = new ArrayList<>();
    /** Schema changes excluded because the type change is compatible (widening, nullable relaxed). */
    public int dbCompatibilitySuppressed;
    /** A few examples behind {@link #dbCompatibilitySuppressed}, capped for display. */
    public List<String> dbCompatibilityExamples = new ArrayList<>();
    /**
     * Schema changes no consumer can observe: adding a column, creating a table, deleting a
     * migration script.
     *
     * None of these can break the other program - named-column code does not see a new column, a
     * new table has no existing consumer, and deleting a script does not undo a migration that
     * already ran. They are still counted, because a change that simply disappears from the
     * report reads as "never analysed". Counted once per change, not per usage point: the number
     * describes the change list, which every peer is measured against, so the report aggregates
     * it with {@code max()} rather than a sum.
     */
    public int dbNonObservableChanges;
    /** A few examples behind {@link #dbNonObservableChanges}, capped for display. */
    public List<String> dbNonObservableExamples = new ArrayList<>();
    /**
     * Schema changes that made a column required: {@code NOT NULL} and no default.
     *
     * An INSERT in the other program that omits such a column is rejected by the database (MySQL
     * strict mode, PostgreSQL, Oracle), so this is a real incompatibility and not merely a blind
     * spot. The matched INSERTs are reported as writer impacts; this counter says how many changes
     * were judged that way, so the report can state the consequence and ask for the dynamic
     * statements to be confirmed by hand. Counted once per change - every peer is measured against
     * the same change list - so the report aggregates it with {@code max()} rather than a sum.
     */
    public int dbRequiredColumnChanges;
    /** A few examples behind {@link #dbRequiredColumnChanges}, capped for display. */
    public List<String> dbRequiredColumnExamples = new ArrayList<>();
    /** Matches kept although one side's database identity is unknown; confidence is lowered. */
    public int dbIdentityUnknownMatches;

    /**
     * One shared contract: the changed thing on this side, and the code on the peer side that
     * consumes it.
     */
    public static class ContractMatch {
        /** API | DB_TABLE | DB_COLUMN | CONFIG | MAVEN_ARTIFACT */
        public String kind;
        /** The shared identity, e.g. {@code GET /api/users/{id}} or {@code orders.status}. */
        public String contract;
        /**
         * The peer-side node the contract lives on (the endpoint, table, column or config key).
         * Propagation starts here, so the first hop is the peer code that consumes it.
         */
        public String contractSymbolKey;
        /** The changed symbol on this side, for the report trail. */
        public String changedSymbol;
        /** The source change that introduced this contract delta. */
        public long changeRecordId;
        /** The peer symbol that consumes the contract. */
        public String peerSymbol;
        /** Stable key of the peer symbol, used as the propagation seed. */
        public String peerSymbolKey;
        public String peerModule;
        public String peerFile;
        public int peerLine;
        public double confidence;
        /** How the peer uses it: caller, reader, writer, config consumer, dependency. */
        public String usage;
    }
}
