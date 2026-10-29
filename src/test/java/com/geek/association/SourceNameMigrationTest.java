package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.model.RepoSource;
import com.geek.association.store.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Remote sources are named after their program now, so existing registries have to follow.
 *
 * The migration only rewrites a row whose name is still exactly what the old build would have
 * derived: that is the one case where the name is known not to be a user's choice. Everything
 * else - a hand-picked name, a local source - has to come out unchanged, or the upgrade quietly
 * renames people's programs.
 */
class SourceNameMigrationTest {

    private static Store store(Path tmp) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(tmp.toString());
        Store store = new Store(properties);
        store.init();
        return store;
    }

    private static RepoSource save(Store store, String type, String name, String location) {
        RepoSource source = new RepoSource();
        source.type = type;
        source.name = name;
        source.location = location;
        return store.saveSource(source);
    }

    @Test
    void oldAutoDerivedRemoteNameIsSimplifiedOnTheNextStart(@TempDir Path tmp) {
        RepoSource saved = save(store(tmp), "GITHUB", "org/repo", "https://github.com/org/repo.git");

        // A second Store over the same data dir is what an app restart looks like to SQLite.
        assertEquals("repo", store(tmp).source(saved.id).name);
    }

    @Test
    void nestedGroupSourceCollapsesToTheProgramName(@TempDir Path tmp) {
        RepoSource saved = save(store(tmp), "GITLAB", "group/sub/repo", "https://gitlab.com/group/sub/repo");

        assertEquals("repo", store(tmp).source(saved.id).name);
    }

    @Test
    void aNameTheUserPickedSurvivesTheMigration(@TempDir Path tmp) {
        RepoSource saved = save(store(tmp), "GITHUB", "billing-fork", "https://github.com/org/repo.git");

        assertEquals("billing-fork", store(tmp).source(saved.id).name);
    }

    @Test
    void aSlashInAHandPickedNameIsNotMistakenForTheOldDerivation(@TempDir Path tmp) {
        RepoSource saved = save(store(tmp), "GITHUB", "team-a/fork", "https://github.com/org/repo.git");

        assertEquals("team-a/fork", store(tmp).source(saved.id).name);
    }

    @Test
    void localSourcesAreNotTouched(@TempDir Path tmp) {
        RepoSource saved = save(store(tmp), "LOCAL_DIR", "orders", "/tmp/orders");

        assertEquals("orders", store(tmp).source(saved.id).name);
    }
}
