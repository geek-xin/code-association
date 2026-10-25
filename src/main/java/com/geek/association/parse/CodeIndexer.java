package com.geek.association.parse;

import com.geek.association.model.CodeIndex;
import com.geek.association.model.Edge;
import com.geek.association.model.Sym;
import com.geek.association.model.TypeInfo;
import com.geek.association.parse.messaging.MessagingAnalyzer;
import com.geek.association.source.FileSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Runs the whole parsing pipeline for one revision and returns the finished code index. */
public class CodeIndexer {

    private final int maxFiles;

    public CodeIndexer(int maxFiles) {
        this.maxFiles = maxFiles;
    }

    public MavenParser maven = new MavenParser();

    public CodeIndex index(FileSource source, String revisionLabel, String revisionId) {
        return index(source, revisionLabel, revisionId, null);
    }

    /**
     * Indexes one revision and reports files as they are parsed. The callback is intentionally
     * coarse (file path only): progress must not depend on parser internals or fake timers.
     */
    public CodeIndex index(FileSource source, String revisionLabel, String revisionId,
                           Consumer<String> onFile) {
        long start = System.currentTimeMillis();
        maven = new MavenParser();
        maven.parse(source);
        CodeIndex index = new CodeIndex();
        index.revisionLabel = revisionLabel;
        index.revisionId = revisionId;
        index.moduleDependencies = new ArrayList<>(maven.moduleDependencies);
        new JavaIndexer(source, maven, index, maxFiles, onFile).build();
        new SqlAnalyzer(source, maven, index).analyze();
        new ApiAnalyzer(source, index, maven).analyze();
        new ConfigAnalyzer(source, index, maven).analyze();
        new MessagingAnalyzer().analyze(source, index);
        new TestLinker(source, index, maven).analyze();
        index.buildMillis = System.currentTimeMillis() - start;
        return index;
    }
}
