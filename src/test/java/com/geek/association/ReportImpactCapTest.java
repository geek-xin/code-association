package com.geek.association;

import com.geek.association.impact.RiskScorer;
import com.geek.association.model.CodeIndex;
import com.geek.association.model.ImpactResult;
import com.geek.association.model.PeerImpact;
import com.geek.association.report.ImpactReport;
import com.geek.association.report.ReportBuilder;
import com.geek.association.source.DirSource;
import com.geek.association.source.RevisionPair;
import com.geek.association.util.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告影响清单的截断规则。
 *
 * <p>回归的那次事故：2880 个文件、20052 项变更、三个对比程序，产出 67 万条跨程序影响，
 * 报告序列化到 2 GiB 时撞上 Jackson 的文本缓冲上限，整次分析在最后一步失败、报告全丢。
 * 因此这里的断言围绕三件事：<b>计数仍然全量</b>、<b>清单按风险保留</b>、
 * <b>同一批影响不再在 peers[].impacts 里重复序列化一遍</b>。</p>
 */
@DisplayName("报告影响清单截断：全量计数 + 风险优先 + 按程序公平分配")
class ReportImpactCapTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("超过上限时只保留风险最高的一批，总数与说明仍按全量")
    void capsTheListButKeepsTheCountsExact() {
        PeerImpact noisy = peer("noisy", 900);
        PeerImpact quiet = peer("quiet", 100);

        ImpactReport report = new ReportBuilder(200)
                .build(revisions(), new CodeIndex(), new CodeIndex(), List.of(), List.of(), Map.of(),
                        List.of(noisy, quiet));

        assertEquals(1000, report.impactsTotal, "总数必须是截断前的全量");
        assertEquals(1000, report.summary.impactedSymbols, "汇总计数不受清单截断影响");
        assertEquals(200, report.impactsShown);
        assertEquals(200, report.impacts.size());
        assertEquals(800, report.impactsTruncated);
        assertNotNull(report.impactsNote, "截断必须留下可见的说明，否则列表会被读成全部结果");
        assertTrue(report.impactsNote.contains("1000") && report.impactsNote.contains("200"),
                "说明里要同时给出总数与保留数：" + report.impactsNote);

        long noisyRows = report.impacts.stream().filter(i -> "noisy".equals(i.peerSourceName)).count();
        long quietRows = report.impacts.stream().filter(i -> "quiet".equals(i.peerSourceName)).count();
        assertEquals(100, noisyRows, "按程序公平分配：噪声大的程序不能占满整份清单");
        assertEquals(100, quietRows, "安静的程序一条都不能被挤掉");
    }

    @Test
    @DisplayName("清单按风险从高到低排，截断只切掉尾部")
    void keepsTheRiskiestRows() {
        PeerImpact peer = peer("order-service", 300);

        ImpactReport report = new ReportBuilder(50)
                .build(revisions(), new CodeIndex(), new CodeIndex(), List.of(), List.of(), Map.of(),
                        List.of(peer));

        assertEquals(50, report.impacts.size());
        // 合成数据的风险分是递减的（300, 299, ...），保留的必须是前 50 条：最低分 251，
        // 被丢掉的第一条是 250。
        double lowestKept = report.impacts.stream().mapToDouble(i -> i.riskScore).min().orElse(0);
        assertEquals(251.0, lowestKept, 0.001,
                "截断必须从风险尾部切，不能先丢高风险条目");
    }

    @Test
    @DisplayName("调用方没有按风险排序时，仍保留风险最高的一批")
    void unsortedPeerListStillKeepsTheRiskiestRows() {
        PeerImpact peer = peer("order-service", 300);
        // 最低风险排在最前面：按数组前缀截断会先丢掉全部高风险条目。
        java.util.Collections.reverse(peer.impacts);

        ImpactReport report = new ReportBuilder(10)
                .build(revisions(), new CodeIndex(), new CodeIndex(), List.of(), List.of(), Map.of(),
                        List.of(peer));

        assertEquals(10, report.impacts.size());
        double lowestKept = report.impacts.stream().mapToDouble(i -> i.riskScore).min().orElse(0);
        assertEquals(291.0, lowestKept, 0.001,
                "应保留风险分 300..291 的 10 条，而不是数组开头的 10 条");
    }

    @Test
    @DisplayName("安静程序用不完的配额会让给其他程序，而不是留空")
    void unusedQuotaIsFilledFromOtherPeers() {
        PeerImpact big = peer("big", 500);
        PeerImpact tiny = peer("tiny", 3);

        ImpactReport report = new ReportBuilder(100)
                .build(revisions(), new CodeIndex(), new CodeIndex(), List.of(), List.of(), Map.of(),
                        List.of(big, tiny));

        assertEquals(100, report.impacts.size(), "总配额要尽量用满，而不是按程序数平均后浪费");
        assertTrue(report.impacts.stream().anyMatch(i -> "tiny".equals(i.peerSourceName)));
        assertEquals(97, report.impacts.stream().filter(i -> "big".equals(i.peerSourceName)).count());
    }

    @Test
    @DisplayName("未超过上限时一条不丢，也不写截断说明")
    void smallReportIsLeftAlone() {
        ImpactReport report = new ReportBuilder(100)
                .build(revisions(), new CodeIndex(), new CodeIndex(), List.of(), List.of(), Map.of(),
                        List.of(peer("order-service", 10)));

        assertEquals(10, report.impactsTotal);
        assertEquals(10, report.impactsShown);
        assertEquals(0, report.impactsTruncated);
        assertNull(report.impactsNote, "没截断就不该出现「只保留」的说明");
    }

    @Test
    @DisplayName("上限为 0 表示不截断")
    void zeroDisablesTheCap() {
        ImpactReport report = new ReportBuilder(0)
                .build(revisions(), new CodeIndex(), new CodeIndex(), List.of(), List.of(), Map.of(),
                        List.of(peer("order-service", 120)));

        assertEquals(120, report.impactsShown);
        assertEquals(0, report.impactsTruncated);
    }

    @Test
    @DisplayName("同一批影响不再在 peers[].impacts 里序列化第二遍")
    void peerImpactsAreNotSerialisedTwice() {
        PeerImpact peer = peer("order-service", 5);
        ImpactReport report = new ReportBuilder(100)
                .build(revisions(), new CodeIndex(), new CodeIndex(), List.of(), List.of(), Map.of(),
                        List.of(peer));

        String json = Json.write(report);
        // peers[].impacts 被 @JsonIgnore 排除后，整份 JSON 里只应出现一次 "impacts" 字段（报告顶层）。
        assertEquals(1, countOccurrences(json, "\"impacts\":"), "顶层 impacts 之外的重复副本必须消失");

        ImpactReport readBack = Json.read(json, ImpactReport.class);
        assertEquals(report.impacts.size(), readBack.impacts.size(), "读回来的清单条数不变");
        assertTrue(readBack.peers.stream().allMatch(p -> p.impacts.isEmpty()),
                "从数据库读回的报告没有 peers[].impacts，任何读者都必须改用 report.impacts");
    }

    @Test
    @DisplayName("序列化体积由截断后的清单决定，与内存里的全量影响无关")
    void serialisedSizeFollowsTheCappedList() {
        // 2000 条全量影响，但清单只保留 20 条：旧实现会把这 2000 条连同 2000 条视图一起写两遍。
        PeerImpact peer = peer("order-service", 2000);
        ImpactReport report = new ReportBuilder(20)
                .build(revisions(), new CodeIndex(), new CodeIndex(), List.of(), List.of(), Map.of(),
                        List.of(peer));

        String capped = Json.write(report);
        assertTrue(capped.length() < 200_000,
                "截断后的报告应保持在几百 KB 以内，实际 " + capped.length() + " 字符");
        assertFalse(capped.contains("symbol1999"), "被截断的尾部不应出现在序列化结果里");
    }

    @Test
    @DisplayName("六位数量级的运行也只在 JSON 里保留上限条数")
    void hugeRunStillSerialisesToASmallReport() {
        // 复现事故的量级：2880 个文件、三个对比程序，其中两个各产出六位数影响。
        PeerImpact door = peer("door", 20_000);
        PeerImpact crontab = peer("crontab", 10_000);
        ImpactReport report = new ReportBuilder(5000)
                .build(revisions(), new CodeIndex(), new CodeIndex(), List.of(), List.of(), Map.of(),
                        List.of(door, crontab));

        assertEquals(30_000, report.impactsTotal);
        assertEquals(5000, report.impactsShown);
        assertEquals(25_000, report.impactsTruncated);

        long started = System.nanoTime();
        String json = Json.write(report);
        long millis = (System.nanoTime() - started) / 1_000_000;
        // 旧实现在这个量级上会写出上百 MB 并撞上 2 GiB 上限；截断后应当是几 MB 以内。
        assertTrue(json.length() < 5_000_000,
                "截断后仍写 " + json.length() + " 字符，接近序列化上限");
        assertTrue(millis < 10_000, "序列化耗时 " + millis + " ms，不应随全量条数增长");
    }

    private RevisionPair revisions() {
        RevisionPair pair = new RevisionPair();
        pair.note = "user-service";
        pair.base = new DirSource(tmp, "base", 20000);
        pair.head = new DirSource(tmp, "head", 20000);
        pair.mode = "GIT_DIFF";
        pair.baseRef = "base";
        pair.headRef = "head";
        return pair;
    }

    /**
     * 一个带 {@code count} 条影响的对比程序。
     *
     * <p>风险分递减，和 {@code ImpactEngine} 的输出顺序一致（引擎按风险分排序后才交给报告），
     * 这样"取前缀"就等于"取风险最高的一批"，测试才能用简单断言描述这个性质。</p>
     */
    private static PeerImpact peer(String name, int count) {
        PeerImpact peer = new PeerImpact();
        peer.peerSourceName = name;
        peer.peerType = "LOCAL_DIR";
        long id = 1;
        for (int i = 0; i < count; i++) {
            ImpactResult impact = new ImpactResult();
            impact.id = id++;
            impact.changeRecordId = i % 100 + 1;
            // 引擎在真实链路里会给每条影响写上来源程序；报告视图按这两个字段分组。
            impact.peerSourceId = 1;
            impact.peerSourceName = name;
            // 反过来说：第 0 条风险最高，第 count-1 条最低。
            impact.riskScore = count - i;
            impact.riskLevel = RiskScorer.level(impact.riskScore);
            impact.targetModule = name + "-module-" + (i % 5);
            impact.peerModule = impact.targetModule;
            impact.targetSymbolKey = name + "#symbol" + i;
            impact.targetQualifiedName = "com.example." + name + ".Symbol" + i;
            impact.targetSignature = "(int)";
            impact.targetFile = "src/main/java/com/example/" + name + "/Symbol" + i + ".java";
            impact.targetLine = i + 1;
            impact.impactType = "BEHAVIOR";
            impact.confidence = "HIGH";
            impact.confidenceScore = 0.9;
            impact.depth = 1 + i % 4;
            impact.reason = "上游调用 " + impact.targetQualifiedName;
            impact.path.add(new ImpactResult.PathStep(impact.targetQualifiedName, impact.targetModule,
                    impact.targetFile, impact.targetLine));
            impact.evidence.add(new ImpactResult.Evidence("PATH", "changed", impact.targetQualifiedName,
                    impact.targetFile, impact.targetLine, "calls"));
            peer.impacts.add(impact);
            if (impact.riskScore >= 60) {
                peer.highRisk++;
            }
            peer.maxRisk = Math.max(peer.maxRisk, (int) Math.round(impact.riskScore));
            peer.impactedModules = Math.max(peer.impactedModules, 1 + i % 5);
        }
        peer.impactedSymbols = count;
        peer.riskLevel = RiskScorer.level(peer.maxRisk);
        return peer;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
