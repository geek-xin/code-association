package com.geek.association.model;

/** A directed relation of the code knowledge graph. Source calls / references / uses target. */
public class Edge {

    public String from;
    public String to;
    public String relation;
    public double confidence = 1.0;
    public String evidence = "STATIC";
    public String filePath;
    public int line;
    public String detail;
    /** How the callee's return value is consumed: CONDITION, ASSIGNMENT, ARGUMENT, RETURN, IGNORED, LOG_ONLY, NONE. */
    public String usage = "NONE";
    public boolean returnValueUsed;
    public String assignedVar;
    /** For ASSIGNMENT usage: true when the assigned variable later appears in a branch condition. */
    public boolean flowsIntoCondition;

    @Override
    public String toString() {
        return from + " -" + relation + "-> " + to;
    }
}
