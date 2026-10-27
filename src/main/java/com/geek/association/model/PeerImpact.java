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
