package com.geek.association.parse;

import com.geek.association.model.ModuleInfo;
import com.geek.association.source.FileSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads every pom.xml of a repository and derives the module tree, the artifact coordinates
 * and the declared inter-module dependencies. Directory layout is never used as a substitute:
 * the build model is the source of truth.
 */
public class MavenParser {

    public Map<String, ModuleInfo> modules = new LinkedHashMap<>();
    public Map<String, String> fileToModule = new HashMap<>();
    public Map<String, String> artifactToModule = new HashMap<>();
    public List<String[]> moduleDependencies = new ArrayList<>();

    public void parse(FileSource source) {
        List<String> poms = new ArrayList<>();
        for (String f : source.files()) {
            if (f.equals("pom.xml") || f.endsWith("/pom.xml")) {
                poms.add(f);
            }
        }
        poms.sort((a, b) -> Integer.compare(a.length(), b.length()));
        Map<String, Document> docs = new LinkedHashMap<>();
        for (String pom : poms) {
            Document doc = readXml(source.read(pom));
            if (doc != null) {
                docs.put(pom, doc);
            }
        }
        // keyed by module directory (the pom path minus "/pom.xml"), not by the pom file name
        Map<String, ModuleInfo> byPom = new LinkedHashMap<>();
        Map<String, ModuleInfo> byDir = new LinkedHashMap<>();
        for (Map.Entry<String, Document> e : docs.entrySet()) {
            String pom = e.getKey();
            String dir = pom.equals("pom.xml") ? "" : pom.substring(0, pom.length() - "/pom.xml".length());
            ModuleInfo info = new ModuleInfo();
            info.path = dir;
            Element project = e.getValue().getDocumentElement();
            Element parent = child(project, "parent");
            info.parentArtifact = parent == null ? null : text(parent, "artifactId");
            info.groupId = text(project, "groupId");
            if (info.groupId == null && parent != null) {
                info.groupId = text(parent, "groupId");
            }
            info.artifactId = text(project, "artifactId");
            info.version = text(project, "version");
            if (info.version == null && parent != null) {
                info.version = text(parent, "version");
            }
            if (info.artifactId == null) {
                info.artifactId = dir.isBlank() ? "root" : dir.substring(dir.lastIndexOf('/') + 1);
            }
            Element modulesEl = child(project, "modules");
            if (modulesEl != null) {
                for (Element m : children(modulesEl, "module")) {
                    String mod = m.getTextContent().trim();
                    if (!mod.isBlank()) {
                        info.declaredModules.add(mod);
                        info.aggregator = true;
                    }
                }
            }
            Element depsEl = child(project, "dependencies");
            if (depsEl != null) {
                for (Element d : children(depsEl, "dependency")) {
                    String aid = text(d, "artifactId");
                    String scope = text(d, "scope");
                    if (aid != null && !"test".equals(scope)) {
                        info.dependencyArtifacts.add(aid);
                    }
                }
            }
            Element build = child(project, "build");
            if (build != null) {
                Element sd = child(build, "sourceDirectory");
                if (sd != null && !sd.getTextContent().isBlank()) {
                    info.sourceRoots.add(normalize(dir, sd.getTextContent().trim()));
                }
            }
            if (info.sourceRoots.isEmpty()) {
                info.sourceRoots.add(dir.isBlank() ? "src/main/java" : dir + "/src/main/java");
            }
            byPom.put(pom, info);
            byDir.put(dir, info);
        }
        if (byPom.isEmpty()) {
            ModuleInfo root = new ModuleInfo();
            root.path = "";
            root.artifactId = "root";
            byPom.put("pom.xml", root);
            byDir.put("", root);
        }
        // groupId inheritance through relativePath (../pom.xml) when not declared explicitly
        for (Map.Entry<String, ModuleInfo> e : byPom.entrySet()) {
            ModuleInfo info = e.getValue();
            if (info.groupId != null && !info.groupId.isBlank()) {
                continue;
            }
            String dir = info.path;
            String parentDir = dir.isBlank() ? null : (dir.contains("/") ? dir.substring(0, dir.lastIndexOf('/')) : "");
            String parentPom = parentDir == null ? null : (parentDir.isBlank() ? "pom.xml" : parentDir + "/pom.xml");
            ModuleInfo p = parentPom == null ? null : byPom.get(parentPom);
            if (p != null && info.artifactId != null && info.artifactId.equals(p.artifactId)) {
                continue;
            }
            if (p != null) {
                info.groupId = p.groupId;
            }
        }
        modules = byDir;
        for (ModuleInfo info : modules.values()) {
            artifactToModule.put(info.artifactId, info.path);
        }
        for (ModuleInfo info : modules.values()) {
            for (String dep : info.dependencyArtifacts) {
                String target = artifactToModule.get(dep);
                if (target != null && !target.equals(info.path)) {
                    moduleDependencies.add(new String[]{info.path, target, dep});
                }
            }
        }
        indexFiles(source);
    }

    private void indexFiles(FileSource source) {
        List<String> modulePaths = new ArrayList<>(modules.values().stream().map(m -> m.path).toList());
        modulePaths.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String f : source.files()) {
            String owner = "";
            for (String mp : modulePaths) {
                if (mp.isBlank()) {
                    continue;
                }
                if (f.startsWith(mp + "/")) {
                    owner = mp;
                    break;
                }
            }
            if (modules.containsKey("")) {
                ModuleInfo root = modules.get("");
                if (owner.isBlank() && !root.declaredModules.isEmpty()) {
                    // file belongs to the aggregator only when not inside any child module
                    owner = "";
                }
            }
            fileToModule.put(f, owner);
        }
    }

    public String moduleOf(String path) {
        return fileToModule.getOrDefault(path, "");
    }

    public ModuleInfo module(String path) {
        return modules.get(path == null ? "" : path);
    }

    public boolean hasModule(String path) {
        return modules.containsKey(path == null ? "" : path);
    }

    private String normalize(String dir, String value) {
        String v = value.replace('\\', '/');
        if (v.startsWith("./")) {
            v = v.substring(2);
        }
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        if (dir.isBlank()) {
            return v;
        }
        return v.startsWith(dir + "/") ? v : dir + "/" + v;
    }

    private Document readXml(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new org.xml.sax.InputSource(new java.io.StringReader("")));
            return builder.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static String text(Element parent, String name) {
        Element e = child(parent, name);
        return e == null ? null : e.getTextContent().trim();
    }
}