package com.geek.association.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One impacted (module, symbol) pair caused by one change record. */
public class ImpactResult {

    public long id;
    public long changeRecordId;
    /** The other program this impact belongs to; null for an analysis-local impact. */
    public long peerSourceId;
    public String peerSourceName;
    public String targetModule;
    /** Maven module inside the peer program; targetModule is the program name for peer impacts. */
    public String peerModule;
    public String targetSymbolKey;
    public String targetQualifiedName;
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
    public List<PathStep> path = new ArrayList<>();
    public List<Evidence> evidence = new ArrayList<>();
    public List<String> suggestedTests = new ArrayList<>();
    public String verificationStatus = "PENDING";
    /** How strongly the first hop consumes the changed value (1.0 = branch condition, 0.2 = log only). */
    public double propagationWeight = 1.0;
    public Map<String, Double> riskFactors = new LinkedHashMap<>();

    public static class PathStep {
        public String symbol;
        public String module;
        public String file;
        public int line;
        public String relation;
        public String usage;
        public double confidence;
        public String note;

        public PathStep() {
        }

        public PathStep(String symbol, String module, String file, int line) {
            this.symbol = symbol;
            this.module = module;
            this.file = file;
            this.line = line;
        }
    }

    public static class Evidence {
        public String type;
        public String source;
        public String target;
        public String filePath;
        public int line;
        public String detail;

        public Evidence() {
        }

        public Evidence(String type, String source, String target, String filePath, int line, String detail) {
            this.type = type;
            this.source = source;
            this.target = target;
            this.filePath = filePath;
            this.line = line;
            this.detail = detail;
        }
    }
}
