package com.geek.association.source;

import com.geek.association.util.Hashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private Map<String, String> hashes;
    private List<String> cached;

    public DirSource(Path root, String label, int maxFiles) {
        this.root = root.toAbsolutePath().normalize();
        this.label = label;
        this.maxFiles = maxFiles;
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

    @Override
    public String read(String path) {
        try {
            byte[] bytes = Files.readAllBytes(root.resolve(path));
            if (isBinary(bytes)) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public String hash(String path) {
        if (hashes == null) {
            hashes = new HashMap<>();
            for (String f : files()) {
                try {
                    byte[] bytes = Files.readAllBytes(root.resolve(f));
                    hashes.put(f, gitBlobId(bytes));
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
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            md.update(("blob " + content.length).getBytes(StandardCharsets.US_ASCII));
            md.update((byte) 0);
            md.update(content);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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