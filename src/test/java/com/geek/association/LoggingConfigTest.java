package com.geek.association;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.geek.association.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日志落盘契约。
 *
 * 用户要的是"记录 7 天内的日志、放在单独目录、重要节点可查"。这三条都在配置文件里，
 * 而配置文件恰恰是最容易被顺手改坏、又没有编译期保护的东西：把 maxHistory 从 7 改成 30、
 * 或者把 fileNamePattern 的 archive/ 去掉，服务照常启动，只是磁盘慢慢被吃掉。
 * 所以这里把约定固化成断言，改配置就必须先改这里的理由。
 *
 * 只读文件、不启动 Spring 上下文：断言的是"配置写了什么"，不是"某次运行恰好写到了哪儿"。
 */
class LoggingConfigTest {

    private static final String LOGBACK = "/logback-spring.xml";

    @Test
    void logbackWritesIntoItsOwnDirectory() throws Exception {
        Document doc = parse(LOGBACK);

        Element logDirProperty = springProperty(doc, "LOG_DIR");
        assertNotNull(logDirProperty, "logback-spring.xml 必须从 code-association.log-dir 读取目录");
        assertEquals("code-association.log-dir", logDirProperty.getAttribute("source"));
        assertEquals("./logs", logDirProperty.getAttribute("defaultValue"),
                "Logback 的默认目录必须与 application.yml 的默认值一致，否则两处会指向不同目录");

        Element file = appender(doc, "FILE");
        String path = substitute(childText(file, "file"));
        assertTrue(path.startsWith("./logs/"), "主日志必须落在独立的日志目录下：" + path);
        assertTrue(path.endsWith("/code-association.log"),
                "文件名是 README / 故障排查文档的排障入口，不能随便改：" + path);
    }

    @Test
    void logbackKeepsSevenDaysAndArchivesCompressed() throws Exception {
        Document doc = parse(LOGBACK);
        for (String appenderName : new String[] {"FILE", "ERROR_FILE"}) {
            Element appender = appender(doc, appenderName);
            Element policy = firstChild(appender, "rollingPolicy");
            assertNotNull(policy, appenderName + " 必须有滚动策略，否则文件只增不减");
            assertEquals("ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy",
                    policy.getAttribute("class"));
            assertEquals("7", childText(policy, "maxHistory"), appenderName + " 只保留 7 天");
            assertEquals("true", childText(policy, "cleanHistoryOnStart"),
                    appenderName + " 停用一段时间后重启也要立刻清掉过期文件");
            assertTrue(childText(policy, "totalSizeCap").endsWith("MB"),
                    appenderName + " 需要总容量上限，避免单日暴涨吃满磁盘");
            String pattern = substitute(childText(policy, "fileNamePattern"));
            assertTrue(pattern.contains("/archive/"), "历史文件进 archive/，目录里只留当前文件：" + pattern);
            assertTrue(pattern.endsWith(".gz"), "历史文件压缩，7 天量级下体积差一个数量级：" + pattern);
            assertTrue(pattern.contains("%d{yyyy-MM-dd}"), "按天滚动：" + pattern);
        }
    }

    @Test
    void errorsGetTheirOwnFileSoTriageDoesNotStartWithGrepping() throws Exception {
        Document doc = parse(LOGBACK);
        Element errorFile = appender(doc, "ERROR_FILE");
        Element filter = firstChild(errorFile, "filter");
        assertNotNull(filter);
        assertEquals("ch.qos.logback.classic.filter.ThresholdFilter", filter.getAttribute("class"));
        assertEquals("ERROR", childText(filter, "level"));

        // 每份文件各接一个异步器。AsyncAppender 只接受一个 appender-ref，把两份文件挂在同一个
        // 异步器上时第二个会被静默忽略——错误日志变成永远空文件，而配置看上去完全正确。
        Element asyncFile = appender(doc, "ASYNC_FILE");
        Element asyncError = appender(doc, "ASYNC_ERROR");
        assertEquals(1, refsOf(asyncFile).size(), "一个 AsyncAppender 只能挂一个 appender");
        assertEquals(1, refsOf(asyncError).size(), "一个 AsyncAppender 只能挂一个 appender");
        assertEquals("FILE", refsOf(asyncFile).get(0));
        assertEquals("ERROR_FILE", refsOf(asyncError).get(0));

        assertTrue(refsInclude(root(doc), "CONSOLE"), "控制台仍然要有输出：前台调试与 start.sh 都靠它");
        assertTrue(refsInclude(root(doc), "ASYNC_FILE"));
        assertTrue(refsInclude(root(doc), "ASYNC_ERROR"));
    }

    @Test
    void patternCarriesWhatAnAnalysisNeeds() throws Exception {
        Document doc = parse(LOGBACK);
        String pattern = substitute(childText(appender(doc, "FILE"), "encoder", "pattern"));
        // 时间定位、级别过滤、线程聚合同一次分析、logger 定位来源、消息本体。
        // 级别用的是 %-5level（带宽度修饰），所以校验 level 这个词而不是 %level。
        for (String token : new String[] {"%d{", "level", "%thread", "%logger", "%msg"}) {
            assertTrue(pattern.contains(token), "行格式缺少 " + token + "：" + pattern);
        }
    }

    @Test
    void logDirectoryIsConfigurableAndDefaultsBesideTheDataDirectory() throws Exception {
        JsonNode yaml = new ObjectMapper(new YAMLFactory())
                .readTree(resource("/application.yml").readAllBytes());
        JsonNode logDir = yaml.path("code-association").path("log-dir");
        assertEquals("${LOG_DIR:./logs}", logDir.asText(),
                "容器里要能用 LOG_DIR 挂日志卷，默认值必须与 AppProperties 一致");

        AppProperties properties = new AppProperties();
        assertEquals("./logs", properties.getLogDir());
        assertTrue(properties.logPath().isAbsolute());
        assertTrue(properties.logPath().endsWith("logs"));

        // 级别是基线；DEBUG 只是排查"为什么没扫这个程序"时才开，不能默认打开。
        assertEquals("INFO", yaml.path("logging").path("level").path("com.geek.association").asText());
    }

    @Test
    void testsDoNotWriteIntoTheRealLogDirectory() {
        // mvn test 会起几十次 Spring 上下文；没有这份配置，./logs 会被测试日志灌满。
        // Logback 会优先加载 logback-test.xml。
        assertNotNull(resource("/logback-test.xml"), "测试必须有独立的 logback 配置");
    }

    @Test
    void theConventionIsDocumentedWhereOperatorsLook() throws Exception {
        Path doc = Path.of("docs/wiki/日志规范.md");
        assertTrue(Files.exists(doc), "日志约定必须有文档，否则事件名与保留期只活在代码里");
        String text = Files.readString(doc, StandardCharsets.UTF_8);
        for (String expected : new String[] {"logs/code-association.log", "logs/archive", "7 天",
                "event=analysis.done", "event=analysis.failed", "event=scan.done"}) {
            assertTrue(text.contains(expected), "日志规范缺少 " + expected);
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Resolves the variables the assertions care about.
     *
     * Not a general resolver: the file only uses LOG_DIR / APP_NAME for paths and LOG_PATTERN for the
     * line format, and asserting on resolved text is what catches "the property was emptied but the
     * reference to it is still there".
     */
    private static String substitute(String text) {
        String out = text;
        out = out.replace("${LOG_DIR}", "./logs");
        out = out.replace("${APP_NAME}", "code-association");
        out = out.replace("${LOG_PATTERN}",
                "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{40} - %msg%n");
        return out;
    }

    private static InputStream resource(String path) {
        InputStream in = LoggingConfigTest.class.getResourceAsStream(path);
        assertNotNull(in, "classpath 上找不到 " + path);
        return in;
    }

    private static Document parse(String classpath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        // 配置文件是本地的，不需要 DTD/外部实体；关掉可以少一类解析器攻击面。
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (InputStream in = resource(classpath)) {
            return factory.newDocumentBuilder().parse(in);
        }
    }

    private static Element appender(Document doc, String name) {
        NodeList appenders = doc.getElementsByTagName("appender");
        for (int i = 0; i < appenders.getLength(); i++) {
            Element element = (Element) appenders.item(i);
            if (name.equals(element.getAttribute("name"))) {
                return element;
            }
        }
        throw new AssertionError("logback-spring.xml 缺少 appender " + name);
    }

    private static Element springProperty(Document doc, String name) {
        NodeList properties = doc.getElementsByTagName("springProperty");
        for (int i = 0; i < properties.getLength(); i++) {
            Element element = (Element) properties.item(i);
            if (name.equals(element.getAttribute("name"))) {
                return element;
            }
        }
        return null;
    }

    private static Element root(Document doc) {
        return (Element) doc.getElementsByTagName("root").item(0);
    }

    private static Element firstChild(Element parent, String tag) {
        Node node = parent.getFirstChild();
        while (node != null) {
            if (node.getNodeType() == Node.ELEMENT_NODE && tag.equals(node.getNodeName())) {
                return (Element) node;
            }
            node = node.getNextSibling();
        }
        return null;
    }

    private static String childText(Element parent, String... path) {
        Element current = parent;
        for (String tag : path) {
            current = firstChild(current, tag);
            assertNotNull(current, "缺少 <" + tag + ">");
        }
        return current.getTextContent().strip();
    }

    private static boolean refsInclude(Element parent, String appenderName) {
        return refsOf(parent).contains(appenderName);
    }

    private static java.util.List<String> refsOf(Element parent) {
        java.util.List<String> names = new java.util.ArrayList<>();
        NodeList refs = parent.getElementsByTagName("appender-ref");
        for (int i = 0; i < refs.getLength(); i++) {
            names.add(((Element) refs.item(i)).getAttribute("ref"));
        }
        return names;
    }
}
