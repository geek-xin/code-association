package com.geek.association.source;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Directory listing behind the UI folder picker.
 *
 * A browser file input only exposes relative names, never the absolute path of the folder the
 * user picked, so a real "choose a folder" flow has to ask the local service. That is also what
 * lets us show git / Maven hints and reject a missing path before a source is saved.
 *
 * Only directories are listed: file contents are never read here.
 */
@Component
public class PathBrowser {

    /** One folder in a listing. */
    public record Entry(String name, String path, boolean git, boolean maven, boolean hidden) {
    }

    /** Safety valve for pathological directories such as / or a huge node_modules. */
    private static final int MAX_ENTRIES = 1000;

    /**
     * Lists the sub-directories of {@code requested}.
     *
     * @param requested absolute path, {@code ~} shorthand, or blank for the user home directory
     * @param showHidden include entries whose name starts with a dot
     */
    public Map<String, Object> browse(String requested, boolean showHidden) {
        Path dir = resolve(requested);
        if (!Files.exists(dir)) {
            throw new IllegalArgumentException("目录不存在：" + dir);
        }
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("不是文件夹：" + dir);
        }

        List<Entry> entries = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                boolean hidden = name.startsWith(".");
                if (hidden && !showHidden) {
                    continue;
                }
                if (!Files.isDirectory(child)) {
                    continue;
                }
                if (entries.size() >= MAX_ENTRIES) {
                    truncated = true;
                    break;
                }
                entries.add(new Entry(name, child.toAbsolutePath().normalize().toString(),
                        Files.isDirectory(child.resolve(".git")),
                        Files.isRegularFile(child.resolve("pom.xml")),
                        hidden));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取目录：" + e.getMessage());
        } catch (SecurityException e) {
            throw new IllegalArgumentException("没有访问权限：" + dir);
        }
        entries.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));

        Path parent = dir.getParent();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", dir.toString());
        out.put("name", dir.getFileName() == null ? dir.toString() : dir.getFileName().toString());
        out.put("parent", parent == null ? null : parent.toString());
        // The listing describes the children, but the picker also needs to know what the folder
        // being looked at is, so it can preselect LOCAL_GIT for a working copy.
        out.put("git", Files.isDirectory(dir.resolve(".git")));
        out.put("maven", Files.isRegularFile(dir.resolve("pom.xml")));
        out.put("entries", entries);
        out.put("truncated", truncated);
        out.put("roots", roots());
        return out;
    }

    /**
     * Resolves user input to an absolute, normalised directory path.
     *
     * Blank means the home directory, and a leading {@code ~/} is expanded because that is what
     * people type into a path field.
     */
    public static Path resolve(String requested) {
        String home = System.getProperty("user.home");
        String raw = requested == null ? "" : requested.strip();
        if (raw.isEmpty() || "~".equals(raw)) {
            return Path.of(home).toAbsolutePath().normalize();
        }
        if (raw.startsWith("~/") || raw.startsWith("~" + java.io.File.separator)) {
            raw = home + raw.substring(1);
        }
        try {
            return Path.of(raw).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("路径格式不正确：" + requested);
        }
    }

    /** Shortcuts offered at the top of the picker. */
    private List<Map<String, String>> roots() {
        List<Map<String, String>> out = new ArrayList<>();
        out.add(Map.of("label", "主目录", "path", System.getProperty("user.home")));
        for (java.io.File root : java.io.File.listRoots()) {
            out.add(Map.of("label", root.getPath(), "path", root.getPath()));
        }
        return out;
    }
}
