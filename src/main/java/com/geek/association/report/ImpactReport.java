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
    public List<ModuleEdge> moduleGraph = new ArrayList<>();
    public Map<String, String> diffs = new LinkedHashMap<>();
    public List<String> notes = new ArrayList<>();
    public List<BlindSpot> blindSpots = new ArrayList<>();
    public Map<String, Object> ai = null;
    /** Cross-program impact: what the selected peer programs have to change because of this delta. */
    public List<com.geek.association.model.PeerImpact> peers = new ArrayList<>();
    /** Set when no peer was selected, so the UI can say why the section is empty. */
    public String peersNote = null;

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
