package com.geek.association.source;

/** Base and head snapshots of one analysis run. */
public class RevisionPair {

    public FileSource base;
    public FileSource head;
    public String baseCommit = "";
    public String headCommit = "";
    public String baseRef = "";
    public String headRef = "";
    public String mode = "";
    public String repoPath = "";
    public String note = "";
    public Integer pullRequest;

    public boolean isGit() {
        return !repoPath.isBlank();
    }
}
