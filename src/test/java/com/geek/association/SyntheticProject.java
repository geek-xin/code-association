package com.geek.association;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates a synthetic Maven repository that has the shape of a real multi-module code base:
 * many modules, many classes per module, cross-module references, Spring controllers, MyBatis
 * mappers, configuration files and tests.
 *
 * It exists so performance work can be measured instead of guessed: every optimisation in this
 * repository is expected to show up as a smaller number here. The shape matters more than the
 * exact size - a project of many small files and a project of a few huge ones stress different
 * parts of the pipeline, and both are generated.
 */
public final class SyntheticProject {

    /** One module of the generated project. */
    public record Module(String dir, String artifactId) {
    }

    private final Path root;

    public SyntheticProject(Path root) {
        this.root = root;
    }

    /**
     * Writes the project and returns it.
     *
     * @param modules       number of child modules
     * @param classesPerMod number of production classes per module
     * @param methodsPerCls number of methods per class
     * @param bigFiles      how many classes are written as one very large file instead
     */
    public SyntheticProject generate(int modules, int classesPerMod, int methodsPerCls, int bigFiles)
            throws IOException {
        Files.createDirectories(root);
        StringBuilder agg = new StringBuilder();
        agg.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n")
                .append("  <modelVersion>4.0.0</modelVersion>\n")
                .append("  <groupId>com.example</groupId>\n")
                .append("  <artifactId>synthetic-root</artifactId>\n")
                .append("  <version>1.0.0</version>\n")
                .append("  <packaging>pom</packaging>\n")
                .append("  <modules>\n");
        for (int m = 0; m < modules; m++) {
            agg.append("    <module>mod-").append(m).append("</module>\n");
        }
        agg.append("  </modules>\n</project>\n");
        write(root.resolve("pom.xml"), agg.toString());

        for (int m = 0; m < modules; m++) {
            writeModule(m, modules, classesPerMod, methodsPerCls, bigFiles);
        }
        return this;
    }

    private void writeModule(int index, int moduleCount, int classesPerMod, int methodsPerCls,
                             int bigFiles) throws IOException {
        String dir = "mod-" + index;
        String artifactId = "mod-" + index;
        StringBuilder pom = new StringBuilder();
        pom.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n")
                .append("  <modelVersion>4.0.0</modelVersion>\n")
                .append("  <parent><groupId>com.example</groupId><artifactId>synthetic-root</artifactId>")
                .append("<version>1.0.0</version></parent>\n")
                .append("  <artifactId>").append(artifactId).append("</artifactId>\n");
        if (index > 0) {
            pom.append("  <dependencies>\n    <dependency><groupId>com.example</groupId>")
                    .append("<artifactId>mod-").append(index - 1).append("</artifactId>")
                    .append("<version>1.0.0</version></dependency>\n  </dependencies>\n");
        }
        pom.append("</project>\n");
        write(root.resolve(dir).resolve("pom.xml"), pom.toString());

        Path pkgDir = root.resolve(dir).resolve("src/main/java/com/example/m" + index);
        Files.createDirectories(pkgDir);

        for (int c = 0; c < classesPerMod; c++) {
            String name = "Service" + c;
            write(pkgDir.resolve(name + ".java"), serviceClass(index, c, classesPerMod, methodsPerCls));
        }
        // A controller per module so the HTTP layer is exercised.
        write(pkgDir.resolve("Api" + index + "Controller.java"), controllerClass(index, classesPerMod));
        // A mapper interface plus its XML, so the SQL layer is exercised.
        write(pkgDir.resolve("Repo" + index + ".java"), mapperInterface(index));
        Path mapperDir = root.resolve(dir).resolve("src/main/resources/mapper");
        Files.createDirectories(mapperDir);
        write(mapperDir.resolve("Repo" + index + ".xml"), mapperXml(index));
        write(root.resolve(dir).resolve("src/main/resources/application.yml"), configYml(index));
        Path testDir = root.resolve(dir).resolve("src/test/java/com/example/m" + index);
        Files.createDirectories(testDir);
        write(testDir.resolve("Service0Test.java"), testClass(index, methodsPerCls));

        // A few deliberately huge classes: real code bases have generated or legacy monsters, and
        // they are where a per-method "split the whole file" pattern turns quadratic.
        for (int b = 0; b < bigFiles; b++) {
            write(pkgDir.resolve("Huge" + b + ".java"), hugeClass(index, b, methodsPerCls * 12));
        }
    }

    private String serviceClass(int module, int cls, int classesPerMod, int methods) {
        StringBuilder sb = new StringBuilder();
        sb.append("package com.example.m").append(module).append(";\n\n");
        sb.append("import org.slf4j.Logger;\nimport org.slf4j.LoggerFactory;\n");
        if (module > 0) {
            sb.append("import com.example.m").append(module - 1).append(".Service0;\n");
        }
        sb.append("\npublic class Service").append(cls).append(" {\n");
        sb.append("  private static final Logger log = LoggerFactory.getLogger(Service").append(cls)
                .append(".class);\n");
        sb.append("  private final int threshold = ").append(cls + 3).append(";\n");
        if (module > 0) {
            sb.append("  private final Service0 upstream = new Service0();\n");
        }
        for (int i = 0; i < methods; i++) {
            sb.append("  public int calc").append(i).append("(int input) {\n");
            sb.append("    int total = input + ").append(i).append(";\n");
            sb.append("    if (total > threshold) {\n");
            sb.append("      total = total - threshold;\n");
            sb.append("    }\n");
            sb.append("    for (int k = 0; k < 3; k++) {\n");
            sb.append("      total += k * ").append(i + 1).append(";\n");
            sb.append("    }\n");
            if (i > 0) {
                sb.append("    total += calc").append(i - 1).append("(total);\n");
            }
            if (module > 0) {
                sb.append("    total += upstream.calc").append(i).append("(total);\n");
            }
            int peer = (cls + 1) % classesPerMod;
            sb.append("    total += new Service").append(peer).append("().calc")
                    .append(i).append("(total);\n");
            sb.append("    log.debug(\"calc {}\", total);\n");
            sb.append("    return total;\n");
            sb.append("  }\n\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private String hugeClass(int module, int index, int methods) {
        StringBuilder sb = new StringBuilder();
        sb.append("package com.example.m").append(module).append(";\n\n");
        sb.append("public class Huge").append(index).append(" {\n");
        for (int i = 0; i < methods; i++) {
            sb.append("  public int step").append(i).append("(int v) {\n");
            sb.append("    int r = v;\n");
            sb.append("    if (r % 2 == 0) { r += ").append(i).append("; }\n");
            sb.append("    if (r > 100) { r -= 7; }\n");
            sb.append("    return r;\n");
            sb.append("  }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private String controllerClass(int module, int classesPerMod) {
        return "package com.example.m" + module + ";\n\n"
                + "import org.springframework.web.bind.annotation.*;\n\n"
                + "@RestController\n"
                + "@RequestMapping(\"/api/m" + module + "\")\n"
                + "public class Api" + module + "Controller {\n"
                + "  private final Service0 service = new Service0();\n\n"
                + "  @GetMapping(\"/items/{id}\")\n"
                + "  public int get(@PathVariable int id) {\n"
                + "    return service.calc0(id);\n"
                + "  }\n\n"
                + "  @PostMapping(\"/items\")\n"
                + "  public int create(@RequestBody int body) {\n"
                + "    return service.calc1(body);\n"
                + "  }\n"
                + "}\n";
    }

    private String mapperInterface(int module) {
        return "package com.example.m" + module + ";\n\n"
                + "import org.apache.ibatis.annotations.*;\n"
                + "import java.util.List;\n\n"
                + "@Mapper\n"
                + "public interface Repo" + module + " {\n"
                + "  List<String> selectAll();\n\n"
                + "  @Select(\"select id, name from t_item" + module + " where id = #{id}\")\n"
                + "  String selectById(long id);\n"
                + "}\n";
    }

    private String mapperXml(int module) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" "
                + "\"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n"
                + "<mapper namespace=\"com.example.m" + module + ".Repo" + module + "\">\n"
                + "  <select id=\"selectAll\" resultType=\"java.lang.String\">\n"
                + "    select id, name from t_item" + module + " where status = 1\n"
                + "  </select>\n"
                + "</mapper>\n";
    }

    private String configYml(int module) {
        return "server:\n  port: " + (9000 + module) + "\n"
                + "feature:\n  enabled: true\n  ratio: " + (module + 1) + "\n"
                + "datasource:\n  url: jdbc:mysql://localhost/db" + module + "\n"
                + "  username: user" + module + "\n";
    }

    private String testClass(int module, int methods) {
        return "package com.example.m" + module + ";\n\n"
                + "import org.junit.jupiter.api.Test;\n\n"
                + "class Service0Test {\n"
                + "  @Test\n"
                + "  void calc() {\n"
                + "    new Service0().calc0(1);\n"
                + "  }\n"
                + "}\n";
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
