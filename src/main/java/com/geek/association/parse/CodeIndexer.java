package com.geek.association.parse;

import com.geek.association.model.CodeIndex;
import com.geek.association.model.Edge;
import com.geek.association.model.Sym;
import com.geek.association.model.TypeInfo;
import com.geek.association.parse.messaging.MessagingAnalyzer;
import com.geek.association.source.FileSource;
import com.geek.association.util.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(CodeIndexer.class);

    private final int maxFiles;

    public CodeIndexer(int maxFiles) {
        this.maxFiles = maxFiles;
    }

    public MavenParser maven = new MavenParser();

    public CodeIndex index(FileSource source, String revisionLabel, String revisionId) {
        return index(source, revisionLabel, revisionId, (FileProgress) null);
    }

    /** Path-only variant, kept so callers that do not care about counts stay simple. */
    public CodeIndex index(FileSource source, String revisionLabel, String revisionId,
                           Consumer<String> onFile) {
        FileProgress progress = onFile == null ? null : (path, done, total) -> onFile.accept(path);
        return index(source, revisionLabel, revisionId, progress);
    }

    /**
     * Indexes one revision and reports files as they are parsed. The callback is intentionally
     * coarse (file path only): progress must not depend on parser internals or fake timers.
     */
    /**
     * Indexes one revision, reporting per-file progress. Prefer this over the path-only overload:
     * only the total lets a caller turn "parsing file N" into a percentage that actually moves.
     */
    public CodeIndex index(FileSource source, String revisionLabel, String revisionId,
                           FileProgress onFile) {
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
        ConfigAnalyzer config = new ConfigAnalyzer(source, index, maven);
        config.analyze();
        index.databaseName = DatabaseIdentity.databaseName(config.keyValues);
        index.defaultSchema = DatabaseIdentity.defaultSchema(config.keyValues);
        new MessagingAnalyzer().analyze(source, index);
        new TestLinker(source, index, maven).analyze();
        index.buildMillis = System.currentTimeMillis() - start;
        // 一次 index 会跑在 head、base 以及每个对比程序上，所以这行是 DEBUG：排查"某个程序到底
        // 解析进来多少"时它是唯一的计数来源，但 INFO 会淹没分析本身的里程碑。
        log.debug(LogEvents.of("index.done",
                "files", index.parsedFiles,
                "symbols", index.symbols.size(),
                "modules", index.modules.size(),
                "unresolved", index.unresolved.size(),
                "durationMs", index.buildMillis));
        return index;
    }
}
