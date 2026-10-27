package com.geek.association.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.geek.association.util.Json;
import com.geek.association.util.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One restart attempt: what phase it is in, when it started, how it ended.
 *
 * Two processes describe the same attempt, so this value exists twice - once in the memory of the
 * JVM that asked for the restart, once as {@code {dataDir}/restart-status.json} written by the
 * daemon script that survives it. The file is the part that matters after a restart: the new JVM
 * has no memory of the attempt at all, and without the file the settings page could only say
 * "idle" about a restart that just succeeded or rolled back.
 *
 * Deliberate properties, mirroring {@link UpdateHistory} because this is the same kind of
 * bookkeeping:
 *
 * <ul>
 *   <li><b>Reading and writing never throw.</b> The status file is what the UI renders; a full
 *       disk or a half-written document must degrade to "no status" instead of failing
 *       {@code GET /api/update};</li>
 *   <li><b>Writes are atomic.</b> A {@code .tmp} file renamed over the target, so the reader (a
 *       different process, at an arbitrary moment) never sees a truncated JSON document;</li>
 *   <li><b>Phases are plain strings.</b> They are a wire format the frontend switches on, so they
 *       are named here once and never derived from an enum's {@code toString()}.</li>
 * </ul>
 */
public final class RestartStatus {

    private static final Logger log = LoggerFactory.getLogger(RestartStatus.class);

    /** File name under the data directory; the daemon script writes the same file. */
    public static final String FILE_NAME = "restart-status.json";

    /** Accepted, nothing started yet. Also what a fresh process reports. */
    public static final String PHASE_IDLE = "idle";
    /** The daemon script is being prepared and spawned. */
    public static final String PHASE_SPAWNING = "spawning";
    /** The script is waiting for this JVM to exit, or for the new process to become healthy. */
    public static final String PHASE_WAITING = "waiting";
    /** The new build answers its health endpoint. */
    public static final String PHASE_SUCCEEDED = "succeeded";
    /** The new build did not come up and the previous jar was restored and started. */
    public static final String PHASE_ROLLED_BACK = "rolled-back";
    /** The new build did not come up and there was nothing to roll back to. */
    public static final String PHASE_FAILED = "failed";

    private String phase = PHASE_IDLE;
    private Long startedAt;
    private Long finishedAt;
    private String attemptedVersion;
    private String message;
    private boolean rolledBack;

    /** The neutral value: no restart in this process, and none worth reporting from the disk. */
    public static RestartStatus idle() {
        return new RestartStatus();
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = Texts.isBlank(phase) ? PHASE_IDLE : phase;
    }

    public Long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Long startedAt) {
        this.startedAt = startedAt;
    }

    public Long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Long finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getAttemptedVersion() {
        return attemptedVersion;
    }

    public void setAttemptedVersion(String attemptedVersion) {
        this.attemptedVersion = attemptedVersion;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isRolledBack() {
        return rolledBack;
    }

    public void setRolledBack(boolean rolledBack) {
        this.rolledBack = rolledBack;
    }

    /** A detached copy, so a reader never observes a field changing under it. */
    public RestartStatus copy() {
        RestartStatus copy = new RestartStatus();
        copy.phase = phase;
        copy.startedAt = startedAt;
        copy.finishedAt = finishedAt;
        copy.attemptedVersion = attemptedVersion;
        copy.message = message;
        copy.rolledBack = rolledBack;
        return copy;
    }

    /** The wire shape of the file (and of the {@code restart} object in the update payload). */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("phase", phase);
        out.put("startedAt", startedAt);
        out.put("finishedAt", finishedAt);
        out.put("attemptedVersion", attemptedVersion);
        out.put("message", message);
        out.put("rolledBack", rolledBack);
        return out;
    }

    /**
     * Reads the status file, or returns null when there is nothing usable to read.
     *
     * Null rather than an idle instance: the caller has to be able to tell "this installation has
     * never restarted" from "it restarted and here is the outcome", and only the second one may
     * override what the current process knows.
     */
    public static RestartStatus read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode root = Json.mapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                return null;
            }
            RestartStatus status = new RestartStatus();
            String phase = text(root, "phase");
            status.setPhase(phase);
            status.startedAt = epochMillis(root.path("startedAt"));
            status.finishedAt = epochMillis(root.path("finishedAt"));
            status.attemptedVersion = text(root, "attemptedVersion");
            status.message = text(root, "message");
            status.rolledBack = root.path("rolledBack").asBoolean(false);
            return status;
        } catch (IOException | RuntimeException e) {
            // A status file is a convenience: the endpoint that renders it must keep answering.
            log.debug("could not read restart status {}: {}", file, e.toString());
            return null;
        }
    }

    /** Writes the status atomically; a failure is logged and otherwise ignored. */
    public static void write(Path file, RestartStatus status) {
        if (file == null || status == null) {
            return;
        }
        Path directory = file.getParent();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (directory != null) {
                Files.createDirectories(directory);
            }
            Files.writeString(tmp, Json.write(status.toMap()), StandardCharsets.UTF_8);
            // Same directory, so the rename stays on one filesystem and is therefore atomic: the
            // reader is another process and may look at any moment, including mid-write.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            log.warn("could not write restart status {}: {}", file, e.toString());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // A leftover .tmp is harmless; the next write replaces it.
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return Texts.isBlank(text) ? null : text.strip();
    }

    private static Long epochMillis(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        // The daemon script writes a bare number, but a hand-edited file may hold a string; both
        // are accepted so a mistake in one field does not lose the whole status.
        try {
            return Long.parseLong(node.asText().strip());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
