package com.geek.association.update;

import java.util.ArrayList;
import java.util.List;

/**
 * One GitHub release, already normalised for the API contract.
 *
 * The fields are the wire shape, so they are public and serialised by name; adding a field here is
 * a change to the API the frontend sees.
 */
public class ReleaseInfo {

    /** Normalised version without the leading v, e.g. {@code 0.3.0}. */
    public String version;
    /** Raw tag as published, e.g. {@code v0.3.0}; what the download URL is keyed on. */
    public String tag;
    public String name;
    /** ISO-8601 instant, or null when GitHub did not report one. */
    public String publishedAt;
    public boolean prerelease;
    /** True when this release is exactly the running version. */
    public boolean current;
    /** True when this release is newer than the running version. */
    public boolean newer;
    /**
     * True when the entry came from the tags endpoint and has no GitHub release behind it.
     *
     * Such a version exists - the code is tagged and published - but carries no notes and usually
     * no jar, so the UI must not offer it as if it were a full release.
     */
    public boolean fromTag;
    /**
     * True when this version's jar is already in {@code {dataDir}/updates}.
     *
     * Recomputed from the directory on every status view rather than stored, so a file deleted by
     * hand, or a download made before this process started, is reported correctly.
     */
    public boolean downloaded;
    /** When the local jar was downloaded, ISO-8601, or null when it is not downloaded. */
    public String downloadedAt;
    /** Markdown body; empty string when absent so the UI never has to handle null. */
    public String notes = "";
    public String url;
    public List<Asset> assets = new ArrayList<>();

    /** A downloadable file of the release. */
    public static class Asset {
        public String name;
        public long size;
        public String downloadUrl;

        public Asset() {
        }

        public Asset(String name, long size, String downloadUrl) {
            this.name = name;
            this.size = size;
            this.downloadUrl = downloadUrl;
        }

        /** The jar we can swap in place; other assets are only useful to a human. */
        public boolean isJar() {
            return name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar");
        }
    }
}
