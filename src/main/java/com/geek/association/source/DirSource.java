package com.geek.association.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Source tree backed by a directory on disk (working tree, uncommitted changes included). */
public class DirSource implements FileSource {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".idea", ".vscode", "node_modules", "target", "build", "out", "dist",
            ".gradle", ".mvn", ".code-association", "__pycache__", ".venv", "venv", "vendor");

    private final Path root;
    private final String label;
    private final int maxFiles;
    private final int maxFileBytes;
    private final List<SkippedFile> skipped = new ArrayList<>();
    private Map<String, String> hashes;
    private List<String> cached;

    public DirSource(Path root, String label, int maxFiles) {
        this(root, label, maxFiles, GitTreeSource.DEFAULT_MAX_FILE_BYTES);
    }

    public DirSource(Path root, String label, int maxFiles, int maxFileBytes) {
        this.root = root.toAbsolutePath().normalize();
        this.label = label;
        this.maxFiles = maxFiles;
        this.maxFileBytes = maxFileBytes;
    }

    @Override
    public List<String> files() {
        if (cached != null) {
            return cached;
        }
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            cached = out;
            return out;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !isSkipped(p))
                    .limit(maxFiles)
                    .forEach(p -> out.add(rel(p)));
        } catch (IOException e) {
            // fall back to whatever was collected
        }
        out.sort(Comparator.naturalOrder());
        cached = out;
        return out;
    }

    private boolean isSkipped(Path p) {
        Path relPath = root.relativize(p);
        for (Path part : relPath) {
            String name = part.toString();
            if (SKIP_DIRS.contains(name) || name.endsWith(".class") || name.endsWith(".jar")
                    || name.endsWith(".min.js") || name.endsWith(".map")) {
                return true;
            }
        }
        return false;
    }

    private String rel(Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    /**
     * Reads one file, refusing anything above the per-file limit.
     *
     * A committed SDK, a database dump or a packaged jar with an unusual extension is not source
     * code, and loading it whole would cost more memory than the file is worth. Refusing it here
     * keeps the file out of the index while {@link #skippedFiles()} still names it, so the gap is
     * visible instead of silent.
     */
    @Override
    public String read(String path) {
        Path file = root.resolve(path);
        try {
            long size = Files.size(file);
            if (size > maxFileBytes) {
                skip(path, size);
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (isBinary(bytes)) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private void skip(String path, long size) {
        for (SkippedFile existing : skipped) {
            if (existing.path().equals(path)) {
                return;
            }
        }
        skipped.add(new SkippedFile(path, size));
    }

    @Override
    public List<SkippedFile> skippedFiles() {
        return List.copyOf(skipped);
    }

    /**
     * Hashes every file, streaming the content.
     *
     * A size limit on reading would be pointless if the diff pass still loaded the same file whole
     * just to hash it, and skipping it here would be worse: a modified large file would look
     * newly added, producing a change record for something that never changed.
     */
    @Override
    public String hash(String path) {
        if (hashes == null) {
            hashes = new HashMap<>();
            for (String f : files()) {
                try {
                    hashes.put(f, gitBlobId(root.resolve(f)));
                } catch (IOException ignored) {
                    hashes.put(f, "");
                }
            }
        }
        return hashes.get(path);
    }

    /**
     * Git blob object id (SHA-1 of "blob &lt;size&gt;\0" + content).
     *
     * Using the same identity function as {@link GitTreeSource} is what makes a working tree
     * comparable with a commit: without it every file would look modified.
     */
    static String gitBlobId(byte[] content) {
        MessageDigest md = sha1();
        md.update(("blob " + content.length).getBytes(StandardCharsets.US_ASCII));
        md.update((byte) 0);
        md.update(content);
        return hex(md.digest());
    }

    /** Same digest, read in chunks so a huge file never has to fit in one array. */
    static String gitBlobId(Path file) throws IOException {
        MessageDigest md = sha1();
        md.update(("blob " + Files.size(file)).getBytes(StandardCharsets.US_ASCII));
        md.update((byte) 0);
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                md.update(buffer, 0, read);
            }
        }
        return hex(md.digest());
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    @Override
    public boolean exists(String path) {
        return Files.isRegularFile(root.resolve(path));
    }

    @Override
    public String label() {
        return label;
    }

    public Path root() {
        return root;
    }

    private static boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 8000);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }
}