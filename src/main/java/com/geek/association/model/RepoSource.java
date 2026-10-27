package com.geek.association.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.geek.association.transfer.SourceTagsDeserializer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
    /**
     * User-assigned tags for categorizing sources (never null).
     *
     * Bound through a deserializer so a request may send either a JSON array or a comma-separated
     * string; see {@link SourceTagsDeserializer}.
     */
    @JsonDeserialize(using = SourceTagsDeserializer.class)
    public List<String> tags = new ArrayList<>();

    public static String now() {
        return Instant.now().toString();
    }
}
