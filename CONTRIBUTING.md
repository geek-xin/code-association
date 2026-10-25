# 贡献指南

感谢参与。本项目是静态分析工具，**正确性比功能数量更重要**——一条误报会让人不再信任报告。

## 开发环境

```bash
git clone git@github.com:geek-xin/code-association.git
cd code-association
mvn test              # 全部用例必须通过
mvn -q -DskipTests package
./start.sh            # 本地起服务，默认 9996
```

要求 JDK 21+、Maven 3.9+。

## 提交前检查

```bash
mvn test
```

CI 之外没有额外门禁，但请确保：

1. **全部测试通过**（当前 62 个）
2. **新增分析规则时补测试** —— 见下节
3. **不要在服务运行时执行 `mvn package`** —— 会覆盖正在使用的 jar

## 新增分析能力的约定

每一条传播规则都必须有可复现的断言。没有测试的规则等于没有规则：
下次有人改了评分权重，没人能发现"这次改动为什么不影响对方"的答案变了。

测试放在 `src/test/java/com/geek/association/`，按关注点分组：

| 测试 | 覆盖 |
|---|---|
| `ImpactAnalysisTest` | 端到端：四模块 fixture 的完整链路 |
| `PeerImpactTest` | 跨程序契约匹配 |
| `ScheduledScanTest` | 定时扫描判定逻辑 |
| `MessagingAnalysisTest` | 消息契约 |
| `RulesTest` | 评分与归一化规则 |

fixture 项目在 `src/test/resources/fixture/`，分 `base/` 与 `head/` 两个版本。

## 代码风格

- Java 21，用 record / switch 表达式 / 文本块
- 注释解释**为什么**，不解释代码在做什么
- 中文注释与英文标识符混用是项目现状，保持一致即可
- 前端是零依赖原生 JS，不引入构建工具

## 提交信息

```text
<type>: <简短说明>

<可选的详细说明：为什么这么改，解决了什么问题>
```

type：`feat` / `fix` / `docs` / `refactor` / `test` / `build` / `chore`

## 报告问题

请提供：

1. 复现步骤（代码源类型、分析模式、是否选了 peer）
2. 期望结果与实际结果
3. 相关日志片段（`code-association.log`）
4. 如果涉及解析失败，附上 `blindSpots` 内容

## 发布流程（维护者）

```bash
./release.sh 0.2.2     # 构建 + 打标签 + 上传 tar.gz/zip 到 GitHub Release
```

脚本从 `git archive` 的干净副本构建，不会覆盖运行中的 jar。
