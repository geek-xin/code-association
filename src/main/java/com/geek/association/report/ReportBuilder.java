package com.geek.association.report;

import com.geek.association.model.ChangeRecord;
import com.geek.association.model.CodeIndex;
import com.geek.association.model.Edge;
import com.geek.association.model.ImpactResult;
import com.geek.association.model.ModuleInfo;
import com.geek.association.model.Sym;
import com.geek.association.source.RevisionPair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Assembles the flat analysis results into the report shape the UI renders. */
public class ReportBuilder {

    /**
     * Builds one report.
     *
     * When peers are supplied, the summary and module sections describe only the impact on those
     * other programs. Local {@code impacts} never contribute to counts, risk levels or the module
     * map when any peer result is present.
     */
    public ImpactReport build(RevisionPair revisions, CodeIndex headIndex, CodeIndex baseIndex,
                              List<ChangeRecord> changes, List<ImpactResult> impacts,
                              Map<String, String> diffs,
                              List<com.geek.association.model.PeerImpact> peers) {
        ImpactReport report = new ImpactReport();
        report.sourceName = revisions.note;
        report.mode = revisions.mode;
        report.pullRequest = revisions.pullRequest;
        report.baseRef = revisions.baseRef;
        report.targetRef = revisions.headRef;
        report.baseCommit = revisions.baseCommit;
        report.headCommit = revisions.headCommit;
        report.baseLabel = revisions.base == null ? "" : revisions.base.label();
        report.headLabel = revisions.head == null ? "" : revisions.head.label();
        report.commits = revisions.commits == null ? new ArrayList<>() : revisions.commits;
        report.commitCount = report.commits.size();
        report.rangeNote = revisions.rangeNote == null ? "" : revisions.rangeNote;
        report.selectedCommits = revisions.selectedCommits;
        report.summary.commitCount = report.commitCount;
        report.generatedAt = java.time.Instant.now().toString();
        report.diffs = diffs;
        report.summary.parsedFiles = headIndex.parsedFiles;
        report.summary.indexMillis = headIndex.buildMillis;
        report.summary.unresolvedCalls = headIndex.unresolved.size();

        Set<String> changedModules = new LinkedHashSet<>();
        Map<String, ImpactReport.ChangedModule> moduleMap = new LinkedHashMap<>();
        for (ChangeRecord change : changes) {
            String module = change.modulePath == null ? "" : change.modulePath;
            changedModules.add(module);
            ImpactReport.ChangedModule cm = moduleMap.computeIfAbsent(module, k -> {
                ImpactReport.ChangedModule m = new ImpactReport.ChangedModule();
                m.module = k.isBlank() ? "(root)" : k;
                ModuleInfo info = headIndex.modules.get(k);
                m.artifactId = info == null ? "" : info.artifactId;
                return m;
            });
            cm.changedSymbols++;
            if (change.filePath != null && !cm.files.contains(change.filePath)) {
                cm.files.add(change.filePath);
            }
            if ("API_CONTRACT_CHANGED".equals(change.changeType) || "API_REMOVED".equals(change.changeType)
                    || "API_ADDED".equals(change.changeType)) {
                report.summary.apiChanges++;
            }
            if (change.changeType.startsWith("SQL_") || change.changeType.startsWith("DDL_")) {
                report.summary.sqlChanges++;
            }
            if (change.changeType.startsWith("CONFIG_")) {
                report.summary.configChanges++;
            }
            if (change.changeType.startsWith("TOPIC_") || change.changeType.startsWith("MESSAGE_")) {
                report.summary.messageChanges++;
            }
            if ("BUILD_CHANGED".equals(change.changeType)) {
                report.summary.buildChanges++;
            }
        }
        report.summary.changedSymbols = changes.size();
        report.summary.changedModules = changedModules.size();
        report.changedModules = new ArrayList<>(moduleMap.values());
        report.changedModules.sort(Comparator.comparing(m -> m.module));

        report.changes = changes.stream().map(this::toView).toList();

        // Scope is decided by intent, never by whether the peer list happened to resolve: an empty
        // result must render as "nothing found", not silently fall back to this program's own
        // modules, blind spots and notes. The caller passes peerScoped explicitly for that reason.
        boolean peerScoped = true;
        List<ImpactResult> peerImpacts = new ArrayList<>();
        for (com.geek.association.model.PeerImpact peer : peers) {
            if (peer.note != null) {
                report.summary.unavailablePrograms++;
            }
            report.summary.matchedContracts += peer.matches.size();
            peerImpacts.addAll(peer.impacts);
        }
        report.summary.comparedPrograms = peers.size();
        List<ImpactResult> scoped = peerImpacts;

        Map<String, List<ImpactResult>> byModule = new LinkedHashMap<>();
        Map<String, String> peerByModule = new LinkedHashMap<>();
        Map<String, String> moduleByKey = new LinkedHashMap<>();
        for (ImpactResult impact : scoped) {
            String peerName = impact.peerSourceName;
            String moduleName = peerScoped
                    ? (impact.peerModule == null ? "(root)" : impact.peerModule)
                    : impact.targetModule;
            String moduleKey = peerScoped
                    ? (peerName == null ? "其他程序" : peerName) + " / " + moduleName
                    : moduleName;
            byModule.computeIfAbsent(moduleKey, k -> new ArrayList<>()).add(impact);
            if (peerName != null) {
                peerByModule.put(moduleKey, peerName);
                moduleByKey.put(moduleKey, moduleName);
            }
            switch (impact.riskLevel) {
                case "CRITICAL", "HIGH" -> report.summary.highRisk++;
                case "MEDIUM" -> report.summary.mediumRisk++;
                default -> report.summary.lowRisk++;
            }
        }
        report.summary.impactedSymbols = scoped.size();
        report.summary.impactedModules = byModule.keySet().size();

        Set<Long> changesWithImpact = new LinkedHashSet<>();
        for (ImpactResult impact : scoped) {
            changesWithImpact.add(impact.changeRecordId);
        }


        for (Map.Entry<String, List<ImpactResult>> e : byModule.entrySet()) {
            ImpactReport.ModuleImpact mi = new ImpactReport.ModuleImpact();
            mi.peerSourceName = peerByModule.get(e.getKey());
            if (mi.peerSourceName != null) {
                mi.program = mi.peerSourceName;
                mi.module = moduleByKey.getOrDefault(e.getKey(), e.getKey());
                mi.artifactId = "";
                mi.changed = false;
            } else {
                mi.program = e.getKey();
                mi.module = e.getKey();
                ModuleInfo info = headIndex.modules.get(e.getKey());
                mi.artifactId = info == null ? "" : info.artifactId;
                mi.changed = changedModules.contains(e.getKey());
            }
            mi.impactedSymbols = e.getValue().size();
            mi.maxRisk = (int) Math.round(e.getValue().stream().mapToDouble(i -> i.riskScore).max().orElse(0));
            mi.riskLevel = com.geek.association.impact.RiskScorer.level(mi.maxRisk);
            mi.maxDepth = e.getValue().stream().mapToInt(i -> i.depth).max().orElse(1);
            mi.highRisk = (int) e.getValue().stream().filter(i -> i.riskScore >= 60).count();
            Set<String> types = new LinkedHashSet<>();
            Set<String> reasons = new LinkedHashSet<>();
            for (ImpactResult i : e.getValue()) {
                types.add(i.impactType);
                if (reasons.size() < 4) {
                    reasons.add(i.reason);
                }
            }
            mi.impactTypes = new ArrayList<>(types);
            mi.reasons = new ArrayList<>(reasons);
            report.moduleImpacts.add(mi);
        }
        report.moduleImpacts.sort(Comparator.comparingInt((ImpactReport.ModuleImpact m) -> -m.maxRisk));

        Map<Long, ChangeRecord> byId = new LinkedHashMap<>();
        for (ChangeRecord c : changes) {
            byId.put(c.id, c);
        }
        for (ImpactResult impact : scoped) {
            report.impacts.add(toView(impact, byId.get(impact.changeRecordId)));
        }

        Set<String> seenEdges = new LinkedHashSet<>();
        for (com.geek.association.model.PeerImpact peer : peers) {
            for (ImpactResult impact : peer.impacts) {
                String key = peer.peerSourceName + "|" + impact.targetQualifiedName;
                if (!seenEdges.add(key)) {
                    continue;
                }
                ImpactReport.ModuleEdge me = new ImpactReport.ModuleEdge();
                me.from = report.sourceName;
                me.to = peer.peerSourceName;
                me.type = "CROSS_PROGRAM";
                me.detail = impact.targetQualifiedName + " in "
                        + (impact.peerModule == null ? impact.targetModule : impact.peerModule);
                report.moduleGraph.add(me);
            }
        }
        // An empty peer list means "nothing was compared", which the UI must state explicitly
        // rather than leave looking like a clean run.
        report.peersNote = peers.isEmpty() ? "本次分析没有选择对比程序" : null;

        // Blind spots come from the peer indexes, never from this program's own unresolved calls.
        report.blindSpots = columnBlindSpots(peers);
        report.skippedFiles = skippedFiles(revisions);
        peerNotes(report, headIndex, peers);
        return report;
    }

    /**
     * Files this side left unread because of the per-file size limit.
     *
     * Both revisions are collected: a file that exists only in the base is a deletion nobody saw,
     * and one that exists only in the head is an addition nobody saw, so reporting just one side
     * would hide half the gap.
     */
    private List<com.geek.association.source.FileSource.SkippedFile> skippedFiles(RevisionPair revisions) {
        List<com.geek.association.source.FileSource.SkippedFile> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (com.geek.association.source.FileSource source : new com.geek.association.source.FileSource[]{
                revisions.base, revisions.head}) {
            if (source == null) {
                continue;
            }
            for (com.geek.association.source.FileSource.SkippedFile skipped : source.skippedFiles()) {
                // The same path in both revisions is one omission for the reader, and the head
                // size is the one that describes the state being analysed.
                if (seen.add(skipped.path())) {
                    out.add(skipped);
                }
            }
        }
        out.sort(java.util.Comparator.comparing(com.geek.association.source.FileSource.SkippedFile::path));
        return out;
    }

    /**
     * Peer statements that touch a changed table but name no column.
     *
     * They are blind spots, not clean results: {@code SELECT *} is judged by the runtime schema, so
     * the analyser can neither match nor exclude it. Listing them next to the unresolved calls is
     * what keeps "no impact found" from quietly meaning "no impact looked for".
     */
    private List<ImpactReport.BlindSpot> columnBlindSpots(
            List<com.geek.association.model.PeerImpact> peers) {
        List<ImpactReport.BlindSpot> out = new ArrayList<>();
        for (com.geek.association.model.PeerImpact peer : peers) {
            for (String location : peer.unparsedColumnFiles) {
                int colon = location.lastIndexOf(':');
                ImpactReport.BlindSpot b = new ImpactReport.BlindSpot();
                b.module = peer.peerSourceName;
                b.file = colon > 0 ? location.substring(0, colon) : location;
                try {
                    b.line = colon > 0 ? Integer.parseInt(location.substring(colon + 1)) : 0;
                } catch (NumberFormatException e) {
                    b.line = 0;
                }
                b.kind = "COLUMN_BLIND_SPOT";
                b.expression = "字段盲区";
                b.reason = "该语句碰到本次变更的表，但没有写明确切列名（SELECT * 或动态拼接），"
                        + "无法参与字段级匹配，需人工确认";
                out.add(b);
            }
        }
        return out;
    }

    private void peerNotes(ImpactReport report, CodeIndex ownHead,
                           List<com.geek.association.model.PeerImpact> peers) {
        Set<String> seen = new LinkedHashSet<>();
        ImpactReport.addNote(report.notes, seen,
                "本报告只统计对其他程序的影响；本程序内部的调用方、模块和测试覆盖不作为影响项。");
        if (report.summary.unavailablePrograms > 0) {
            ImpactReport.addNote(report.notes, seen,
                    report.summary.unavailablePrograms + " 个对比程序无法读取，已在对应条目中说明原因。");
        }
        if (!report.skippedFiles.isEmpty()) {
            ImpactReport.addNote(report.notes, seen,
                    "本次有 " + report.skippedFiles.size() + " 个文件超过单文件读取上限，未被索引；"
                            + "名单见报告末尾，涉及这些文件的改动与影响需人工确认。");
        }
        int blindSpots = peers.stream().mapToInt(p -> p.unparsedColumnStatements).sum();
        if (blindSpots > 0) {
            ImpactReport.addNote(report.notes, seen,
                    "对比程序里有 " + blindSpots + " 条 SQL 语句碰到本次变更的表，但没有写明确切列名"
                            + "（SELECT * 或动态拼接），无法参与字段级匹配；"
                            + "它们既不算命中也不算排除，请按「字段盲区」人工确认。");
        }
        int dbMismatch = peers.stream().mapToInt(p -> p.dbMismatchSuppressed).sum();
        if (dbMismatch > 0) {
            ImpactReport.addNote(report.notes, seen,
                    "有 " + dbMismatch + " 条数据库候选（按对方使用点计）因两程序声明的数据库不同而被排除；"
                            + "同名表不等于同一张表，若确认两者共享数据库，请核对数据源配置后人工复核。");
        }
        // Two aggregation rules, because the counters count different things. Every peer is
        // analysed against the same change list, so a per-change counter is repeated in every peer
        // result and describes one change N times: max() reads it back as the change count, while
        // sum() would multiply the change list by the number of compared programs. A usage point
        // belongs to exactly one peer, so those counters never overlap and must be summed - max()
        // there would report the busiest peer and hide the rest.
        int dbCompatible = peers.stream().mapToInt(p -> p.dbCompatibilitySuppressed).max().orElse(0);
        if (dbCompatible > 0) {
            ImpactReport.addNote(report.notes, seen,
                    "有 " + dbCompatible + " 条表结构候选属于兼容变更（字段加宽、可空性放宽等），"
                            + "未作为跨程序影响报告；原有取值与绑定仍然有效。");
        }
        int dbNonObservable = peers.stream().mapToInt(p -> p.dbNonObservableChanges).max().orElse(0);
        if (dbNonObservable > 0) {
            ImpactReport.addNote(report.notes, seen,
                    "有 " + dbNonObservable + " 条表结构变更（可空或带默认值的加列、建表、"
                            + "删除迁移脚本或移除 DDL 动作）对对比程序不可观察，未作为跨程序影响报告；"
                            + "这些变更不会让对方已有代码失效，但仍需随发布顺序执行，字段盲区处请人工确认。");
        }
        int dbRequired = peers.stream().mapToInt(p -> p.dbRequiredColumnChanges).max().orElse(0);
        if (dbRequired > 0) {
            ImpactReport.addNote(report.notes, seen,
                    "有 " + dbRequired + " 条表结构变更把字段改为必填（NOT NULL 且无默认值）："
                            + "对比程序中省略该字段的 INSERT 会被数据库拒绝，已按「写入方必填字段」命中；"
                            + "请确认对方所有插入语句都补齐该字段，动态 SQL 与 SELECT * 处仍属字段盲区，需人工确认。");
        }
        // An entity-field edit only produces a low-confidence mapping hint, never a column-level
        // impact. Saying so keeps "no impact" from hiding a match the reader may still want to
        // check - especially when both programs share the model class through a Maven dependency.
        int mappingHints = peers.stream().mapToInt(p -> p.mappingHintMatches).sum();
        if (mappingHints > 0) {
            ImpactReport.addNote(report.notes, seen,
                    "有 " + mappingHints + " 条低置信度映射提示：对比程序也映射了同一列，"
                            + "但本次变化是本程序删除/修改 Java 实体字段，数据库列并没有变化，"
                            + "因此未计入跨程序影响；若两边共用同一模型类（Maven 依赖），"
                            + "请按编译契约单独确认。");
        }
        int dbUnknown = peers.stream().mapToInt(p -> p.dbIdentityUnknownMatches).sum();
        if (dbUnknown > 0) {
            ImpactReport.addNote(report.notes, seen,
                    "有 " + dbUnknown + " 条数据库匹配因一侧未声明或无法确定数据源（或数据库名与 schema 名"
                            + "属于不同层级）而无法确认是否同库，已保留匹配但降低置信度；"
                            + "核对 JDBC URL 或显式 schema 可确认。");
        }
        int peerSkipped = peers.stream().mapToInt(p -> p.skippedFiles.size()).sum();
        if (peerSkipped > 0) {
            ImpactReport.addNote(report.notes, seen,
                    "对比程序共有 " + peerSkipped + " 个文件超过单文件读取上限而未被索引，"
                            + "对应条目中已列出文件名。");
        }
        if (!ownHead.unresolved.isEmpty()) {
            ImpactReport.addNote(report.notes, seen,
                    "本程序有 " + ownHead.unresolved.size() + " 个调用点无法静态解析；"
                            + "它们只影响本程序内部传播，不影响本次跨程序契约匹配。");
        }
        ImpactReport.addNote(report.notes, seen,
                "跨程序结论来自共享契约（HTTP、数据库、配置、Maven 依赖和消息）；"
                        + "未命中的契约不视为没有影响，请结合接口文档与运行时调用确认。");
    }

    private ImpactReport.ChangeView toView(ChangeRecord c) {
        ImpactReport.ChangeView v = new ImpactReport.ChangeView();
        v.id = c.id;
        v.changeType = c.changeType;
        v.symbolType = c.symbolType;
        v.module = c.modulePath == null || c.modulePath.isBlank() ? "(root)" : c.modulePath;
        v.qualifiedName = c.qualifiedName;
        v.signature = c.signature;
        v.file = c.filePath;
        v.startLine = c.startLine;
        v.endLine = c.endLine;
        v.oldValue = c.oldValue;
        v.newValue = c.newValue;
        v.summary = c.semanticSummary;
        v.riskSeed = c.riskSeed;
        v.tags = c.tags;
        v.extra = c.extra;
        return v;
    }

    private ImpactReport.ImpactView toView(ImpactResult i, ChangeRecord change) {
        ImpactReport.ImpactView v = new ImpactReport.ImpactView();
        v.id = i.id;
        v.changeId = i.changeRecordId;
        v.peerSourceId = i.peerSourceId;
        v.peerSourceName = i.peerSourceName;
        v.peerModule = i.peerModule;
        v.changeType = change == null ? "" : change.changeType;
        v.changedSymbol = change == null ? "" : change.qualifiedName + (change.signature == null ? "" : change.signature);
        v.changedModule = change == null || change.modulePath == null ? "" : change.modulePath;
        v.changedFile = change == null ? "" : change.filePath;
        v.changedLine = change == null ? 0 : change.startLine;
        v.targetModule = i.targetModule;
        v.targetSymbol = i.targetQualifiedName;
        v.targetSignature = i.targetSignature;
        v.targetFile = i.targetFile;
        v.targetLine = i.targetLine;
        v.impactType = i.impactType;
        v.riskLevel = i.riskLevel;
        v.riskScore = i.riskScore;
        v.confidence = i.confidence;
        v.confidenceScore = i.confidenceScore;
        v.depth = i.depth;
        v.reason = i.reason;
        v.path = i.path;
        v.evidence = i.evidence;
        v.suggestedTests = i.suggestedTests;
        v.verificationStatus = i.verificationStatus;
        v.riskFactors = i.riskFactors;
        return v;
    }
}
