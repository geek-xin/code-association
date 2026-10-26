package com.geek.association.update;

import java.util.List;

/**
 * Where release information comes from.
 *
 * An interface rather than a concrete client so the orchestration can be tested without the
 * network: the tests feed a fake source, which is the only way to cover caching and "is there an
 * update" decisions deterministically.
 */
public interface VersionSource {

    /**
     * Releases of the configured repository, newest first.
     *
     * @throws UpdateException with a user-readable Chinese message when the source cannot be read
     */
    List<ReleaseInfo> fetch();

    /**
     * Problems from the last {@link #fetch()} that did not make the answer unusable.
     *
     * A source may consult several endpoints (releases, then tags); when one of them fails the
     * rest is still worth showing, but silently dropping half the history would be a lie of
     * omission. These strings are surfaced in the status payload and the UI.
     */
    default List<String> warnings() {
        return List.of();
    }
}
