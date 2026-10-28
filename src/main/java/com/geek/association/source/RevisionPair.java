package com.geek.association.source;

import com.geek.association.model.CommitInfo;

import java.util.ArrayList;
import java.util.List;

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
    /**
     * Commits contained in the reviewed range, newest first.
     *
     * A diff alone says what changed but not what the reviewer is being asked to approve: two
     * commits and forty commits produce the same file list, and only the commits explain why. Empty
     * for a working-tree comparison, which has no commits of its own yet.
     */
    public List<CommitInfo> commits = new ArrayList<>();
    /** Why {@link #commits} is not the complete range, or "" when it is. */
    public String rangeNote = "";
    /**
     * How many commits the user hand-picked, or 0 for a run that named a range instead.
     *
     * Carried separately from {@link #commits} because that list is capped for the report while
     * this number is the actual size of the question being answered - and the UI has to be able to
     * say "只分析了选中的 3 个提交" without re-deriving it from a truncated list.
     */
    public int selectedCommits = 0;

    public boolean isGit() {
        return !repoPath.isBlank();
    }

    public int commitCount() {
        return commits == null ? 0 : commits.size();
    }
}
