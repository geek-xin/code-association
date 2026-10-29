package com.geek.association.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One review comment produced by the model and then placed by deterministic code.
 *
 * The model never writes a line number here. It returns {@link #existingCode}, the verbatim snippet
 * it is talking about, and {@link CommentLocator} turns that snippet into {@link #startLine} /
 * {@link #endLine}. A comment that cannot be placed is dropped before it reaches the report - a
 * comment at the wrong line costs more than a missing one.
 */
public class ReviewComment {

    /** Model-assigned id, stable within one generation; used to correlate reflection results. */
    public String id;
    /** BLOCKER / CRITICAL / MAJOR / MINOR / INFO. */
    public String severity;
    /** CORRECTNESS / SECURITY / PERFORMANCE / RELIABILITY / MAINTAINABILITY / IMPACT. */
    public String category;
    public String title;
    public String detail;
    public String suggestion;
    /** Path relative to the repository root, as it appears in the diff. */
    public String file;
    /** The snippet the model quoted; the input of deterministic localisation. */
    public String existingCode;
    public int startLine;
    public int endLine;
    /** NEW for the new side of the diff, OLD for deleted lines. */
    public String side = "NEW";
    /** HUNK / FILE / RELOCATED / NONE. */
    public String anchor = "NONE";
    /** Fact ids (C1 / I2 / B3) the comment claims to be based on. */
    public List<String> evidence = new ArrayList<>();
    public double confidence = 0.5;
    /** KEPT for the report; DROPPED is only used inside the pipeline and never serialised. */
    public String status = "KEPT";
    /** Why the deterministic filter or the reflection pass rejected it; null for kept comments. */
    public String filterNote;

    /** The wire shape stored under {@code report.ai.comments}. */
    public Map<String, Object> view() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("severity", severity);
        out.put("category", category);
        out.put("title", title);
        out.put("detail", detail);
        out.put("suggestion", suggestion);
        out.put("file", file);
        out.put("startLine", startLine);
        out.put("endLine", endLine);
        out.put("side", side);
        out.put("anchor", anchor);
        out.put("existingCode", existingCode);
        out.put("evidence", evidence);
        out.put("confidence", confidence);
        return out;
    }
}
