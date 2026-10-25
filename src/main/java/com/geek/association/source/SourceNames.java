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
     * Local sources use the directory name. Remote sources use {@code owner/repo} when the URL
     * carries an owner, because "repo" alone is ambiguous once several forks are registered.
     */
    public static String derive(RepoSource source) {
        if (source == null) {
            return "source";
        }
        if (!Texts.isBlank(source.name)) {
            return source.name.strip();
        }
        String type = source.type == null ? "LOCAL_DIR" : source.type.toUpperCase(Locale.ROOT);
        String location = Texts.trim(source.location);
        String derived = isRemote(type) ? fromRemote(location) : fromPath(location);
        return Texts.isBlank(derived) ? "source" : derived;
    }

    /** Fills {@code source.name} when it is blank. */
    public static RepoSource withDerivedName(RepoSource source) {
        if (source != null && Texts.isBlank(source.name)) {
            source.name = derive(source);
        }
        return source;
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
     * Strips the scheme, host, credentials, trailing slashes and the .git suffix.
     * Both URL and scp-like remote syntaxes are accepted.
     */
    static String fromRemote(String location) {
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
