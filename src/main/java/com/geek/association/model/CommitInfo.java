package com.geek.association.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One commit of a reviewed range, as the UI and the report show it.
 *
 * Deliberately free of JGit types: this object is serialised into the report JSON and into the
 * commit picker's response, so it must not drag a repository handle (or a {@code RevCommit}'s lazy
 * tree loading) into a DTO that outlives the run.
 */
public class CommitInfo {

    /** Full 40 character id, which is what a re-run can be pinned to. */
    public String sha;
    /** First 8 characters, for lists that must stay readable. */
    public String shortSha;
    /** First line of the message: the part a reviewer actually reads. */
    public String subject;
    public String author;
    /** ISO-8601 instant of the author date, UTC. */
    public String date;
    /**
     * Parent ids, short form.
     *
     * Carried so the picker can tell a merge commit from an ordinary one without a second request:
     * "this range contains a merge" changes what the diff means.
     */
    public List<String> parents = new ArrayList<>();

    public static CommitInfo of(String sha, String subject, String author, String date,
                                List<String> parents) {
        CommitInfo info = new CommitInfo();
        info.sha = sha;
        info.shortSha = sha == null || sha.length() <= 8 ? sha : sha.substring(0, 8);
        info.subject = subject;
        info.author = author;
        info.date = date;
        info.parents = parents == null ? new ArrayList<>() : new ArrayList<>(parents);
        return info;
    }
}
