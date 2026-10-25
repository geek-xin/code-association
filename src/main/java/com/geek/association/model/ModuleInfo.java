package com.geek.association.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A Maven module discovered from pom.xml files. */
public class ModuleInfo {

    public String path;
    public String groupId;
    public String artifactId;
    public String version;
    public String parentArtifact;
    public boolean aggregator;
    public List<String> declaredModules = new ArrayList<>();
    /** artifactId -> groupId of Maven dependencies declared in this pom. */
    public List<String> dependencyArtifacts = new ArrayList<>();
    public Set<String> sourceRoots = new LinkedHashSet<>();

    public String displayName() {
        if (artifactId != null && !artifactId.isBlank()) {
            return artifactId;
        }
        return path == null || path.isBlank() ? "(root)" : path;
    }

    public boolean isTestSource(String filePath) {
        return filePath != null && (filePath.contains("/src/test/") || filePath.startsWith("src/test/"));
    }
}
