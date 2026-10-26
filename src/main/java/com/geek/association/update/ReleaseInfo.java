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
