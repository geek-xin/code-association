package com.geek.association.report;

import com.geek.association.model.ChangeRecord;
import com.geek.association.model.ImpactResult;
import com.geek.association.model.Sym;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything the API returns for one analysis run. */
public class ImpactReport {

    public long analysisId;
    public String sourceName;
    public String sourceType;
    public String mode;
    public String baseRef;
    public String targetRef;
    public String baseCommit;
    public String headCommit;
    public String generatedAt;
    public String baseLabel;
    public String headLabel;
    public Integer pullRequest;
    public Summary summary = new Summary();
    public List<ChangedModule> changedModules = new ArrayList<>();
    public List<ModuleImpact> moduleImpacts = new ArrayList<>();
    public List<ChangeView> changes = new ArrayList<>();
    public List<ImpactView> impacts = new ArrayList<>();
    /**
     * How many impacts this run found, before the list was capped for storage.
     *
     * <p>Equal to {@link Summary#impactedSymbols}. Kept next to the list because a reader that only
     * sees {@code impacts} cannot otherwise tell "the run found 5,000" from "the run found 412,000
     * and this is the top of it" - and a review page silently showing a subset as if it were the
     * whole result is the kind of omission the report exists to avoid.</p>
     */
    public int impactsTotal;
    /** How many rows {@link #impacts} actually carries. */
    public int impactsShown;
    /** Found impacts absent from {@link #impacts} because of the cap; 0 when the list is complete. */
    public int impactsTruncated;
    /**
     * One sentence explaining the cap, or null when the list is complete.
     *
     * <p>The wording lives here rather than in the page: the same sentence has to make sense in the
     * JSON export, the Markdown report and the review screen, so there is one place to keep it
     * accurate instead of three.</p>
     */
    public String impactsNote = null;
    public List<ModuleEdge> moduleGraph = new ArrayList<>();
    public Map<String, String> diffs = new LinkedHashMap<>();
    public List<String> notes = new ArrayList<>();
    public List<BlindSpot> blindSpots = new ArrayList<>();
    /**
     * Files on this side that were too large to read, so they were not indexed.
     *
     * Kept as a first-class list rather than only a note: a reviewer looking at "no impacts" has to
     * be able to see that a file was skipped, otherwise the clean result is a lie of omission.
     */
    public List<com.geek.association.source.FileSource.SkippedFile> skippedFiles = new ArrayList<>();
    public Map<String, Object> ai = null;
    /** Cross-program impact: what the selected peer programs have to change because of this delta. */
    public List<com.geek.association.model.PeerImpact> peers = new ArrayList<>();
    /** Set when no peer was selected, so the UI can say why the section is empty. */
    public String peersNote = null;
    /**
     * Commits contained in the reviewed range, newest first.
     *
     * The file list says what changed; only the commits say what the reviewer is being asked to
     * approve. Empty for a working-tree run, which has no commits of its own yet.
     */
    public List<com.geek.association.model.CommitInfo> commits = new ArrayList<>();
    /** How many commits the range holds (the same as {@link #commits} unless it was truncated). */
    public int commitCount = 0;
    /** Why {@link #commits} is shorter than the range, or "" when it is complete. */
    public String rangeNote = "";
    /**
     * How many commits the user hand-picked, or 0 when the run named a range instead.
     *
     * The difference matters to the reader: a range of forty commits and a hand-picked three can
     * produce the same file list, and only this number says which question was asked.
     */
    public int selectedCommits = 0;

    public static class Summary {
        public int changedModules;
        public int changedSymbols;
        /** Programs selected for comparison. */
        public int comparedPrograms;
        /** Contract matches found across those programs. */
        public int matchedContracts;
        /** Programs that could not be indexed. */
        public int unavailablePrograms;
        public int impactedModules;
        public int impactedSymbols;
        public int highRisk;
        public int mediumRisk;
        public int lowRisk;
        public int apiChanges;
        public int sqlChanges;
        public int configChanges;
        public int messageChanges;
        public int buildChanges;
        public int unresolvedCalls;
        /** Commits in the reviewed range; 0 for a working-tree run. */
        public int commitCount;
        public int parsedFiles;
        public long indexMillis;
        /** Changed symbols that no statically resolvable code calls (e.g. framework entry points). */
        public int changedSymbolsWithoutCallers;
    }

    public static class ChangedModule {
        public String module;
        public String artifactId;
        public int changedFiles;
        public int changedSymbols;
        public List<String> files = new ArrayList<>();
    }

    public static class ModuleImpact {
        /** The other program this row belongs to. */
        public String peerSourceName;
        /** Module path inside that program. */
        public String module;
        /** Program-level display name: the peer program for cross-program rows. */
        public String program;
        public String artifactId;
        public boolean changed;
        public int impactedSymbols;
        public int highRisk;
        public int maxRisk;
        public String riskLevel;
        public int maxDepth;
        public List<String> impactTypes = new ArrayList<>();
        public List<String> reasons = new ArrayList<>();
    }

    public static class ModuleEdge {
        public String from;
        public String to;
        public String type;
        public String detail;
    }

    public static class ChangeView {
        public long id;
        public String changeType;
        public String symbolType;
        public String module;
        public String qualifiedName;
        public String signature;
        public String file;
        public int startLine;
        public int endLine;
        public String oldValue;
        public String newValue;
        public String summary;
        public double riskSeed;
        public List<String> tags = new ArrayList<>();
        public Map<String, Object> extra = new LinkedHashMap<>();
        public String diffHunk;
    }

    public static class ImpactView {
        public long id;
        public long changeId;
        public long peerSourceId;
        public String peerSourceName;
        public String peerModule;
        public String changeType;
        public String changedSymbol;
        public String changedModule;
        public String changedFile;
        public int changedLine;
        public String targetModule;
        public String targetSymbol;
        public String targetSignature;
        public String targetFile;
        public int targetLine;
        public String impactType;
        public String riskLevel;
        public double riskScore;
        public String confidence;
        public double confidenceScore;
        public int depth;
        public String reason;
        public List<ImpactResult.PathStep> path = new ArrayList<>();
        public List<ImpactResult.Evidence> evidence = new ArrayList<>();
        public List<String> suggestedTests = new ArrayList<>();
        public String verificationStatus;
        public Map<String, Double> riskFactors = new LinkedHashMap<>();
    }

    public static class BlindSpot {
        /** UNRESOLVED_CALL | COLUMN_BLIND_SPOT - the two need different follow-up. */
        public String kind = "UNRESOLVED_CALL";
        public String module;
        public String file;
        public int line;
        public String expression;
        public String reason;
    }

    public static void addNote(List<String> notes, Set<String> seen, String note) {
        if (seen.add(note)) {
            notes.add(note);
        }
    }
}
