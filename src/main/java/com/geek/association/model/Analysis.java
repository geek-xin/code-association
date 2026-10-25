package com.geek.association.model;

import java.util.ArrayList;
import java.util.List;

public class Analysis {

    public long id;
    public long sourceId;
    public String sourceName;
    public String sourceType;
    public String mode;
    public String baseRef;
    public String targetRef;
    public String baseCommit;
    public String headCommit;
    public String status;
    public String message;
    public String createdAt;
    public String finishedAt;
    public long durationMs;
    public String summaryJson;
    public String reportJson;
    /** Machine-readable current stage: PREPARE, DIFF, INDEX_HEAD, INDEX_BASE, DETECT, IMPACT, PEERS, REPORT. */
    public String progressStage;
    public int progressPercent;
    public String progressLabel;
    public String progressDetail;
    public List<String> progressCompleted = new ArrayList<>();
    /** Stages that were skipped, e.g. PEERS when no comparison was selected. */
    public List<String> progressSkipped = new ArrayList<>();

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
}
