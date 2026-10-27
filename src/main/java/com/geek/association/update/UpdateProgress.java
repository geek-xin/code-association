package com.geek.association.update;

import com.geek.association.util.Texts;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How far the update pipeline has got, in a form the UI can render.
 *
 * This exists because the automatic path used to be invisible: a scheduled check could start a
 * 40 MB download on a daemon thread and the only trace was a {@code busy} boolean flipping to
 * true, then a log line. A user watching the settings page could not tell a slow download from a
 * hung one, could not see which version was being fetched, and - when the download failed - saw
 * nothing at all, because the failure was logged and swallowed.
 *
 * Deliberate properties:
 *
 * <ul>
 *   <li><b>Memory only, never a file.</b> The pipeline dies with the process that runs it, and the
 *       only cross-process fact worth keeping is the restart outcome, which
 *       {@link RestartStatus} already owns. Persisting this would create a second, staler truth;</li>
 *   <li><b>Fields are volatile and written from the download thread.</b> A reader on an HTTP
 *       thread must see the latest byte count without taking a lock that the streaming loop would
 *       then hold 600 times per download;</li>
 *   <li><b>Phases are plain strings</b>, for the same reason as in {@link RestartStatus}: they are
 *       a wire format the frontend switches on, not an enum's {@code toString()};</li>
 *   <li><b>{@code percent} is -1 when unknown.</b> The asset size is not always reported and a
 *       proxy may strip {@code Content-Length}; inventing a percentage there would make the bar
 *       jump backwards when the real total arrives.</li>
 * </ul>
 */
public final class UpdateProgress {

    /** Nothing running: no update in this process, or one that finished and was consumed. */
    public static final String PHASE_IDLE = "idle";
    /** Streaming the jar to the updates directory. */
    public static final String PHASE_DOWNLOADING = "downloading";
    /** Checking the file header and hashing it. */
    public static final String PHASE_VERIFYING = "verifying";
    /** Backing up the running jar and copying the new one over it. */
    public static final String PHASE_APPLYING = "applying";
    /** The jar is swapped; the supervisor is bringing the service back. */
    public static final String PHASE_RESTARTING = "restarting";
    /** Done: the download is staged (manual path) or the service is back (automatic path). */
    public static final String PHASE_SUCCEEDED = "succeeded";
    /** Stopped with a reason the user can act on. */
    public static final String PHASE_FAILED = "failed";

    private volatile String phase = PHASE_IDLE;
    private volatile String version;
    /** True when the background scheduler started this, false when the user pressed a button. */
    private volatile boolean automatic;
    private volatile long bytes;
    private volatile long totalBytes;
    private volatile Long startedAt;
    private volatile Long updatedAt;
    private volatile String message;
    private volatile String error;

    public String getPhase() {
        return phase;
    }

    public String getVersion() {
        return version;
    }

    public boolean isAutomatic() {
        return automatic;
    }

    public long getBytes() {
        return bytes;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public Long getStartedAt() {
        return startedAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public String getMessage() {
        return message;
    }

    public String getError() {
        return error;
    }

    /** Whether a pipeline is still running. A terminal phase is not "active" even if it just ended. */
    public boolean isActive() {
        return PHASE_DOWNLOADING.equals(phase) || PHASE_VERIFYING.equals(phase)
                || PHASE_APPLYING.equals(phase) || PHASE_RESTARTING.equals(phase);
    }

    /**
     * 0..100, or -1 when the total is unknown.
     *
     * Capped at 99 while active: a bar that reads 100% and then keeps working is the classic
     * "is it stuck?" moment, and the last percent belongs to the phase that actually finishes.
     */
    public int getPercent() {
        if (totalBytes <= 0) {
            return -1;
        }
        int percent = (int) Math.min(100, Math.round(bytes * 100.0 / totalBytes));
        if (isActive() && percent >= 100) {
            return 99;
        }
        return Math.max(0, percent);
    }

    /**
     * Starts a run. Called once per pipeline, so the previous outcome is replaced rather than
     * merged: a stale error from the last attempt must not decorate a run that is going fine.
     */
    public synchronized void start(String phase, String version, boolean automatic, long totalBytes,
                                   String message) {
        this.phase = Texts.isBlank(phase) ? PHASE_IDLE : phase;
        this.version = version;
        this.automatic = automatic;
        this.bytes = 0;
        this.totalBytes = Math.max(0, totalBytes);
        this.startedAt = System.currentTimeMillis();
        this.updatedAt = this.startedAt;
        this.message = message;
        this.error = null;
    }

    /**
     * Starts one transfer attempt of an already-started run.
     *
     * A retry discards the partial file, so the byte counter has to go back to zero - but the run
     * itself did not restart, and resetting {@code startedAt} would make a download that has been
     * going for two minutes look like it just began.
     */
    public synchronized void attemptStarted(long totalBytes, String message) {
        this.bytes = 0;
        this.totalBytes = Math.max(0, totalBytes);
        this.message = message;
        this.updatedAt = System.currentTimeMillis();
        if (this.startedAt == null) {
            this.startedAt = this.updatedAt;
        }
    }

    /** Bytes received so far; called from the streaming loop, so it stays a single volatile write. */
    public void downloaded(long bytes, long totalBytes) {
        this.bytes = bytes;
        if (totalBytes > 0) {
            this.totalBytes = totalBytes;
        }
        this.updatedAt = System.currentTimeMillis();
    }

    /** Moves to another phase, keeping the byte counters so the bar does not snap back to zero. */
    public synchronized void moveTo(String phase, String message) {
        this.phase = Texts.isBlank(phase) ? PHASE_IDLE : phase;
        this.message = message;
        this.updatedAt = System.currentTimeMillis();
    }

    /** A terminal success, with the byte count left intact for "downloaded N MB" in the UI. */
    public synchronized void succeeded(String message) {
        this.phase = PHASE_SUCCEEDED;
        this.message = message;
        this.error = null;
        this.updatedAt = System.currentTimeMillis();
    }

    /** A terminal failure. The message is shown as-is, so it has to be a sentence for a human. */
    public synchronized void failed(String message, String error) {
        this.phase = PHASE_FAILED;
        this.message = message;
        this.error = Texts.isBlank(error) ? message : error;
        this.updatedAt = System.currentTimeMillis();
    }

    /** The wire shape. {@code active} is derived here so the client never has to re-implement it. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", isActive());
        out.put("phase", phase);
        out.put("version", version);
        out.put("automatic", automatic);
        out.put("bytes", bytes);
        out.put("totalBytes", totalBytes);
        out.put("percent", getPercent());
        out.put("startedAt", startedAt);
        out.put("updatedAt", updatedAt);
        out.put("message", message);
        out.put("error", error);
        return out;
    }
}
