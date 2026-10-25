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
