package com.geek.association.model;

import java.time.Instant;

public class RepoSource {

    public long id;
    public String name;
    public String type;
    public String location;
    public String defaultRef;
    public String credentialEnv;
    public String createdAt;
    public String lastSyncedAt;
    public String localPath;
    public String status;
    public String message;

    public static String now() {
        return Instant.now().toString();
    }
}
