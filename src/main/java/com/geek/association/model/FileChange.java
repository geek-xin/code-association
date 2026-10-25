package com.geek.association.model;

/** A file level change between two revisions. */
public class FileChange {

    public String status;
    public String path;
    public String oldPath;
    public String oldHash;
    public String newHash;
    public String oldContent;
    public String newContent;

    public static FileChange of(String status, String path) {
        FileChange fc = new FileChange();
        fc.status = status;
        fc.path = path;
        fc.oldPath = path;
        return fc;
    }
}
