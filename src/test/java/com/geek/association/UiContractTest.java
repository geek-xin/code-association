package com.geek.association;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 界面契约：把 {@code docs/wiki/界面规范.md} 里的关键约定变成可执行的断言，防止规范与实现悄悄漂移。
 *
 * <p>这里不启动 Spring 上下文，也不经过 HTTP：直接读文件系统里的
 * {@code src/main/resources/static/{style.css,index.html,app.js}} 与 {@code docs/wiki/*.md}。
 * Maven surefire 的工作目录是项目根，所以下面的相对路径在 {@code mvn test} 里成立。
 *
 * <p>失败信息一律写清三件事：<b>哪个契约</b>、<b>期望什么</b>、<b>实际缺什么</b>。
 * 断言只描述代码事实；如果某条规范写错了，改规范而不是放宽这里的断言。
 */
@DisplayName("界面契约：界面规范.md ↔ static/{style.css,index.html,app.js}")
class UiContractTest {

    private static final Path STATIC_DIR = Path.of("src/main/resources/static");
    private static final Path WIKI_DIR = Path.of("docs/wiki");

    /** 规范第 7 章列出的组件根类名。每个都必须在样式表里有规则，并在骨架或脚本里有使用者。 */
    private static final List<String> COMPONENT_CLASSES = List.of(
            "card", "stat", "pill", "btn", "btn-primary", "btn-ghost", "btn-danger", "btn-sm",
            "tabs", "tab", "modal", "drawer", "toast", "empty", "callout", "stepper", "pager",
            "tag-chip", "sidebar", "workspace", "page-head", "page-title", "title-badge",
            "tr", "path", "timeline", "flow", "progress", "chip",
            // 规范 7.23：AI 审核必须与证据视觉分区，标注与正文各自有独立类名。
            "ai-text", "ai-foot", "ai-verified-note", "ai-status-row", "ai-presets");

    /** 规范 2.1：浅色 :root 是唯一令牌源，这些令牌必须在浅色块里全部有定义。 */
    private static final List<String> LIGHT_ROOT_TOKENS = List.of(
            "--primary", "--surface", "--surface-2", "--surface-3", "--text", "--text-2", "--text-3",
            "--border", "--border-strong", "--r-card", "--r-tile", "--r-control", "--r-pill",
            "--shadow-card", "--shadow-pop", "--ease", "--t-fast", "--t-base", "--sidebar-w", "--sidebar-w-collapsed",
            "--risk-critical", "--risk-high", "--risk-medium", "--risk-low", "--risk-info");

    /** 规范 2.1：深色块覆盖颜色类核心令牌。 */
    private static final List<String> DARK_MUST_OVERRIDE = List.of(
            "--surface", "--surface-2", "--surface-3", "--app-bg", "--text", "--text-2", "--text-3",
            "--border", "--primary", "--danger", "--success", "--warning");

    /** 规范 2.1 的纪律：深色块不重定义几何 / 动效 / 布局 / 字体令牌。 */
    private static final List<String> DARK_MUST_NOT_OVERRIDE = List.of(
            "--r-card", "--r-tile", "--r-control", "--r-pill", "--ease", "--t-fast", "--t-base",
            "--sidebar-w", "--sidebar-w-collapsed", "--font", "--mono");

    /** 规范 4.2 的字号阶梯（px）。 */
    private static final List<String> FONT_LADDER = List.of(
            "9.5", "10", "10.5", "11", "11.5", "12", "12.5", "13", "15", "16", "18", "20", "21", "27", "30");

    private static String css;
    private static String html;
    private static String js;
    private static List<CssRule> rules;
    private static Set<String> htmlClassTokens;
    private static List<String> jsClassFragments;

    @BeforeAll
    static void loadSources() {
        css = read(STATIC_DIR.resolve("style.css"));
        html = read(STATIC_DIR.resolve("index.html"));
        js = read(STATIC_DIR.resolve("app.js"));
        rules = parseRules(css);
        htmlClassTokens = classTokens(html);
        jsClassFragments = jsClassFragments(js);
    }

    // ------------------------------------------------------------------ 主题令牌

    @Nested
    @DisplayName("1. 主题令牌：双主题、单一令牌源（规范第 2 章）")
    class ThemeTokens {

        @Test
        @DisplayName("浅色 :root 声明了全部核心令牌")
        void lightRootDeclaresEveryCoreToken() {
            String light = tokenBlock(":root");
            List<String> missing = missingTokens(light, LIGHT_ROOT_TOKENS);
            assertTrue(missing.isEmpty(), () -> "主题契约（规范 2.1）：浅色 :root 是唯一令牌源，"
                    + "应声明 " + LIGHT_ROOT_TOKENS.size() + " 个核心令牌，实际缺少 " + missing.size()
                    + " 个：" + String.join("、", missing) + "。");
        }

        @Test
        @DisplayName("深色块覆盖了全部颜色类核心令牌")
        void darkRootCoversEveryColourToken() {
            String dark = tokenBlock(":root[data-theme=\"dark\"]");
            List<String> missing = missingTokens(dark, DARK_MUST_OVERRIDE);
            assertTrue(missing.isEmpty(), () -> "主题契约（规范 2.1）：深色块必须覆盖颜色类核心令牌，"
                    + "否则深色会话里这些表面仍是浅色值；实际缺少：" + String.join("、", missing) + "。");
        }

        @Test
        @DisplayName("深色块不重定义几何 / 动效 / 布局 / 字体令牌")
        void darkRootNeverTouchesGeometryMotionLayoutOrType() {
            String dark = tokenBlock(":root[data-theme=\"dark\"]");
            List<String> overridden = DARK_MUST_NOT_OVERRIDE.stream()
                    .filter(token -> declares(dark, token))
                    .toList();
            assertTrue(overridden.isEmpty(), () -> "主题纪律（规范 2.1）：深色块只覆盖颜色令牌，"
                    + "不修改任何尺寸、动效或字体；实际重定义了：" + String.join("、", overridden)
                    + "。加组件只写一次布局，改深色只改令牌。");
        }

        @Test
        @DisplayName("深色主题不新增选择器（只有一个 :root[data-theme=\"dark\"] 块）")
        void darkThemeAddsNoSelectorsOfItsOwn() {
            List<String> selectors = rules.stream()
                    .filter(rule -> rule.selector().contains("data-theme=\"dark\""))
                    .map(CssRule::selector)
                    .distinct()
                    .toList();
            assertTrue(selectors.size() == 1 && selectors.get(0).equals(":root[data-theme=\"dark\"]"),
                    () -> "主题纪律（规范 2.1）：深色主题只允许一个 :root[data-theme=\"dark\"] 块，"
                            + "实际出现的选择器是：" + String.join("；", selectors) + "。");
        }

        @Test
        @DisplayName("组件规则里没有十六进制颜色字面量")
        void componentRulesNeverHardcodeHexColours() {
            Pattern hex = Pattern.compile("#[0-9a-fA-F]{3,8}\\b");
            List<String> offenders = new ArrayList<>();
            for (CssRule rule : rules) {
                if (rule.hasSelector(":root") || rule.hasSelector(":root[data-theme=\"dark\"]")) {
                    continue;
                }
                Matcher matcher = hex.matcher(rule.body());
                while (matcher.find()) {
                    offenders.add(rule.selector() + " → " + matcher.group());
                }
            }
            assertTrue(offenders.isEmpty(), () -> "色彩纪律（规范 3.1）：颜色只有一个来源，"
                    + "组件规则里禁止写十六进制字面量；实际发现 " + offenders.size() + " 处："
                    + String.join("；", offenders) + "。");
        }
    }

    // ------------------------------------------------------------------ 排版

    @Nested
    @DisplayName("2. 排版：字号阶梯与全大写小标签（规范 4.2 / 4.3）")
    class Typography {

        @Test
        @DisplayName("阶梯里的每个字号都能在样式表里找到 font-size 声明")
        void everyStepOfTheFontLadderIsDeclared() {
            List<String> missing = FONT_LADDER.stream()
                    .filter(size -> !Pattern.compile("font-size\\s*:\\s*" + Pattern.quote(size) + "px").matcher(css).find())
                    .toList();
            assertTrue(missing.isEmpty(), () -> "字号阶梯契约（规范 4.2）：表里的每个字号都应出现在 style.css 的 "
                    + "font-size 声明中，实际缺少 " + String.join("、", missing) + " px。");
        }

        @Test
        @DisplayName("规范 4.2 的代表选择器真的用了表里的字号")
        void representativeSelectorsUseTheLadderedSize() {
            Map<String, String> expected = new LinkedHashMap<>();
            expected.put(".upd-version-num", "30");
            expected.put(".page-title", "27");
            expected.put(".stat .value", "21");
            expected.put(".head-metric .value", "20");
            expected.put(".ring-label .pct", "20");
            expected.put(".modal-title", "18");
            expected.put(".sidebar-context h2", "16");
            expected.put(".card-title", "15");
            expected.put(".flow-value", "15");
            expected.put("body", "13");
            expected.put(".btn", "12.5");
            expected.put(".nav-item", "12.5");
            expected.put(".page-sub", "12.5");
            expected.put(".row-desc", "12");
            expected.put(".card-sub", "11.5");
            expected.put(".stat .label", "11.5");
            expected.put(".meta-row", "11.5");
            expected.put(".pill", "11");
            expected.put(".chip", "11");
            expected.put(".tag-chip", "11");
            expected.put(".eyebrow", "10.5");
            expected.put(".nav-group-title", "10.5");
            expected.put(".head-metric .label", "10.5");
            expected.put(".search kbd", "10");
            expected.put(".ring-label .cap", "10");
            expected.put(".commit-merge", "9.5");

            List<String> wrong = new ArrayList<>();
            expected.forEach((selector, size) -> {
                List<CssRule> hits = rulesFor(selector);
                if (hits.isEmpty()) {
                    wrong.add(selector + "（样式表里没有这条规则）");
                    return;
                }
                Pattern pattern = Pattern.compile("font-size\\s*:\\s*" + Pattern.quote(size) + "px");
                if (hits.stream().noneMatch(rule -> pattern.matcher(rule.body()).find())) {
                    wrong.add(selector + "（期望 " + size + "px，实际 " + fontSizeOf(hits) + "）");
                }
            });
            assertTrue(wrong.isEmpty(), () -> "字号阶梯契约（规范 4.2）：代表选择器的字号必须与阶梯表一致，"
                    + "实际不符 " + wrong.size() + " 处：" + String.join("；", wrong) + "。");
        }

        @Test
        @DisplayName("五个全大写小标签共用同一套配方：uppercase + 0.06~0.09em + 600 + --text-3")
        void uppercaseLabelsShareTheSameRecipe() {
            List<String> selectors = List.of(
                    ".eyebrow", ".nav-group-title", ".tr.head", ".section-label", ".flow-label");
            List<String> broken = new ArrayList<>();
            for (String selector : selectors) {
                String body = firstBody(selector);
                List<String> faults = new ArrayList<>();
                if (!body.contains("text-transform: uppercase")) {
                    faults.add("缺 text-transform: uppercase");
                }
                if (!body.contains("font-weight: 600")) {
                    faults.add("缺 font-weight: 600");
                }
                if (!body.contains("color: var(--text-3)")) {
                    faults.add("缺 color: var(--text-3)");
                }
                Double spacing = letterSpacingEm(body);
                if (spacing == null || spacing < 0.06 || spacing > 0.09) {
                    faults.add("letter-spacing 期望 0.06~0.09em，实际 " + (spacing == null ? "未声明" : spacing + "em"));
                }
                if (!faults.isEmpty()) {
                    broken.add(selector + "：" + String.join("、", faults));
                }
            }
            assertTrue(broken.isEmpty(), () -> "排版纪律（规范 4.3）：全大写小标签必须同时满足 "
                    + "uppercase + letter-spacing 0.06~0.09em + font-weight 600 + --text-3，实际偏差："
                    + String.join("；", broken) + "。");
        }

        @Test
        @DisplayName("页头指标标签是大写弱色小标签，但只有 0.04em 字距、没有 600 字重（规范 4.3 的配方比代码严）")
        void headMetricLabelIsMutedUppercaseWithoutTheFullRecipe() {
            // 代码事实：.head-metric .label = 10.5px + uppercase + --text-3 + letter-spacing 0.04em，
            // 没有 font-weight: 600。规范 4.3 把 .head-metric .label 列进"全大写小标签"配方
            // （uppercase + 0.06~0.09em + 600 + --text-3），这一句与代码不符——需要改的是规范，
            // 不是这条断言：这里如实固定代码现状，避免测试假装实现已经满足。
            String body = firstBody(".head-metric .label");
            assertTrue(body.contains("text-transform: uppercase"),
                    () -> "排版契约（规范 4.3）：.head-metric .label 应是大写小标签，实际规则体为：" + body.trim());
            assertTrue(body.contains("color: var(--text-3)"),
                    () -> "排版契约（规范 4.3）：.head-metric .label 应使用 --text-3，实际规则体为：" + body.trim());
            assertTrue(body.contains("font-size: 10.5px"),
                    () -> "排版契约（规范 4.2）：.head-metric .label 应为 10.5px，实际规则体为：" + body.trim());
            Double spacing = letterSpacingEm(body);
            assertTrue(spacing != null && spacing == 0.04,
                    () -> "排版契约（规范 4.3 与代码不一致）：规范要求全大写小标签 letter-spacing 0.06~0.09em，"
                            + "而 .head-metric .label 实际是 " + (spacing == null ? "未声明" : spacing + "em")
                            + "；规范那句需要修正为「页头指标标签 0.04em」或把代码改成 0.06em。实际规则体为：" + body.trim());
        }
    }

    // ------------------------------------------------------------------ 布局

    @Nested
    @DisplayName("3. 布局：骨架、栅格与断点（规范第 5 章）")
    class Layout {

        @Test
        @DisplayName("外壳两列网格由 --sidebar-w 驱动")
        void shellGridIsDrivenBySidebarToken() {
            String app = firstBody(".app");
            assertTrue(app.contains("grid-template-columns: var(--sidebar-w) minmax(0, 1fr)"),
                    () -> "布局契约（规范 5.1）：.app 必须是 "
                            + "grid-template-columns: var(--sidebar-w) minmax(0, 1fr)，实际规则体为："
                            + app.trim());
            assertTrue(app.contains("height: calc(100vh - 28px)"),
                    () -> "布局契约（规范 5.1）：.app 高度应为 calc(100vh - 28px)，实际规则体为：" + app.trim());
            assertTrue(app.contains("border-radius: 22px"),
                    () -> "布局契约（规范 5.1 / 6.1）：.app 圆角应为 22px，实际规则体为：" + app.trim());
            assertTrue(app.contains("overflow: hidden"),
                    () -> "布局契约（规范 5.1）：页面本身不滚，只有 .workspace 滚，.app 必须 overflow: hidden，"
                            + "实际规则体为：" + app.trim());
        }

        @Test
        @DisplayName("侧栏默认收起，展开是记忆化选择")
        void sidebarStartsCollapsedUnlessTheBrowserSaysOtherwise() {
            assertTrue(html.contains("<div class=\"app nav-collapsed\">"),
                    () -> "布局契约（规范 7.3）：第一帧必须是收起态，index.html 的 .app 应带 nav-collapsed。");
            assertTrue(js.contains("saved ? saved === 'collapsed' : true"),
                    () -> "布局契约（规范 7.3）：没有保存值时 initNav 必须默认收起。");
        }

        @Test
        @DisplayName("收起侧栏：轨道换成图标宽度，文字标签移出布局")
        void collapsingTheSidebarUsesTheIconTrackAndHidesLabels() {
            String collapsed = firstBody(".app.nav-collapsed");
            assertTrue(collapsed.contains("grid-template-columns: var(--sidebar-w-collapsed) minmax(0, 1fr)"),
                    () -> "布局契约（规范 5.1 / 7.3）：收起态应换成 --sidebar-w-collapsed 轨道，实际规则体为："
                            + collapsed.trim());
            String hidden = firstBody(".app.nav-collapsed .side-label");
            assertTrue(hidden.contains("display: none"),
                    () -> "无障碍契约（规范 7.3）：收起态的文字标签必须移出布局，实际规则体为：" + hidden.trim());
        }

        @Test
        @DisplayName("卡片栅格都带 minmax(0, …)，页面最大宽与工作区内边距符合尺寸表")
        void everyCardGridKeepsMinmaxZero() {
            List<String> missing = new ArrayList<>();
            for (String selector : List.of(".grid-2", ".grid-2-even", ".grid-3", ".stats", ".flow")) {
                List<CssRule> hits = rulesFor(selector);
                if (hits.isEmpty()) {
                    missing.add(selector + "（没有规则）");
                } else if (hits.stream().noneMatch(rule -> rule.body().contains("minmax(0"))) {
                    missing.add(selector + "（列定义里没有 minmax(0, …)）");
                }
            }
            assertTrue(missing.isEmpty(), () -> "布局契约（规范 5.2）：minmax(0, …) 不是可选项，"
                    + "少了它网格项会被最小内容宽度撑破容器；实际问题：" + String.join("；", missing) + "。");

            String page = firstBody(".page");
            assertTrue(page.contains("max-width: 1460px"),
                    () -> "布局契约（规范 5.3）：.page 最大宽应为 1460px，实际规则体为：" + page.trim());
            String workspace = firstBody(".workspace");
            assertTrue(workspace.contains("padding: 26px 30px 48px"),
                    () -> "布局契约（规范 5.3）：.workspace 内边距应为 26px 30px 48px，实际规则体为：" + workspace.trim());
            assertTrue(workspace.contains("overflow-y: auto"),
                    () -> "布局契约（规范 5.5）：外壳不滚、.workspace 滚，实际规则体为：" + workspace.trim());
        }

        @Test
        @DisplayName("五个断点 1400 / 1180 / 860 / 1024 / 720 都存在")
        void fiveBreakpointsExist() {
            List<String> missing = new ArrayList<>();
            for (String width : List.of("1400", "1180", "860", "1024", "720")) {
                Pattern pattern = Pattern.compile("@media\\s*\\(max-width:\\s*" + width + "px\\s*\\)");
                if (!pattern.matcher(css).find()) {
                    missing.add(width + "px");
                }
            }
            assertTrue(missing.isEmpty(), () -> "响应式契约（规范 5.4）：五个断点是固定尺寸的对应物，"
                    + "缺一不可；实际缺少 " + String.join("、", missing) + "。");
        }

        @Test
        @DisplayName("布局断点改的是骨架：统计块列数、菜单宽、标题字号、单列化")
        void layoutBreakpointsChangeTheSkeleton() {
            String at1400 = "@media (max-width: 1400px)";
            String at1180 = "@media (max-width: 1180px)";
            String at860 = "@media (max-width: 860px)";
            String at1024 = "@media (max-width: 1024px)";
            String at720 = "@media (max-width: 720px)";

            assertTrue(bodyIn(at1400, ".stats").contains("repeat(2"),
                    () -> "响应式契约（规范 5.4）：≤1400px 统计块应 4→2 列，实际为："
                            + bodyIn(at1400, ".stats").trim());
            assertTrue(bodyIn(at1400, ".grid-2").contains("1.35fr"),
                    () -> "响应式契约（规范 5.4）：≤1400px .grid-2 主栏比例应 1.5→1.35，实际为："
                            + bodyIn(at1400, ".grid-2").trim());

            String app1180 = bodyIn(at1180, ".app");
            assertTrue(app1180.contains("--sidebar-w: 248px"),
                    () -> "响应式契约（规范 5.4）：≤1180px 侧栏宽应 288→248，实际为：" + app1180.trim());
            assertTrue(app1180.contains("min-width: 0"),
                    () -> "响应式契约（规范 5.4）：≤1180px .app 必须补 min-width: 0，"
                            + "否则 1180px 下限把右列裁掉且没有滚动条；实际为：" + app1180.trim());
            assertTrue(bodyIn(at1180, ".page-title").contains("font-size: 22px"),
                    () -> "响应式契约（规范 5.4）：≤1180px 页面标题应 27→22px，实际为："
                            + bodyIn(at1180, ".page-title").trim());
            for (String selector : List.of(".grid-2", ".grid-2-even", ".grid-3", ".flow")) {
                assertTrue(bodyIn(at1180, selector).contains("minmax(0, 1fr)"),
                        () -> "响应式契约（规范 5.4）：≤1180px " + selector + " 应改单列，实际为："
                                + bodyIn(at1180, selector).trim());
            }

            assertTrue(bodyIn(at860, ".stats").contains("minmax(0, 1fr)"),
                    () -> "响应式契约（规范 5.4）：≤860px 统计块应单列，实际为："
                            + bodyIn(at860, ".stats").trim());
            assertTrue(bodyIn(at860, ".app").contains("--sidebar-w: 236px"),
                    () -> "响应式契约（规范 5.3）：≤860px 侧栏宽应 236px，实际为："
                            + bodyIn(at860, ".app").trim());
            assertTrue(bodyIn(at860, ".tr .cell.optional").contains("display: none"),
                    () -> "响应式契约（规范 5.4）：≤860px 应隐藏 .tr .cell.optional，实际为："
                            + bodyIn(at860, ".tr .cell.optional").trim());

            assertTrue(bodyIn(at1024, ".upd-version-num").contains("font-size: 25px"),
                    () -> "响应式契约（规范 5.4）：≤1024px 版本号应 30→25px，实际为："
                            + bodyIn(at1024, ".upd-version-num").trim());
            assertTrue(bodyIn(at720, ".commit-meta").contains("display: none"),
                    () -> "响应式契约（规范 5.4）：≤720px 提交范围行应去掉 meta 列，实际为："
                            + bodyIn(at720, ".commit-meta").trim());
        }
    }

    // ------------------------------------------------------------------ 几何与阴影

    @Nested
    @DisplayName("4. 几何：圆角层级对应容器层级（规范 6.1）")
    class Geometry {

        @Test
        @DisplayName("卡片 / 统计块 / 控件 / 徽章各自使用对应层级的圆角令牌")
        void radiusTokensAreUsedByTheComponentsThatOwnTheLevel() {
            Map<String, String> expected = new LinkedHashMap<>();
            expected.put(".card", "border-radius: var(--r-card)");
            expected.put(".stat", "border-radius: var(--r-tile)");
            expected.put(".btn", "border-radius: var(--r-control)");
            expected.put(".pill", "border-radius: var(--r-pill)");
            expected.put(".modal", "border-radius: 18px");
            expected.put(".app", "border-radius: 22px");

            List<String> wrong = new ArrayList<>();
            expected.forEach((selector, declaration) -> {
                List<CssRule> hits = rulesFor(selector);
                if (hits.isEmpty()) {
                    wrong.add(selector + "（没有规则）");
                } else if (hits.stream().noneMatch(rule -> rule.body().contains(declaration))) {
                    wrong.add(selector + "（期望 " + declaration + "）");
                }
            });
            assertTrue(wrong.isEmpty(), () -> "几何契约（规范 6.1）：越大越外层的容器圆角越大，"
                    + "把卡片做成 8px 而按钮做成 16px 会让层级读反；实际不符：" + String.join("；", wrong) + "。");
        }
    }

    // ------------------------------------------------------------------ 组件类名

    @Nested
    @DisplayName("5. 组件类名双向契约（规范第 7 章）")
    class ComponentClasses {

        @Test
        @DisplayName("每个组件类名在 style.css 里都有规则")
        void everyComponentHasAStyleRule() {
            List<String> missing = COMPONENT_CLASSES.stream()
                    .filter(name -> !hasClassRule(name))
                    .toList();
            assertTrue(missing.isEmpty(), () -> "组件契约（规范第 7 章）：规范当作组件描述的类名必须在 style.css 里"
                    + "有对应规则，实际缺少 " + missing.size() + " 个："
                    + String.join("、", missing.stream().map(name -> "." + name).toList()) + "。");
        }

        @Test
        @DisplayName("每个组件类名在 index.html 或 app.js 里都有使用者")
        void everyComponentHasAUser() {
            List<String> unused = COMPONENT_CLASSES.stream()
                    .filter(name -> !htmlClassTokens.contains(name) && !usedInJs(name))
                    .toList();
            assertTrue(unused.isEmpty(), () -> "组件契约（规范第 7 章）：样式表里的组件必须真的被骨架或脚本使用，"
                    + "否则规范描述的是一个已经不存在（或拼错）的组件；实际无使用者 " + unused.size()
                    + " 个：" + String.join("、", unused.stream().map(name -> "." + name).toList()) + "。");
        }
    }

    // ------------------------------------------------------------------ 无障碍

    @Nested
    @DisplayName("6. 无障碍：焦点、动效、ARIA（规范第 9 / 10 章）")
    class Accessibility {

        @Test
        @DisplayName("键盘焦点始终可见：:focus-visible 有 2px 主色描边与外扩")
        void focusVisibleIsAlwaysDrawn() {
            String body = firstBody(":focus-visible");
            assertTrue(body.contains("outline: 2px solid var(--primary)"),
                    () -> "无障碍契约（规范 10.1）：:focus-visible 应有 "
                            + "outline: 2px solid var(--primary)，实际规则体为：" + body.trim());
            assertTrue(body.contains("outline-offset: 2px"),
                    () -> "无障碍契约（规范 10.1）：:focus-visible 应有 outline-offset: 2px，实际规则体为：" + body.trim());
            assertTrue(body.contains("border-radius: 8px"),
                    () -> "无障碍契约（规范 10.1）：:focus-visible 应有 border-radius: 8px，实际规则体为：" + body.trim());
            assertTrue(css.contains(":focus:not(:focus-visible)"),
                    () -> "无障碍契约（规范 10.1）：鼠标点击不应显示焦点环，style.css 里找不到 "
                            + ":focus:not(:focus-visible) 规则。");
        }

        @Test
        @DisplayName("prefers-reduced-motion 把动画与过渡压到 0.01ms、迭代 1 次")
        void reducedMotionCollapsesEveryAnimation() {
            String context = "@media (prefers-reduced-motion: reduce)";
            List<CssRule> blocks = rulesIn(context);
            assertFalse(blocks.isEmpty(), () -> "动效契约（规范 9.4）：style.css 里没有 " + context + " 媒体查询。");
            assertTrue(css.contains("animation-duration: 0.01ms"),
                    () -> "动效契约（规范 9.4）：降低动效时动画时长应压到 0.01ms，实际没有该声明。");
            assertTrue(css.contains("transition-duration: 0.01ms"),
                    () -> "动效契约（规范 9.4）：降低动效时过渡时长应压到 0.01ms，实际没有该声明。");
            assertTrue(css.contains("animation-iteration-count: 1"),
                    () -> "动效契约（规范 9.4）：降低动效时迭代次数应为 1，实际没有该声明。");
        }

        @Test
        @DisplayName("侧栏是带 aria-label 的主导航")
        void sidebarIsALabelledNavigation() {
            assertTrue(Pattern.compile("<nav[^>]*class=\"sidebar\"[^>]*aria-label=\"主导航\"").matcher(html).find(),
                    () -> "ARIA 契约（规范 10.3）：<nav class=\"sidebar\"> 必须带 aria-label=\"主导航\"，"
                            + "index.html 里没有找到这样的标签。");
        }

        @Test
        @DisplayName("主题按钮用 aria-pressed 反映当前是否为深色")
        void themeButtonPublishesItsPressedState() {
            String tag = tagWithId("theme-toggle");
            assertTrue(Pattern.compile("aria-pressed=\"(true|false)\"").matcher(tag).find(),
                    () -> "ARIA 契约（规范 10.3）：#theme-toggle 必须有 aria-pressed，实际标签为：" + tag.trim());
            assertTrue(js.contains("btn.setAttribute('aria-pressed'"),
                    () -> "ARIA 契约（规范 10.3）：切换主题时 app.js 必须更新 #theme-toggle 的 aria-pressed，"
                            + "实际找不到 setAttribute('aria-pressed', …) 的调用。");
        }

        @Test
        @DisplayName("收起 / 展开按钮都发布 aria-expanded 与 aria-controls=\"sidenav\"")
        void bothNavButtonsPublishExpandedAndControls() {
            for (String id : List.of("nav-collapse", "nav-expand")) {
                String tag = tagWithId(id);
                assertTrue(tag.contains("aria-expanded="),
                        () -> "ARIA 契约（规范 10.3）：#" + id + " 必须有 aria-expanded，实际标签为：" + tag.trim());
                assertTrue(tag.contains("aria-controls=\"sidenav\""),
                        () -> "ARIA 契约（规范 10.3）：#" + id + " 必须有 aria-controls=\"sidenav\"，实际标签为：" + tag.trim());
            }
            assertTrue(js.contains("setAttribute('aria-expanded'"),
                    () -> "ARIA 契约（规范 10.3 / 7.3）：收起态必须同步两个按钮的 aria-expanded，"
                            + "app.js 里找不到 setAttribute('aria-expanded', …) 的调用。");
        }

        @Test
        @DisplayName("每个弹窗 / 抽屉都是 role=dialog + aria-modal + aria-labelledby")
        void everyDialogIsModalAndLabelled() {
            List<String> dialogs = new ArrayList<>();
            Matcher tags = Pattern.compile("<[a-zA-Z][^>]*>").matcher(html);
            while (tags.find()) {
                if (tags.group().contains("role=\"dialog\"")) {
                    dialogs.add(tags.group());
                }
            }
            assertTrue(dialogs.size() >= 5,
                    () -> "ARIA 契约（规范 10.3 / 7.16）：弹窗与抽屉都应是 role=\"dialog\"，"
                            + "index.html 里只找到 " + dialogs.size() + " 个。");
            for (String tag : dialogs) {
                String compact = tag.trim().replaceAll("\\s+", " ");
                assertTrue(compact.contains("aria-modal=\"true\""),
                        () -> "ARIA 契约（规范 10.3）：弹窗缺少 aria-modal=\"true\"，实际标签为：" + compact);
                assertTrue(compact.contains("aria-labelledby="),
                        () -> "ARIA 契约（规范 10.3）：弹窗缺少 aria-labelledby，实际标签为：" + compact);
            }
        }

        @Test
        @DisplayName("进度 / 更新条是 aria-live=\"polite\" 的活区")
        void liveRegionsExistForProgress() {
            for (String id : List.of("upd-progress", "upd-restart")) {
                String tag = tagWithId(id);
                assertTrue(tag.contains("aria-live=\"polite\""),
                        () -> "ARIA 契约（规范 10.3）：#" + id + " 必须是 aria-live=\"polite\" 的活区，"
                                + "实际标签为：" + tag.trim());
            }
        }

        @Test
        @DisplayName("只在 hover 显现的行内动作在触屏上常驻")
        void hoverOnlyActionsStayVisibleOnTouch() {
            for (String selector : List.of(".tr .row-delete", ".tr .row-edit")) {
                List<CssRule> hits = rulesFor(selector);
                assertTrue(hits.stream().anyMatch(rule -> rule.body().contains("opacity: 0")),
                        () -> "组件契约（规范 7.10）：" + selector + " 默认应 opacity: 0（hover / focus 才显现）。");
            }
            List<CssRule> touch = rulesIn("@media (hover: none)");
            assertTrue(touch.stream().anyMatch(rule -> rule.hasSelector(".tr .row-delete")
                            && rule.body().contains("opacity: 1")),
                    () -> "无障碍契约（规范 10.4）：触屏没有 hover，@media (hover: none) 下 "
                            + ".tr .row-delete 必须常驻（opacity: 1），实际没有这条规则。");
            assertTrue(touch.stream().anyMatch(rule -> rule.hasSelector(".tr .row-edit")
                            && rule.body().contains("opacity: 1")),
                    () -> "无障碍契约（规范 10.4）：@media (hover: none) 下 .tr .row-edit 必须常驻，实际没有这条规则。");
        }
    }

    // ------------------------------------------------------------------ 首帧防闪

    @Nested
    @DisplayName("7. 首帧防闪：样式表之前解析主题（规范 2.2）")
    class FirstPaint {

        @Test
        @DisplayName("head 里在 <link rel=stylesheet> 之前读 localStorage 'ca.theme' 并写 data-theme")
        void themeIsResolvedBeforeTheStylesheetLoads() {
            int headEnd = html.indexOf("</head>");
            int script = html.indexOf("localStorage.getItem('ca.theme')");
            Matcher stylesheet = Pattern.compile("<link[^>]*rel=\"stylesheet\"[^>]*href=\"/style[.]css\"[^>]*>").matcher(html);
            int link = stylesheet.find() ? stylesheet.start() : -1;
            assertTrue(script > -1, () -> "首帧契约（规范 2.2）：index.html 的内联脚本必须读取 localStorage 的 "
                    + "'ca.theme'，实际找不到这段代码。");
            assertTrue(link > -1, () -> "首帧契约：index.html 里找不到加载 /style.css 的 "
                    + "<link rel=\"stylesheet\" href=\"/style.css\">。");
            assertTrue(script < link,
                    () -> "首帧契约（规范 2.2）：主题解析脚本必须在样式表之前执行，否则深色会话会闪白；"
                            + "实际脚本位置 " + script + " 晚于样式表位置 " + link + "。");
            assertTrue(headEnd > link && headEnd > script,
                    () -> "首帧契约（规范 2.2）：首帧脚本必须内联在 <head> 里，实际 </head> 位置为 " + headEnd + "。");
            assertTrue(html.contains("setAttribute('data-theme'"),
                    () -> "首帧契约（规范 2.2）：首帧脚本必须写入 data-theme，实际找不到 setAttribute('data-theme', …)。");
            assertTrue(html.contains("data-theme-pref"),
                    () -> "首帧契约（规范 2.2）：data-theme-pref 必须始终记录三态原值，实际 index.html 里没有这个属性。");
        }
    }

    // ------------------------------------------------------------------ 主题运行时

    @Nested
    @DisplayName("8. 主题运行时：存储键与画布取色（规范 2.2 / 2.3）")
    class ThemeRuntime {

        @Test
        @DisplayName("偏好存在 localStorage 的 ca.theme，三态由 data-theme-pref 记录")
        void preferenceIsStoredUnderCaTheme() {
            assertTrue(js.contains("'ca.theme'"),
                    () -> "主题契约（规范 2.2）：app.js 必须用 localStorage 键 'ca.theme'，实际找不到该字面量。");
            assertTrue(js.contains("data-theme-pref"),
                    () -> "主题契约（规范 2.2）：app.js 必须写 data-theme-pref（三态原值），实际找不到该属性名。");
            assertTrue(js.contains("prefers-color-scheme"),
                    () -> "主题契约（规范 2.2）：system 偏好必须跟随 prefers-color-scheme，实际找不到该媒体查询。");
        }

        @Test
        @DisplayName("依赖图颜色运行时从令牌读，主题切换后重建图")
        void graphColoursAreReadFromTokensAtRuntime() {
            assertTrue(js.contains("function themeColor("),
                    () -> "主题契约（规范 2.3）：app.js 必须有 themeColor(name, fallback) 读取令牌计算值，"
                            + "实际找不到该函数。");
            assertTrue(js.contains("getComputedStyle(document.documentElement).getPropertyValue(name)"),
                    () -> "主题契约（规范 2.3）：themeColor 必须用 getComputedStyle 读令牌计算值，"
                            + "不能在 JS 里硬编码颜色；实际实现不是这样。");
            assertTrue(js.contains("renderMapsFor("),
                    () -> "主题契约（规范 2.3）：主题切换后必须重建 cytoscape 场景，app.js 里找不到 renderMapsFor( 调用。");
        }
    }

    // ------------------------------------------------------------------ 规范与代码同源

    @Nested
    @DisplayName("9. 规范与代码同源（文档索引与映射）")
    class SpecStaysInSync {

        @Test
        @DisplayName("界面规范.md 存在，且同时提到关键令牌与关键类名")
        void specDocumentExists() {
            String spec = read(WIKI_DIR.resolve("界面规范.md"));
            List<String> missing = new ArrayList<>();
            for (String needle : List.of("--primary", "--sidebar-w", "--sidebar-w-collapsed", "--ease",
                    ".btn", ".card", ".sidebar", ".workspace")) {
                if (!spec.contains(needle)) {
                    missing.add(needle);
                }
            }
            assertTrue(missing.isEmpty(), () -> "同源契约：docs/wiki/界面规范.md 必须与代码同源，"
                    + "关键令牌 / 类名应出现在文档里，实际缺少：" + String.join("、", missing) + "。");
        }

        @Test
        @DisplayName("规范里提到的每个令牌都真的在 :root 里有定义")
        void everyTokenTheSpecMentionsExists() {
            String spec = read(WIKI_DIR.resolve("界面规范.md"));
            String light = tokenBlock(":root");
            Set<String> mentioned = new LinkedHashSet<>();
            Matcher matcher = Pattern.compile("--[a-z][a-z0-9-]*").matcher(spec);
            while (matcher.find()) {
                String token = matcher.group();
                int after = matcher.end();
                // 跳过 "--toast-*" 这类通配写法与 "--" 之类的前缀片段
                if (after < spec.length() && spec.charAt(after) == '*') {
                    continue;
                }
                mentioned.add(token);
            }
            List<String> missing = mentioned.stream().filter(token -> !declares(light, token)).toList();
            assertTrue(missing.isEmpty(), () -> "同源契约（规范 3.1）：规范里写到的令牌必须真的在 style.css 的 :root 里"
                    + "有定义，否则文档描述的是一个不存在的令牌；实际缺少：" + String.join("、", missing) + "。");
        }

        @Test
        @DisplayName("界面实现映射.md 存在，并指向 style.css / index.html / app.js")
        void mappingDocumentPointsAtTheThreeStaticFiles() {
            Path mappingPath = WIKI_DIR.resolve("界面实现映射.md");
            assertTrue(Files.isRegularFile(mappingPath),
                    () -> "同源契约（规范 12 章验收清单）：docs/wiki/界面实现映射.md 是规范到代码的映射层，必须存在；"
                            + "实际路径 " + mappingPath.toAbsolutePath() + " 不存在。");
            String mapping = read(mappingPath);
            List<String> missing = new ArrayList<>();
            for (String file : List.of("style.css", "index.html", "app.js")) {
                if (!mapping.contains(file)) {
                    missing.add(file);
                }
            }
            assertTrue(missing.isEmpty(), () -> "同源契约（规范 12 章验收清单）：docs/wiki/界面实现映射.md 是"
                    + "规范到代码的映射层，必须提到 " + String.join(" / ", List.of("style.css", "index.html", "app.js"))
                    + "，实际缺少：" + String.join("、", missing) + "。");
        }

        @Test
        @DisplayName("docs/wiki/index.md 与 docs/wiki/索引.md 都链接到界面规范")
        void bothWikiIndexesLinkTheSpec() {
            for (String index : List.of("index.md", "索引.md")) {
                String content = read(WIKI_DIR.resolve(index));
                assertTrue(content.contains("[界面规范](界面规范)"),
                        () -> "文档索引契约：docs/wiki/" + index + " 必须链接到 [界面规范](界面规范)，"
                                + "否则改界面的人找不到唯一权威规范。");
            }
        }
    }

    // ------------------------------------------------------------------ 图标雪碧图

    @Nested
    @DisplayName("10. 图标雪碧图：脚本引用的每个 #i-* 都有定义（规范 7.10）")
    class IconSprite {

        @Test
        @DisplayName("app.js 里 icon('i-…') 引用的每个 id 都在 index.html 的 sprite 里有 symbol")
        void everyIconTheScriptReferencesIsDefinedInTheSprite() {
            Set<String> defined = spriteSymbolIds(html);
            assertFalse(defined.isEmpty(),
                    () -> "图标契约（规范 7.10）：index.html 里应有由 <symbol id=\"i-…\"> 组成的雪碧图，"
                            + "实际一个 symbol 都没找到。");
            List<String> referenced = new ArrayList<>(iconIdsUsedInJs(js));
            List<String> missing = referenced.stream().filter(id -> !defined.contains(id)).toList();
            assertTrue(missing.isEmpty(),
                    () -> "图标契约（规范 7.10）：app.js 用 icon('i-…') 引用的 id 必须都在 index.html 的 "
                            + "<symbol> 里定义，否则 <use href=\"#…\"> 命中不到任何绘制内容，按钮会渲染成看不见的"
                            + "空白（代码源行的编辑按钮就是这样消失的）；实际引用 " + referenced.size() + " 个 id，"
                            + "缺少定义 " + missing.size() + " 个：" + String.join("、", missing) + "。");
        }
    }

    // ------------------------------------------------------------------ 工具

    private record CssRule(String selector, String body, String context) {

        boolean hasSelector(String wanted) {
            for (String part : selector.split(",")) {
                if (part.trim().equals(wanted)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static String read(Path path) {
        assertTrue(Files.isRegularFile(path),
                () -> "契约前置：读不到被测文件 " + path.toAbsolutePath()
                        + "（Maven surefire 的工作目录应为项目根）。");
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 去掉注释后把样式表拆成规则列表；@media / @keyframes 等容器会被展开，context 记录外层条件。 */
    private static List<CssRule> parseRules(String source) {
        List<CssRule> parsed = new ArrayList<>();
        parseBlock(source.replaceAll("(?s)/\\*.*?\\*/", ""), "", parsed);
        return parsed;
    }

    private static void parseBlock(String block, String context, List<CssRule> out) {
        StringBuilder prelude = new StringBuilder();
        int i = 0;
        while (i < block.length()) {
            char c = block.charAt(i);
            if (c == '{') {
                String selector = prelude.toString().trim().replaceAll("\\s+", " ");
                prelude.setLength(0);
                int end = matchingBrace(block, i);
                String body = block.substring(i + 1, end);
                if (selector.startsWith("@")) {
                    parseBlock(body, selector, out);
                } else {
                    out.add(new CssRule(selector, body, context));
                }
                i = end + 1;
                continue;
            }
            if (c == '}') {
                prelude.setLength(0);
                i++;
                continue;
            }
            prelude.append(c);
            i++;
        }
    }

    private static int matchingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return text.length() - 1;
    }

    private static List<CssRule> rulesFor(String selector) {
        List<CssRule> hits = new ArrayList<>();
        for (CssRule rule : rules) {
            if (rule.hasSelector(selector)) {
                hits.add(rule);
            }
        }
        return hits;
    }

    private static String firstBody(String selector) {
        List<CssRule> hits = rulesFor(selector);
        assertFalse(hits.isEmpty(), () -> "样式契约：style.css 里没有 " + selector + " 规则。");
        return hits.get(0).body();
    }

    private static String bodyIn(String context, String selector) {
        List<CssRule> hits = rulesIn(context);
        for (CssRule rule : hits) {
            if (rule.hasSelector(selector)) {
                return rule.body();
            }
        }
        return "";
    }

    private static List<CssRule> rulesIn(String context) {
        List<CssRule> hits = new ArrayList<>();
        for (CssRule rule : rules) {
            if (rule.context().equals(context)) {
                hits.add(rule);
            }
        }
        return hits;
    }

    /** 某个选择器（可能是 :root 的多个块）的全部声明，拼成一段文本。 */
    private static String tokenBlock(String selector) {
        List<CssRule> hits = rulesFor(selector);
        assertFalse(hits.isEmpty(), () -> "主题令牌契约：style.css 缺少 " + selector + " 块。");
        StringBuilder body = new StringBuilder();
        for (CssRule rule : hits) {
            body.append(rule.body()).append('\n');
        }
        return body.toString();
    }

    private static boolean declares(String block, String token) {
        return Pattern.compile("(?:^|[;{\\s])" + Pattern.quote(token) + "\\s*:").matcher(block).find();
    }

    private static List<String> missingTokens(String block, List<String> tokens) {
        return tokens.stream().filter(token -> !declares(block, token)).toList();
    }

    private static boolean hasClassRule(String name) {
        Pattern pattern = Pattern.compile("(?:^|[\\s,>+~(])\\." + Pattern.quote(name) + "(?![\\w-])");
        for (CssRule rule : rules) {
            if (pattern.matcher(rule.selector()).find()) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> classTokens(String markup) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("class\\s*=\\s*\"([^\"]*)\"").matcher(markup);
        while (matcher.find()) {
            for (String token : matcher.group(1).trim().split("\\s+")) {
                if (!token.isEmpty()) {
                    tokens.add(token);
                }
            }
        }
        return tokens;
    }

    /** app.js 里拼出来的 class 片段：class="…"、className = '…'、classList.add/remove/toggle('…')。 */
    private static List<String> jsClassFragments(String script) {
        List<String> fragments = new ArrayList<>();
        List<Pattern> patterns = List.of(
                Pattern.compile("class\\s*=\\s*[\"']([^\"']*)[\"']"),
                Pattern.compile("className\\s*=\\s*[\"']([^\"']*)[\"']"),
                Pattern.compile("classList\\.(?:add|remove|toggle|contains)\\(([^)]*)\\)"));
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(script);
            while (matcher.find()) {
                fragments.add(matcher.group(1));
            }
        }
        return fragments;
    }

    private static boolean usedInJs(String name) {
        Pattern pattern = Pattern.compile("(?<![\\w-])" + Pattern.quote(name) + "(?![\\w-])");
        for (String fragment : jsClassFragments) {
            if (pattern.matcher(fragment).find()) {
                return true;
            }
        }
        return false;
    }

    /** index.html 雪碧图里定义的全部 symbol id，即 <use href="#…"> 可以命中的目标。 */
    private static Set<String> spriteSymbolIds(String markup) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("<symbol[^>]*\\bid=\"(i-[a-z0-9-]+)\"").matcher(markup);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    /**
     * app.js 引用的图标 id：icon('i-…') 的调用参数，以及直接拼进 <use href="#i-…"> 的 id。
     * 两者最终都是 <use href="#…">，少定义一个就是一块看不见的空白，所以都要覆盖。
     */
    private static Set<String> iconIdsUsedInJs(String script) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?<![\\w-])(i-[a-z0-9-]+)(?![\\w-])").matcher(script);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    private static String tagWithId(String id) {
        Matcher matcher = Pattern.compile("<[a-zA-Z][^>]*\\bid=\"" + Pattern.quote(id) + "\"[^>]*>").matcher(html);
        assertTrue(matcher.find(), () -> "骨架契约：index.html 里找不到 id=\"" + id + "\" 的元素。");
        return matcher.group();
    }

    private static Double letterSpacingEm(String body) {
        Matcher matcher = Pattern.compile("letter-spacing\\s*:\\s*([0-9.]+)em").matcher(body);
        return matcher.find() ? Double.valueOf(matcher.group(1)) : null;
    }

    private static String fontSizeOf(List<CssRule> hits) {
        for (CssRule rule : hits) {
            Matcher matcher = Pattern.compile("font-size\\s*:\\s*([0-9.]+px)").matcher(rule.body());
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "未声明";
    }
}