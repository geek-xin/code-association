package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.model.Analysis;
import com.geek.association.model.PeerImpact;
import com.geek.association.model.RepoSource;
import com.geek.association.report.ImpactReport;
import com.geek.association.source.RepositoryPreparer;
import com.geek.association.store.Store;
import com.geek.association.web.ApiController;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evidence excerpt endpoint, wired through the same collaborators the server uses.
 *
 * <p>Three behaviours matter: it reads the file a report actually cited, it points at the cited
 * line, and it refuses everything the report did not cite. The last one is what keeps a local
 * service from becoming an arbitrary file reader.</p>
 */
class CodeExcerptEndpointTest {

    @Test
    void returnsTheLinesAroundTheCitedPosition(@TempDir Path temp) throws Exception {
        Fixture f = fixture(temp);

        Map<String, Object> out = f.controller.code(f.analysisId, "A.java", 2, 1, "window", null);

        assertEquals("A.java", out.get("path"));
        assertEquals(2, out.get("line"));
        assertEquals(1, out.get("startLine"));
        assertEquals(3, out.get("endLine"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) out.get("lines");
        assertEquals(3, lines.size());
        assertEquals("line 1", lines.get(0).get("text"));
        assertEquals("line 2", lines.get(1).get("text"));
    }

    @Test
    void blockModeReturnsTheWholeMethodTheLineLivesIn(@TempDir Path temp) throws Exception {
        Fixture f = fixture(temp);
        Files.writeString(f.sourceRoot.resolve("A.java"), """
                class A {
                    void m() {
                        call();
                    }
                }
                """);

        Map<String, Object> out = f.controller.code(f.analysisId, "A.java", 3, 3, "block", null);

        assertEquals(true, out.get("block"));
        assertEquals("方法", out.get("blockLabel"));
        assertEquals(2, out.get("startLine"));
        assertEquals(4, out.get("endLine"));
        assertEquals(5, out.get("totalLines"));
        assertEquals("方法 · 第 2–4 行", out.get("caption"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) out.get("lines");
        assertEquals(3, lines.size());
        assertEquals("        call();", lines.get(1).get("text"));
    }

    @Test
    void refusesFilesTheReportNeverCited(@TempDir Path temp) throws Exception {
        Fixture f = fixture(temp);
        Files.writeString(f.sourceRoot.resolve("B.java"), "secret");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> f.controller.code(f.analysisId, "B.java", 1, 1, "window", null));
        assertTrue(e.getMessage().contains("没有"), e.getMessage());
    }

    @Test
    void refusesPathsThatClimbOutOfTheSourceRoot(@TempDir Path temp) throws Exception {
        Fixture f = fixture(temp);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> f.controller.code(f.analysisId, "../outside", 1, 1, "window", null));
        assertTrue(e.getMessage().contains("越出"), e.getMessage());
    }

    @Test
    void refusesAPeerThatIsNotPartOfTheAnalysis(@TempDir Path temp) throws Exception {
        Fixture f = fixture(temp);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> f.controller.code(f.analysisId, "A.java", 1, 1, "window", 99L));
        assertTrue(e.getMessage().contains("对比程序"), e.getMessage());
    }

    @Test
    void peerEvidenceIsReadFromTheCommitTheAnalysisIndexed(@TempDir Path temp) throws Exception {
        Path peerRoot = temp.resolve("peer");
        Files.createDirectories(peerRoot);
        String peerCommit;
        try (Git git = Git.init().setDirectory(peerRoot.toFile()).call()) {
            Files.writeString(peerRoot.resolve("A.java"), "peer 1\npeer 2\npeer 3\n");
            git.add().addFilepattern("A.java").call();
            peerCommit = git.commit().setMessage("init").call().getName();
            // The working tree moves on after the analysis; the excerpt must not follow it.
            Files.writeString(peerRoot.resolve("A.java"), "changed later\nchanged later\n");
        }

        AppProperties properties = new AppProperties();
        properties.setDataDir(temp.resolve("data").toString());
        Store store = new Store(properties);
        store.init();
        RepoSource own = source(store, "own", "LOCAL_DIR", temp.resolve("own"));
        RepoSource peer = source(store, "peer", "LOCAL_GIT", peerRoot);

        Analysis analysis = new Analysis();
        analysis.sourceId = own.id;
        analysis.sourceName = own.name;
        analysis.sourceType = own.type;
        analysis.mode = "GIT_DIFF";
        analysis = store.createAnalysis(analysis);

        ImpactReport report = new ImpactReport();
        report.mode = "GIT_DIFF";
        PeerImpact peerImpact = new PeerImpact();
        peerImpact.peerSourceId = peer.id;
        peerImpact.peerSourceName = peer.name;
        peerImpact.peerType = peer.type;
        peerImpact.peerLocation = peer.location;
        peerImpact.peerCommit = peerCommit;
        PeerImpact.ContractMatch match = new PeerImpact.ContractMatch();
        match.peerFile = "A.java";
        match.peerLine = 2;
        peerImpact.matches.add(match);
        report.peers.add(peerImpact);
        store.updateReportJson(analysis.id, report);

        ApiController controller = new ApiController(store, null, new RepositoryPreparer(properties),
                properties, null, null, null, null, null);
        Map<String, Object> out = controller.code(analysis.id, "A.java", 2, 1, "window", peer.id);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) out.get("lines");
        assertEquals("peer 2", lines.get(1).get("text"));
        assertEquals(peerCommit.substring(0, 8), out.get("revision"));
    }

    private static RepoSource source(Store store, String name, String type, Path location) throws Exception {
        Files.createDirectories(location);
        RepoSource source = new RepoSource();
        source.name = name;
        source.type = type;
        source.location = location.toString();
        return store.saveSource(source);
    }

    private static Fixture fixture(Path temp) throws Exception {
        Path data = temp.resolve("data");
        Path sourceRoot = temp.resolve("program");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("A.java"), "line 1\nline 2\nline 3\nline 4\nline 5\n");

        AppProperties properties = new AppProperties();
        properties.setDataDir(data.toString());
        Store store = new Store(properties);
        store.init();

        RepoSource source = new RepoSource();
        source.name = "program";
        source.type = "LOCAL_DIR";
        source.location = sourceRoot.toString();
        source = store.saveSource(source);

        Analysis analysis = new Analysis();
        analysis.sourceId = source.id;
        analysis.sourceName = source.name;
        analysis.sourceType = source.type;
        analysis.mode = "WORKTREE";
        analysis = store.createAnalysis(analysis);

        ImpactReport report = new ImpactReport();
        report.sourceName = source.name;
        report.mode = "WORKTREE";
        report.headCommit = "";
        ImpactReport.ImpactView impact = new ImpactReport.ImpactView();
        impact.id = 1;
        impact.changedFile = "A.java";
        impact.changedLine = 2;
        report.impacts.add(impact);
        store.updateReportJson(analysis.id, report);

        ApiController controller = new ApiController(store, null, new RepositoryPreparer(properties),
                properties, null, null, null, null, null);
        return new Fixture(controller, analysis.id, sourceRoot);
    }

    private record Fixture(ApiController controller, long analysisId, Path sourceRoot) {
    }
}
