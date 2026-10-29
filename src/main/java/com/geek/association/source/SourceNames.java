package com.geek.association.source;

import com.geek.association.model.RepoSource;
import com.geek.association.util.Texts;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Derives a display name for a source from its location.
 *
 * The name is a label, not an identifier: it is only used in lists and reports, so asking the
 * user to type one was pure friction. It is derived on the server so the web UI, the CLI and the
 * REST API all agree on the same answer.
 */
public final class SourceNames {

    private SourceNames() {
    }

    /**
     * Picks a name for {@code source}, or returns its existing name when it already has one.
     *
     * The label answers one question - "which program is this" - so both kinds of location are
     * answered the same way: a local directory uses its folder name, a remote repository uses its
     * program name (the last segment of the URL). {@code owner/repo} reads as a location, and it
     * made the source list show two long coordinates next to short local folder names.
     */
    public static String derive(RepoSource source) {
        if (source == null) {
            return "source";
        }
        if (!Texts.isBlank(source.name)) {
            return source.name.strip();
        }
        String derived = deriveFrom(source.type, source.location);
        return Texts.isBlank(derived) ? "source" : derived;
    }

    /** The name the location alone implies, ignoring any name already stored on the source. */
    public static String deriveFrom(String type, String location) {
        String kind = type == null ? "LOCAL_DIR" : type.toUpperCase(Locale.ROOT);
        String where = Texts.trim(location);
        String derived = isRemote(kind) ? fromRemote(where) : fromPath(where);
        return Texts.isBlank(derived) ? "" : derived.strip();
    }

    /** Fills {@code source.name} when it is blank. */
    public static RepoSource withDerivedName(RepoSource source) {
        if (source != null && Texts.isBlank(source.name)) {
            source.name = derive(source);
        }
        return source;
    }

    /**
     * The name a remote source carried before names were cut down to the program name.
     *
     * Only the store migration calls this. A stored name that still equals the old {@code owner/repo}
     * derivation proves the row was auto-named and never renamed by hand, which is what makes
     * rewriting it safe; every other row keeps its name. Null for local sources and unusable
     * locations.
     */
    public static String legacyRemoteName(RepoSource source) {
        if (source == null) {
            return null;
        }
        String kind = source.type == null ? "" : source.type.toUpperCase(Locale.ROOT);
        if (!isRemote(kind)) {
            return null;
        }
        String path = remotePath(source.location);
        return Texts.isBlank(path) ? null : path;
    }

    private static boolean isRemote(String type) {
        return switch (type) {
            case "GITLAB", "GITHUB", "GIT", "GIT_REMOTE", "REMOTE" -> true;
            default -> false;
        };
    }

    /** The last path segment, which is the folder the user pointed at. */
    private static String fromPath(String location) {
        if (Texts.isBlank(location)) {
            return "";
        }
        try {
            Path path = PathBrowser.resolve(location);
            Path fileName = path.getFileName();
            return fileName == null ? "" : fileName.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * The program name of a remote location: the last path segment, once the scheme, host,
     * credentials, query, trailing slashes and the .git suffix are stripped.
     * Both URL and scp-like remote syntaxes are accepted.
     */
    static String fromRemote(String location) {
        String path = remotePath(location);
        if (Texts.isBlank(path)) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * The path part of a remote location, without scheme, host, credentials, query or .git suffix.
     *
     * Split out because the migration has to reproduce the old {@code owner/repo} answer from
     * exactly the parsing rules the new program name comes from.
     */
    private static String remotePath(String location) {
        if (Texts.isBlank(location)) {
            return "";
        }
        String raw = location.strip();
        // scp-like syntax: git@host:owner/repo.git
        int at = raw.indexOf('@');
        int colon = raw.indexOf(':');
        if (at >= 0 && colon > at && raw.indexOf("://") < 0) {
            raw = raw.substring(colon + 1);
        } else {
            int scheme = raw.indexOf("://");
            if (scheme >= 0) {
                raw = raw.substring(scheme + 3);
                int slash = raw.indexOf('/');
                raw = slash < 0 ? "" : raw.substring(slash + 1);
            }
        }
        raw = raw.replaceAll("[?#].*$", "");
        while (raw.endsWith("/")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        if (raw.endsWith(".git")) {
            raw = raw.substring(0, raw.length() - 4);
        }
        return raw;
    }
}
