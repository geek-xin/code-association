# code-association · 其他程序变更影响扫描

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Build](https://img.shields.io/badge/build-Maven-c71a36.svg)](pom.xml)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/geek-xin/code-association)](https://github.com/geek-xin/code-association/releases)
[![Docs](https://img.shields.io/badge/docs-GitHub%20Pages-blue)](https://geek-xin.github.io/code-association/)

给定一次代码变更（本地未提交改动 / 两次提交 / 分支 / PR·MR），分析它对**其他程序模块**的影响，
并给出：影响路径、影响原因、代码位置、影响类型、风险等级、分析置信度、建议验证的测试。

> **扫描范围只有“其他程序”。** 本程序内部的调用方、模块、测试覆盖和传播链不会进入影响结果。
> 因此每次分析至少需要选择一个被扫描的其他程序。

支持的程序来源：本地目录、本地 Git 仓库、GitHub、GitLab、任意 Git 远程仓库。
分析对象以 **Java + Maven 多模块项目**为主，同时解析 MyBatis / JPA / Spring MVC / 配置文件，
以及 Kafka / RabbitMQ / RocketMQ 的消息契约，因此同一仓库内的模块、通过 HTTP、数据库、
配置或消息主题相连的服务都能被串起来。

![总览](docs/screenshots/overview.png)

> 一次真实运行的界面：`user-service` 的 `isActive` 判定条件被改动，报告指出 `order-service`、
> `payment-service` 受影响，并给出每条的路径、原因、风险与置信度。

---

## 目录

- [1. 它到底做了什么](#1-它到底做了什么)
- [2. 快速开始](#2-快速开始)（[Linux / macOS](#21-linux--macos) · [Windows](#22-windows)）
- [3. 命令行 / CI 集成](#3-命令行--ci-集成)
- [4. 分析引擎是怎么工作的](#4-分析引擎是怎么工作的)
- [5. 界面](#5-界面)
- [6. 数据与存储](#6-数据与存储)
- [7. 已知边界](#7-已知边界)
- [8. 项目结构](#8-项目结构)
- [9. AI 辅助解读（可选，默认关闭）](#9-ai-辅助解读可选默认关闭)
- [10. 验收测试](#10-验收测试)
- [11. 贡献与反馈](#11-贡献与反馈)
- [12. 许可证](#12-许可证)

---

## 1. 它到底做了什么

```text
代码来源（本地 / Git / GitHub / GitLab）
        │
        ▼  ① 变更解析：Git diff → AST 语义差异（不是文本比较）
        │     方法签名 / 实现 / 分支条件 / 字段类型 / 枚举 / 接口契约 / SQL / DDL / 配置
        ▼
        ② 代码知识图谱：模块依赖 · 方法调用 · 类型继承 · 接口实现 · 数据流用法
        │     · REST 端点与消费方 · MyBatis/JPA 表与列 · 配置项引用
        │     · Kafka/RabbitMQ/RocketMQ 主题、路由、消息 Schema · 测试关联
        ▼
        ③ 影响分析引擎：反向可达性传播 + 用法语义过滤 + 接口/数据/配置专项传播
        ▼
        ④ 风险与置信度：两个独立维度，可解释的风险因子分解
        ▼
        ⑤ 结果展示：跨程序契约地图 · 其他程序影响明细 · 变更清单 · 路径与证据 · 代码差异
```

跨程序传播时同样区分使用方式。对方程序里的调用方如何使用被调用方法的返回值，
（`CONDITION` 参与分支 / `ASSIGNMENT` 赋值后继续传递 / `ARGUMENT` 作为实参 / `RETURN` 继续返回 /
`LOG_ONLY` 仅打日志 / `IGNORED` 被忽略），并据此降低或提高行为影响的权重：

```java
// 返回值控制分支 → 行为影响权重 1.0
boolean active = userService.isActive(userId);
if (!active) { throw new IllegalStateException("user is not active"); }

// 返回值只进日志 → 行为影响权重 0.2
boolean active = userService.isActive(userId);
log.info("audit active={}", active);
```

同一个 `UserService.isActive` 的变更，前者被评为 HIGH，后者显著更低——这正是减少误报的关键。

---

## 2. 快速开始

需要 JDK 21+，Linux / macOS / Windows 三平台都支持。

### 2.1 Linux / macOS

```bash
mvn -q -DskipTests package

./start.sh          # 后台启动，默认 http://127.0.0.1:9996
./start.sh 8080     # 指定端口
PORT=8080 ./start.sh  # 同上，环境变量方式
./stop.sh           # 停止
./stop.sh 8080      # 停止 8080 上的实例
```

`start.sh` 会把进程从当前 shell 完全脱离（PPID=1、独立进程组），关掉终端不会带走服务；
日志写到 `code-association.log`，PID 写到 `.code-association.pid`。
脱离方式按平台自动选择：Linux 用 `setsid`，macOS 没有 `setsid` 时用 `python3` 双 fork，
两者都没有则退化为 `nohup` 并提示。同理，健康检查与查监听进程也不硬依赖 `curl` / `lsof`，
见 [INSTALL.md](INSTALL.md) 的兼容表。

### 2.2 Windows

```bat
mvn -q -DskipTests package

start.bat           :: 后台启动，默认 http://127.0.0.1:9996
start.bat 8080      :: 指定端口
stop.bat            :: 停止
```

`start.bat` / `stop.bat` 是薄封装，内部带 `-ExecutionPolicy Bypass` 调用同目录的
`start.ps1` / `stop.ps1`，所以**不需要修改系统的 PowerShell 执行策略**。
Windows 上以隐藏窗口后台启动，日志写到 `code-association.log`，
启动期错误单独写到 `code-association.err.log`。

### 2.3 前台运行（调试用，三平台通用）

```bash
java -jar target/code-association.jar --server.port=9996
```

界面里：右上角「新建分析」→ 选择代码源与分析模式 → 开始分析。首次使用先在「代码源」页导入项目。

### 2.4 三种分析模式

| 模式 | 含义 | 典型场景 |
|---|---|---|
| `GIT_DIFF` | 比较两个提交 / 分支 / tag | 合并前评估、分支对比 |
| `WORKTREE_DIFF` | 工作区（含未提交改动）对比 HEAD | 提交前自查"我这次改动会影响谁" |
| `PULL_REQUEST` | 拉取 PR / MR 的 head，与目标分支的 merge-base 比较 | CI 中拦截高风险变更 |

不填基线时，`GIT_DIFF` 自动取目标提交的父提交（即"最近一次提交改了什么"）。

### 2.5 来源类型

| 类型 | 位置写法 | 说明 |
|---|---|---|
| `LOCAL_DIR` | `/path/to/project` | 直接读取本地目录（工作区即目标版本） |
| `LOCAL_GIT` | `/path/to/repo` | 本地 Git 仓库，可做提交对比 / 未提交对比 / PR |
| `GITHUB` | `https://github.com/org/repo.git` | 克隆到本地数据目录，PR ref 为 `refs/pull/N/head` |
| `GITLAB` | `https://gitlab.com/group/repo.git` | MR ref 为 `refs/merge-requests/N/head` |

界面的「来源类型」只提供上述四种。通用 Git 远程地址（如自建 Git 服务、Bitbucket）没有对应的
下拉项，需要时用命令行 `--type GIT` 或直接调用 API 创建，能力与远程仓库一致，
可用 `--base/--target` 指定分支。

私有仓库：在「凭据环境变量」里填变量名（如 `GITHUB_TOKEN`），服务从**环境变量**读取 token，
不落库、不写入报告。GitHub 用 `oauth2:<token>`，GitLab 可用 `oauth2:<token>` 或私有 token。

---

## 3. 命令行 / CI 集成

`--peer` 是必填的：扫描范围只有其他程序，没有对比对象就没有可报告的内容。

```bash
# 分析最近一次提交对其他程序的影响，把高风险当作失败（可用于流水线门禁）
java -jar code-association.jar analyze \
     --location /path/to/repo --base HEAD~1 --target HEAD \
     --peer /path/to/order-service --peer https://github.com/org/billing.git \
     --out impact-report.json --fail-on HIGH

# 本地未提交改动
java -jar code-association.jar analyze --location . --mode WORKTREE_DIFF \
     --peer /path/to/other-service --out report.json

# GitHub PR（第 42 号）
java -jar code-association.jar analyze --location https://github.com/org/repo.git \
     --type GITHUB --pr 42 --peer /path/to/consumer --out report.json --fail-on HIGH
```

退出码：`0` 通过，`1` 命中 `--fail-on` 阈值，`2` 参数或仓库解析失败（含缺少 `--peer`）。
`analyze` 子命令不会启动 Web 服务。

### 3.1 直接生成 PR 评论

```bash
java -jar code-association.jar analyze --location . --base origin/main --target HEAD \
     --format markdown --out impact.md
```

`--format markdown` 输出一段可直接贴到 GitHub PR / GitLab MR 的评论：结论、其他程序影响表、
逐条影响（位置 / 原因 / 影响路径 / 建议验证的测试）以及分析边界。例如：

```text
## 跨模块变更影响分析
**结论：** 存在高风险跨模块影响，建议人工评审后再合并

| 模块 | 风险 | 影响符号 | 最深层级 | 影响类型 |
| order-service | HIGH 75 | 6 | 5 | 行为 / 数据 |

1. HIGH 75 · 行为 · 置信度 HIGH
   - 位置：order-service OrderService#createOrder(Long, Money) — .../OrderService.java:18
   - 原因：branch condition changed. OrderService#createOrder(Long, Money) calls
           UserService#isActive(Long). The return value controls a branch condition ...
   - 影响路径：UserService#isActive(Long) -> OrderService#createOrder [CONDITION] -> OrderController#create [IGNORED]
   - 建议验证：UserServiceTest#testIsActive()
```

### 3.2 全部命令行参数

| 参数 | 说明 |
|---|---|
| `--location` | 本地路径或 Git 地址（必填） |
| `--peer` | 被扫描的其他程序，可重复（必填，至少一个） |
| `--type` | `LOCAL_DIR` / `LOCAL_GIT` / `GITHUB` / `GITLAB` / `GIT`，省略时按地址推断 |
| `--mode` | `GIT_DIFF`（默认）/ `WORKTREE_DIFF` / `PULL_REQUEST` |
| `--base` `--target` | 基线 / 目标引用，`--base` 省略时取目标提交的父提交 |
| `--pr` | PR / MR 编号（自动切换为 `PULL_REQUEST`） |
| `--token-env` | 存放访问令牌的环境变量名 |
| `--out` `--format` | 输出文件与格式（`json` / `markdown`） |
| `--fail-on` | `CRITICAL` / `HIGH` / `MEDIUM` / `LOW`，命中阈值时退出码为 1 |

GitLab CI 示例：

```yaml
impact:
  image: eclipse-temurin:21-jre
  script:
    - java -jar code-association.jar analyze
        --location "$CI_PROJECT_DIR" --base "$CI_MERGE_REQUEST_TARGET_BRANCH_NAME"
        --target "$CI_COMMIT_SHA" --peer "$PEER_DIR" --out impact.json --fail-on HIGH
  artifacts:
    paths: [impact.json]
    when: always
```

---

## 4. 分析引擎是怎么工作的

### 4.1 变更识别（语义，而非文本）

文本 diff 只能告诉你"这一行变了"，引擎要回答"什么变了、属于哪类变化"。
每个变更都带稳定的符号标识：`模块::全限定名#方法名(参数类型)`。

| 变更类型 | 判定依据 | 主要传播方式 |
|---|---|---|
| `METHOD_SIGNATURE_CHANGED` | 参数或返回类型哈希变化 | 编译影响 + 调用方 |
| `METHOD_CONDITION_CHANGED` | 分支/返回条件指纹变化 | 行为影响（控制依赖） |
| `METHOD_BODY_CHANGED` | 方法体哈希变化 | 行为影响（较弱） |
| `METHOD_REMOVED` / `FIELD_REMOVED` | 符号在 head 缺失 | 编译影响 |
| `FIELD_TYPE_CHANGED` | 字段类型变化 | 类型 / 序列化影响 |
| `ENUM_CONSTANT_REMOVED` | 枚举常量缺失 | 编译 + 契约 |
| `API_CONTRACT_CHANGED` | 同一端点响应类型变化 | 接口契约 → 消费方 |
| `SQL_CHANGED` / `DDL_CHANGED` | SQL 体或 DDL 语句变化 | 数据影响 → Mapper/Entity |
| `CONFIG_CHANGED` | 配置键值变化 | 配置影响 → 读取方 |
| `TOPIC_REMOVED` / `TOPIC_ADDED` | 消息主题缺失 / 新增 | 消息契约 → 生产者、消费者 |
| `MESSAGE_ROUTE_CHANGED` | Topic、Exchange、Queue、Routing Key 或 Tag 变化 | 投递路径 → 两端代码 |
| `MESSAGE_REQUIRED_CHANGED` / `MESSAGE_FIELD_*` | 消息 DTO 必填性、字段、类型变化 | 消息契约 → 生产与消费端 |
| `MESSAGE_SCHEMA_CHANGED` | JSON / Avro / Protobuf 等消息 Schema 变化 | 消息契约 → 两端代码 |
| `BUILD_CHANGED` | pom.xml 变化 | 依赖 / 版本影响 |

### 4.2 知识图谱的七类关系

```text
DEPENDS_ON      模块 → 模块（pom.xml，真实构建模型）
CALLS           方法 → 方法（含接口/抽象方法的分派边）
INSTANTIATES    方法 → 构造器
REFERENCES_TYPE 类型 → 类型（字段/参数/返回/局部变量）
EXTENDS / IMPLEMENTS / OVERRIDES   类型层次
EXPOSES_API / CALLS_API            端点提供方 / 消费方（Spring MVC、Feign、RestTemplate、OpenAPI）
EXECUTES_SQL / READS_TABLE / WRITES_TABLE / READS_COLUMN / WRITES_COLUMN / MAPS_COLUMN
USES_CONFIG     代码 → 配置项（@Value、@ConfigurationProperties、@ConditionalOnProperty）
PUBLISHES_TO / SUBSCRIBES_TO  生产者/消费者 → 消息投递通道
ROUTES_TO       Topic / Exchange → 实际投递通道
USES_MESSAGE_SCHEMA  代码 → 消息体契约
FILTERS_BY      消费者 → RocketMQ Tag / RabbitMQ Routing Key
TESTS           测试用例 → 被测符号
```

每条边都记录 `confidence` 与 `evidence`（静态解析 / 方法引用 / 分派 / 配置推导），
无法解析的调用会进入**盲区清单**，而不是被悄悄忽略。

### 4.3 传播算法

```text
I₁ = 直接调用方/引用方（反向边，一次跳转）
Iₙ₊₁ = Iₙ ∪ Pred(Iₙ)     直到深度上限或置信度衰减到阈值以下
```

实现要点：

- 在 **base 与 head 合并后的图**上反向遍历：被删除的符号只存在于 base 图，其调用方只存在于 head 图。
- 每个节点保留"最短且置信度最高"的那条路径（避免路径爆炸）。
- 传播权重 = 边置信度 × 关系系数 × 用法系数；`TESTS` 边不参与传播（测试是验证手段，不是被影响方）。
- 首跳决定影响类型（`COMPILE` / `BEHAVIOR` / `API_CONTRACT` / `DATA` / `CONFIG` / `TYPE`）。
- 消息变更使用 `MESSAGE_CONTRACT`；从消息入口方法继续沿其下游调用展开，避免只找到消费者本身。

### 4.4 风险与置信度分离

```text
R = 100 × (0.25·S + 0.20·D + 0.20·B + 0.20·C + 0.15·U)

S 变更本身的风险      D 传播深度衰减      B 行为变化可能性（含用法权重）
C 影响对象重要程度    U 分析不确定性（= 1 - 置信度）
```

等级：`CRITICAL ≥80`、`HIGH ≥60`、`MEDIUM ≥40`、`LOW ≥20`、`INFO <20`。

**风险高但置信度低**是完全正常且必须展示的组合：影响可能很严重，但静态分析没把握，
此时结论是"需要人工确认"，而不是"确定受影响"。报告中两个数字分开显示，并在详情面板给出风险因子分解。

---

## 5. 界面

左侧是两级导航：窄图标栏切换主功能区，文字导航承载代码源、状态分组、计数与最近分析。
文字菜单可以收起——收起后只留图标栏，内容区拿回宽度（见 [5.6](#56-收起左侧菜单栏)）。
右侧工作区按任务优先级分配宽度，主任务占更大面积，辅助信息放右侧。

| 工作区 | 内容 |
|---|---|
| **总览** | 待处理影响、审核进度环、风险分布、模块影响地图、近期事件时间线与最近分析 |
| **代码源** | 导入表单（含标签）、四步导入进度、工作原理说明、最近导入记录（标签 pill 即过滤） |
| **影响审核** | 发现列表（待审核 / 已确认 / 已忽略），右侧证据链与逐跳路径，确认 / 忽略操作 |
| **影响详情** | 变更 → 传播 → 影响三段式结论、影响路径、证据、风险因子分解、建议验证的测试、代码差异 |
| **复核队列** | 高风险待复核、复核建议、处理进度 |
| **模块地图** | 放大版依赖图与关系类型统计 |

选中代码源与加载报告是两件事：点击左侧代码源即把工作区切换到它，即使该源还没有任何分析。
此时各页显示「这个代码源还没有分析记录」并引导新建分析，而不是保留上一个代码源的结果。

- **模块影响地图**：Cytoscape 依赖图，节点大小=风险分，颜色=风险等级，紫色描边=本次变更模块，
  实线=调用/引用/接口/SQL/配置，虚线=Maven 模块依赖。点击节点筛选影响明细。
- **模块影响明细**：每个受影响模块的最高风险、影响符号数、最深层级、影响类型与主要原因。
- **变更清单**：语义变更列表，点击查看变更前后、标签与对应代码差异。
- **影响详情抽屉**：路径从"变更起点"开始逐跳展示，每一跳标注调用方的使用方式（`CONDITION` / `LOG_ONLY` 等）。

### 5.1 深浅色主题

界面支持浅色 / 深色两套配色，并默认跟随操作系统。

- **切换位置**：左侧图标栏底部的月亮 / 太阳按钮，或「设置 → 外观主题」。
- **三种偏好**：跟随系统（默认）/ 浅色 / 深色，选择保存在浏览器 `localStorage` 的 `ca.theme`。
- **无闪烁**：`index.html` 在样式表之前先解析偏好并写好 `data-theme`，深色会话首帧即为深色。
- **跟随系统**：偏好为「跟随系统」时监听 `prefers-color-scheme`，系统切换会实时生效。

实现上所有颜色都收敛为 `style.css` 中的设计令牌：`:root` 定义浅色值，
`:root[data-theme="dark"]` 只覆盖令牌、不改动任何布局规则。依赖图（Cytoscape）
把颜色烘进画布，无法继承 CSS 变量，因此由 `app.js` 的 `themeColor()` 读取令牌
的计算值，并在主题切换时重建图实例。

![影响详情](docs/screenshots/impact-detail.png)

![影响审核](docs/screenshots/review-findings.png)

![代码源导入](docs/screenshots/import.png)

![模块地图](docs/screenshots/module-map.png)

> **复核状态是本地状态。** 「确认 / 忽略」的复核结果保存在浏览器 `localStorage`，
> 只影响当前浏览器的界面展示，不会写回服务端分析结果。分析数据本身始终来自服务端。

- **分析边界与盲区**：无法解析的调用点（反射、动态代理、生成的代码、动态 SQL 等）。
- **代码差异**：按文件折叠的 unified diff。

### 5.2 添加代码源：选择文件夹与自动命名

添加「本地目录 / 本地 Git 仓库」类型的代码源时，位置一栏右侧有「浏览…」按钮，
打开文件夹树直接选择，不必手输路径。

**名称不需要填写**：它只是一个展示标签，由位置自动推导——本地目录取文件夹名，
远程仓库取 `owner/repo`（避免多个同名 fork 无法区分）。表单里的「名称」是只读预览，
随位置实时更新；推导逻辑在 `SourceNames`，服务端与命令行共用同一套规则，
因此 REST / CLI 不传 name 也能正常创建。

浏览器的文件选择框只会返回相对文件名、拿不到绝对路径，所以选择器由本地服务驱动
（`GET /api/fs/dirs`）。它只列目录、不读文件内容，并给每个目录标注 `git`（含 `.git`）
与 `maven`（含 `pom.xml`），方便识别该选哪个。

- 支持直接输入路径回车跳转、上一级、主目录 / 根目录快捷入口、显示隐藏目录。
- **默认分支自动读取**：填写位置后自动列出该仓库的分支与标签供选择，不需要手输。
  本地仓库直接读取；远程仓库用 `ls-remote` 查询（不克隆，10 秒超时），读不到时留空由服务端自动选择。
- 选定后自动回填位置；若该目录是 Git 工作副本，来源类型会自动切到「本地 Git 仓库」。
- 保存前服务端会校验：本地来源必须指向已存在的目录，`LOCAL_GIT` 还必须含 `.git`，
  否则返回 400 并给出具体原因。

### 5.3 删除代码源

「代码源」页的「最近导入」列表里，每行右侧有删除按钮（悬停显示，触屏常驻）。
删除是不可撤销的，所以会先弹出确认框，要求输入代码源名称才能点「删除」。

删除是级联的：

- 该代码源的**分析记录一并删除**——分析里存的是名称快照，留着会继续展示已被移除的代码库。
- 远程仓库的**本地克隆缓存**（`.code-association/repos/<id>-<name>-<hash>`）一并清理。
- 用户配置的**位置本身永远不会被删除**，只删服务自己创建的副本。

还有分析在跑时拒绝删除（返回 400），避免后台任务在记录删除后又把结果写回去。

### 5.4 跨程序影响比较

单个仓库的分析只能看到仓库内部。但一个改动常常越过仓库边界：改了接口，调用方在另一个服务里；
改了表结构，读这张表的是另一个程序。

在「新建分析」弹窗底部勾选**同时比较其他程序**，就会把本次变更与选中的程序对齐：

| 契约 | 对齐方式 |
|---|---|
| HTTP 接口 | 按规范化路径模板匹配，调用方写的是具体路径也能对上（`/api/users/42` ↔ `/api/users/{id}`，`"/api/users/" + id` 拼接写法同样识别） |
| 数据库表 | 改动的 SQL 语句会先还原出它读写的表，再按表名匹配；区分读方与写方 |
| 配置项 | 按 key 匹配，`a.b.c` 的改动也会命中绑定 `a.b` 前缀的代码 |
| Maven 依赖 | 对方声明了对本程序模块的依赖，即视为编译期契约 |
| 消息主题 | Kafka / RocketMQ Topic、RabbitMQ Queue 路由按规范化标识匹配 |
| 消息 Schema | 同一中间件下的消息类型、字段、必填性和枚举指纹一致 |

命中之后，影响会**在对方程序的调用图里继续传播**，所以看到的不只是直接调用方，还有它的上游。
每个条目都给出契约、使用点、文件行号和置信度，并复用与本地分析相同的风险与置信度模型。

两个限制值得说明：

- 只比较**本次真正改动过**的契约。共享接口没被改动就不算影响，否则结果会退化成一份清单。
- 对方程序按当前工作区索引；无法读取时只在该条目上给出原因，不会让整次分析失败。

结果展示在**总览**和**影响审核**两页的「对其他程序的影响」卡片里：命中哪些契约、对方哪段代码在用、
影响在对方内部继续传播到哪里。两个页面都放，是因为审核时同样要判断「这次改动要不要通知另一个团队」。

实现上不是把两个仓库的图合并——它们本来就不共享调用图。做法是：先用契约把两边接上，
再以对方命中的那个节点为起点，在对方自己的图里跑一遍普通的影响传播。
所以跨程序的影响条目在评分、置信度和解释文案上与本地分析完全一致，没有第二套算法。

**扫描范围只有其他程序。** 本程序内部的调用方、模块、测试与传播链不会进入结果：
引擎不会对自身索引做传播，报告里的模块地图、盲区与计数也全部来自对方程序。
因此每次分析至少选择一个被扫描的其他程序；命令行同样要求 `--peer`，缺省会直接报错退出，
而不是产出一份空报告让 `--fail-on` 静默通过。

### 5.5 每日定时扫描

不必等人点按钮：每天定时检查所有已注册的程序，**只对产生了新提交的程序**发起分析。

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `code-association.schedule.enabled` | `true` | 总开关 |
| `code-association.schedule.cron` | `0 30 2 * * *` | 每天 02:30 |
| `code-association.schedule.run-on-startup` | `false` | 启动后补跑一次，用于补上停机期间错过的扫描 |
| `code-association.schedule.max-programs-per-run` | `20` | 单次扫描的程序上限 |
| `code-association.schedule.directory-rescan-minutes` | `1440` | 无提交可比的普通目录，按时间间隔重扫 |

判定逻辑：解析程序当前提交，与该程序**上一次成功分析所记录的提交**比较。
相同则跳过，不同则分析；每次分析都以**其余所有程序**为对比对象。
此外还有三种情况会跳过而不是反复重试：已有分析在进行中、位置不可访问、没有其他程序可比较。

运维接口：

```bash
curl http://127.0.0.1:9996/api/schedule        # 配置、下次时间、上次每个程序的判定结果
curl -X POST http://127.0.0.1:9996/api/schedule/run   # 立即执行一次（异步，结果回上面查）
```

两个可靠性细节值得说明：

- **孤儿任务恢复**：进程被杀死时正在运行的分析会永远停在 RUNNING，后续扫描会一直把它当作
  「忙碌」而跳过。启动时会把这些行标记为失败，让调度自愈。
- **提交要落库**：分析实际比较的提交在完成时写回数据库。否则调度无法区分「已分析过这个提交」
  与「从未分析」，每天都会把所有程序重扫一遍。

### 5.6 收起左侧菜单栏

文字菜单可以收起，把宽度让给工作区。三个入口等价：

| 入口 | 位置 |
|---|---|
| 折叠按钮 | 侧栏右上角，添加按钮左边的箭头按钮 |
| 展开按钮 | 图标栏底部，收起后才出现 |
| 快捷键 | `Ctrl + B` / `⌘ + B` |

收起后只剩窄图标栏，六个主功能区仍然一点即达——图标栏本来就是常驻的那一层，
文字菜单承载的是分组、计数与最近分析这类细节。选择存在 `localStorage` 的 `ca.nav`，
刷新后保持，且首帧就按记下的状态渲染，不会先展开再折起。

三个容易忽略的细节：

- **收起时用 `visibility:hidden`，不只是零宽度**：否则按 Tab 会依次停在那些看不见的
  链接和按钮上。同时两个按钮的 `aria-expanded` 同步反转，读屏不会读到过期的展开状态。
- **窄屏（≤860px）默认收起，但仍可手动展开**：这一断点原来直接用 `display:none` 把菜单
  藏死，没有任何展开入口；现在收起只是默认值，不是能力上限。
- 快捷键在输入框 / 文本域 / 下拉框里自动让位，不会吃掉正常输入。


### 5.7 代码源标签分类

一个代码源可以打多个标签，用来分组和检索。标签是**纯展示元数据**：分析引擎不读它，
它也不会影响契约匹配、风险评分或报告内容。

| 位置 | 交互 |
|---|---|
| 添加表单 / 新建弹窗 | 「标签」输入框：按 Enter 或逗号确认，退格在空输入时删最后一个，粘贴 `a,b,c` 一次拆成多个 |
| 候选池 | 输入框下方列出**已在用**的标签（来自 `GET /api/tags`），点一下即添加 |
| 代码源列表 | 每行显示标签 pill，点 pill 即按该标签过滤；再次点击或点「清除」恢复 |
| 左侧菜单 | 代码源按标签分组，可逐组折叠（状态记在 `localStorage` 的 `ca.navtags`） |

一个源属于多个标签时会**出现在每个标签组里**——这正是多标签与单分类的区别；
没有标签的源归入「未分类」，永远排在最后。

归一化规则在服务端（`SourceTags`），Web、CLI 与导入文件因此得到同一个答案：

- 按英文逗号与中文逗号拆分，trim，丢弃空串；
- **大小写不敏感去重**，保留首次出现的写法（`Payment` 与 `payment` 视为同一个）；
- 单个标签最多 24 个字符，一个代码源最多 12 个标签；
- 永不抛异常——标签是检索辅助，格式不对只收敛、不报错。

`tags` 字段接受 JSON 数组或逗号分隔字符串两种写法（见 `SourceTagsDeserializer`），
因为表单和手写 curl 习惯不同，为此回 400 没有意义。

### 5.8 代码源导入导出

设置页有「代码源导入导出」卡片，用于**换机器迁移**、**团队共享清单**与**备份**。

**导出**（`GET /api/sources/export`）下载一个 JSON，文件名带时间戳：

```json
{
  "kind": "code-association.sources",
  "version": 1,
  "exportedAt": "2026-10-26T08:41:58.488818Z",
  "sources": [
    { "name": "alpha", "type": "LOCAL_DIR", "location": "/work/alpha",
      "defaultRef": "main", "credentialEnv": null, "tags": ["payment", "core"] }
  ]
}
```

**不导出**本机运行时字段：`id`、`status`、`message`、`createdAt`、`lastSyncedAt`。
这些只对产生它们的那台机器有意义，带过去只会让导入方覆盖掉自己的健康状态。
凭据本身从不入库，导出的只是环境变量**名**。

**导入**（`POST /api/sources/import`）按 **type + location** 判定是否为同一个代码源：

| 情况 | 行为 |
|---|---|
| 已存在 | **更新**：标签取**并集**，`defaultRef` / `credentialEnv` 仅在传入值非空时覆盖 |
| 不存在 | **新建**，`name` 为空时按位置推导 |
| 单条非法 | 记入 `errors` 并继续处理其余条目，不中断整份文件 |
| 本地目录不存在 | 仍然导入，但置 `status = MISSING`，列表显示「目录缺失」 |

位置比较会忽略尾部斜杠与首尾空白（`a/b/` 与 `a/b ` 是同一个源），
否则手工改过路径的文件每导一次就会复制出一份重复的代码源。

导入结果返回计数与逐条错误：

```json
{ "total": 3, "created": 1, "updated": 1, "skipped": 1,
  "errors": [{ "index": 2, "name": "broken", "reason": "不支持的 type：NOT_A_TYPE" }] }
```

「导入你刚导出的文件」是幂等的：应全部计为 updated，标签与数据都不变。

---

## 6. 数据与存储

SQLite（默认 `./.code-association/code-association.db`）：

```text
repo_source   代码源注册表（类型、位置、默认分支、凭据环境变量名、标签 JSON 数组）
analysis      每次分析：模式、基线/目标提交、状态、耗时、摘要 JSON、完整报告 JSON
```

代码索引不落库：每次分析从快照重建（毫秒级），避免索引与代码漂移。
远程仓库克隆到 `.code-association/repos/<id>-<name>-<urlHash>`（同一地址复用同一缓存，
不会与同名仓库冲突）。

界面在选中代码源后会调用 `GET /api/sources/{id}/refs` 拉取真实分支与标签，作为基线/目标的候选值。

REST 接口：

| 方法 | 路径 | 作用 |
|---|---|---|
| `GET` | `/api/health` | 运行状态与配置 |
| `GET` `POST` `PUT` `DELETE` | `/api/sources` `/api/sources/{id}` | 代码源管理 |
| `GET` | `/api/sources/{id}/refs` | 分支 / 标签列表 |
| `GET` | `/api/tags` | 全部在用标签及计数（侧栏分组与输入候选） |
| `GET` | `/api/sources/export` | 导出全部代码源为 JSON 文件 |
| `POST` | `/api/sources/import` | 导入代码源（合并标签，逐条报错） |
| `POST` | `/api/analyses` | 提交分析（异步，立即返回 id） |
| `GET` | `/api/analyses` `/api/analyses/{id}` | 历史与状态 |
| `GET` | `/api/analyses/{id}/report` | 完整报告（未完成时返回 202） |
| `GET` | `/api/update` | 当前版本、最新版本、历史版本列表 |
| `GET` | `/api/update/history` | 本机版本记录（见过 / 下载过 / 应用过 / 回滚过） |
| `POST` | `/api/update/check` | 强制向 GitHub 重新检查 |
| `POST` | `/api/update/download` | 下载指定版本的 jar（校验后暂存） |
| `POST` | `/api/update/apply` | 用暂存的 jar 替换当前 jar（备份旧的） |
| `POST` | `/api/update/rollback` | 从备份回滚 |

配置项（`application.yml`）：

```yaml
server:
  port: 9996
code-association:
  data-dir: ./.code-association
  max-files: 20000      # 单仓库解析文件上限
  max-file-mb: 20       # 单文件读取上限（MB）
  max-depth: 8          # 影响传播最大深度
```

`max-file-mb` 是单个文件的读取上限：源码通常只有几 KB，接近这个数的基本是打包进仓库的
SDK、数据库导出或改了扩展名的 jar。超过上限的文件不读取、不索引，**在报告「跨程序分析边界」
里逐个列出文件名与大小**，而不是让整次分析失败。设为 `0` 表示不限制。

> 为什么必须有这一项：JGit 读超大对象时抛出的是 `LargeObjectException`（`RuntimeException`），
> 不会被 `catch (IOException)` 拦住。仓库里只要有一个这样的文件，**该程序的每一次分析都会失败**，
> 且错误信息只有 `Object unknown object exceeds 20,971,520 limit`，看不出是哪个文件造成的。

---

## 6.1 版本检查与自动更新

服务会定时向 GitHub 查询 **release 与 git tag**，设置页能直接看到「当前版本 / 最新版本 / 历史版本」。
发现新版本时界面顶部会出现提示横幅，左侧图标栏的设置按钮也会亮起红点——不必主动进设置页。
**检查是自动的，替换 jar 需要你点确认**——让程序重启自己会让前端拿不到响应，
把「什么时候中断服务」的决定权交给人更稳妥。

```yaml
code-association:
  update:
    enabled: true
    repo: geek-xin/code-association
    token: ${GITHUB_TOKEN:}      # 可选，见下方限流说明
    check-interval-minutes: 360   # 6 小时
    cron: "0 0 * * * *"           # 每小时一次定时检查（命中缓存时只读内存）
    max-releases: 20              # 历史版本列表最多返回多少条
    include-prerelease: false
    include-tags: true            # 同时读 git tag，不只读 release
    auto-download: false          # 发现新版本后自动下载 jar（仍然不会自动应用）
```

**为什么同时读 tag。** 一个版本通常既是 tag 又是 release，但维护者常常先 push tag、
稍后才写 release（或者干脆忘了写）。只读 `/releases` 的结果是这类版本在历史里根本不存在，
用户会以为版本没发布。所以默认还会读 `/tags` 并与 release 合并：**同一版本以 release 为准**
（release 有更新说明和附件），只在 tags 里出现的版本标记为「仅标签」，能看见但通常不能就地下载。
`/tags` 请求失败不会让整次检查失败——release 的结果照常返回，失败原因进 `warnings` 显示在界面上。

**「有新版本」与「有可安装的新版本」是两件事。** 最新 tag 可能没有对应 release，也就没有 jar。
状态里因此有两个字段：`updateAvailable`（GitHub 上存在更新的版本）与
`installableUpdateAvailable`（那个版本真的能下载安装）。界面横幅与红点只由后者触发，
避免把用户引到一个点了必然失败的下载按钮；每个 `releases[]` 条目也带 `downloadable` 标记。

**关于 GitHub 限流。** 未认证调用 `api.github.com` 每小时只有 60 次。默认 6 小时检查一次，
一天 4 次，够用；但如果你频繁点「立即检查」，就会撞上 403。此时界面会明确提示是限流，
并给出额度重置时间。设置 `GITHUB_TOKEN`（一个只读的 classic token 即可，不需要任何 scope）
可把上限提到每小时 5000 次：

```bash
export GITHUB_TOKEN=ghp_xxx
./start.sh
```

**更新流程。** 点「下载此版本」→ 下载到 `{data-dir}/updates/`，边下边算 SHA-256，
下完再校验文件头确实是 jar（`PK\x03\x04`），不是就删掉并报错；然后点「应用更新」→
当前 jar 先备份成 `<jar>.bak-<版本>`，再被新 jar 覆盖。**替换完不会自动重启**，
界面会提示你执行 `./stop.sh && ./start.sh`（Windows 上是 `stop.bat` 再 `start.bat`）。

三种情况下「应用更新」会被拒绝，并说明原因：

| 情况 | 原因 |
|---|---|
| 在 IDE 里跑（不是 `java -jar`） | 没有可替换的 jar，CodeSource 不是文件 |
| Windows | 运行中的 jar 被系统锁定，无法覆盖 |
| 没先下载 | 没有暂存文件可应用 |

回滚用同一套机制：`POST /api/update/rollback` 从最近的 `.bak-*` 恢复。

**本机版本记录。** `GET /api/update/history` 读的是落在 `{data-dir}/update-history.json` 的账本，
记录每个版本**第一次见到 / 最近见到 / 下载过 / 应用过 / 回滚过**的时间。这份记录跨重启保留，
所以「这台机器上的 0.3.0 是哪天下载的」可以直接查，而不必去翻文件时间戳。
账本写坏或没权限时只会退化成空账本并记一条日志——它绝不能连带让版本检查失败。

**下载状态是算出来的，不是记下来的。** `releases[].downloaded` 每次都由
`{data-dir}/updates/code-association-<版本>.jar` 是否真的存在决定。
手动删掉 jar 后界面会如实回到「未下载」，不会因为账本写着下载过就骗人。

**发现新版本时不会只在设置页里提示。** 页面顶部会出现提示横幅，左侧图标栏的设置按钮亮红点，
并可直接「查看」或「忽略此版本」（忽略只作用于当前浏览器，记在 localStorage）。
这样自动检查才真的「自动」——否则用户不点进设置页就永远不知道有新版本。

---

## 7. 已知边界

静态分析无法在一般情况下完整证明跨模块行为是否变化，系统如实标注能力边界：

| 场景 | 现状 |
|---|---|
| 多态 / 接口调用 | 记录声明方法 + 已知实现的分派边（置信度较低） |
| Spring 依赖注入 | 通过字段/构造器类型解析，不还原 Bean 装配图 |
| 反射 / 动态代理 / 代码生成 | 无法解析，进入盲区清单 |
| Feign / RestTemplate / OpenAPI | 按路径模板匹配端点，动态拼接的 URL 可能漏报 |
| 动态 SQL（`<if>` 拼接） | 解析静态部分，动态部分进入盲区 |
| 配置中心（Nacos/Apollo） | 仅分析仓库内配置与代码引用 |
| 跨仓库依赖 | 需分别登记代码源；单次分析在同一仓库内完成 |
| 框架入口（Controller / Listener / 定时任务） | 由容器调用，仓库内没有静态调用方；报告会单独计数并说明 |
| 消息中间件 | 支持 Spring Kafka、Spring AMQP、RocketMQ Spring/原生客户端；不读取 Broker 中的真实消息 |
| 动态消息目标 | Topic、Queue、Routing Key、Tag 来自运行时参数或配置中心时进入盲区，不猜测具体值 |
| 消息 Schema | 支持 JSON DTO 及 Avro / Protobuf 文件识别；Schema Registry 在线拉取不在首版范围 |
| 消息框架 | Kafka Streams 拓扑 DSL、Spring Cloud Stream 动态 Binding 不在首版范围 |
| 首次提交（无父提交） | 基线为空快照，所有符号都算新增；报告会明确标注 |

---

## 8. 项目结构

```text
src/main/java/com/geek/association/
├── source/     快照抽象：DirSource / GitTreeSource / RepositoryPreparer（本地·Git·GitHub·GitLab·PR）
├── parse/      MavenParser · JavaIndexer · CallGraphBuilder · TypeResolver
│               SqlAnalyzer（MyBatis/JPA/DDL）· ApiAnalyzer（Spring MVC/Feign/OpenAPI）
│               ConfigAnalyzer · TestLinker · CodeIndexer
├── diff/       FileDiffCollector · ChangeDetector（语义变更分类）· LineDiff
├── impact/     ImpactGraph（base+head 合并）· PropagationRules · ImpactEngine · RiskScorer
├── report/     ImpactReport（输出模型）· ReportBuilder · MarkdownReport · AiExplainer
├── service/    AnalysisService（异步编排）
├── store/      Store（SQLite）
├── web/        ApiController（REST）
├── cli/        CliRunner（CI 无头模式 + 退出码）
└── config/     AppProperties

src/main/resources/static/   零依赖前端（原生 JS + Cytoscape 本地内置）
src/test/resources/fixture/  四模块 Java 示例项目（base/ 与 head/ 两个版本）
src/test/java/               62 个用例，按关注点分组（见第 10 节）

docs/wiki/                   深度文档：核心概念 · 契约匹配 · 评分模型 · 架构 · 排错
.github/                     Issue 模板与 PR 模板
start.sh / stop.sh           Linux · macOS 启停脚本（bash，逐项探测可用工具）
start.ps1 / stop.ps1         Windows 启停脚本（PowerShell 实现）
start.bat / stop.bat         Windows 薄封装（自带 -ExecutionPolicy Bypass）
release.sh                   打包并发布 tar.gz / zip 到 GitHub Release（含两个平台脚本）
```

### 8.1 启动脚本的平台适配

同一个 jar、同一份数据目录，三个平台各有对应的启停脚本，参数契约完全一致：

| 平台 | 启动 | 停止 | 端口参数 |
|---|---|---|---|
| Linux / macOS | `./start.sh` | `./stop.sh` | `./start.sh 8080` · `PORT=8080` |
| Windows | `start.bat` | `stop.bat` | `start.bat 8080` · `-Port 8080` · `set PORT=8080` |

`start.sh` / `stop.sh` 不硬依赖任何单一工具，缺什么就换实现：

| 能力 | 首选 | 备选 | 都没有 |
|---|---|---|---|
| 脱离终端 | `setsid`（Linux） | `python3` 双 fork（macOS） | `nohup` + 提示 |
| 健康检查 | `curl` | bash `/dev/tcp` | 纯 sleep 轮询 |
| 查监听 PID | `lsof` | `ss` / `netstat -tlnp` | 记录的 `$!` / 命令行匹配 |

Windows 侧以隐藏窗口后台启动，`.ps1` 保存为 UTF-8 with BOM、
`.bat` 为纯 ASCII、统一 CRLF —— PowerShell 5.1 会按 ANSI 解码无 BOM 文件，
不这样处理中文注释会乱码甚至语法报错。发布脚本对这些编码做了字节级校验。

---

## 9. AI 辅助解读（可选，默认关闭）

设计原则是"先由程序分析产出事实，再让模型解释事实"，而不是把仓库丢给模型猜：

- 送入模型的是**已经算出的**变更、影响路径、每一跳的用法、置信度与盲区，不包含源码全文；
- 模型只被要求写评审要点，并被明确禁止编造事实中不存在的类/方法/调用；
- 输出单独存放于 `report.ai`，UI 顶部标注"基于上述事实生成 · 待验证"，与静态分析结论分开呈现。

```yaml
code-association:
  ai:
    enabled: true
    base-url: https://api.deepseek.com/v1     # 任意 OpenAI 兼容端点
    api-key: ${AI_API_KEY}
    model: deepseek-chat
```

关闭时（默认）报告完全不依赖任何外部服务。

## 10. 验收测试

```bash
mvn test
# 62 tests, 0 failures
```

用例按关注点分组，每条规则都有对应断言：

| 测试 | 覆盖内容 |
|---|---|
| `ImpactAnalysisTest` | 端到端：四模块工程的条件变更、SQL 变化、配置翻转、接口契约变化 |
| `PeerImpactTest` | 跨程序契约匹配：HTTP / 数据库表 / 配置项，以及无关程序不误报 |
| `ScheduledScanTest` | 定时扫描的判定：首次扫描、无新提交跳过、位置丢失跳过、孤儿任务恢复 |
| `MessagingAnalysisTest` | Kafka / RabbitMQ / RocketMQ 主题、路由与消息 Schema 契约 |
| `RulesTest` | 风险与置信度分离、评分归一化、用法权重 |
| `ApiPathMatchTest` | 路径模板匹配：具体路径、拼接写法、不相关路径不误判 |
| `PathBrowserTest` / `RefDiscoveryTest` / `SourceNamesTest` / `AnalysisProgressTest` | 目录选择、分支读取、自动命名、进度持久化 |

`ImpactAnalysisTest` 用一个真实结构的多模块工程验证端到端行为：

- head 版把 `UserService.isActive` 从"存在且 status==1"改为"仅存在"；
- 同时改窄 `selectById` 的查询列、翻转一个配置开关、把 `GET /api/users/{id}` 的响应类型改为 `UserSummary`。

断言：条件变更被识别为 `METHOD_CONDITION_CHANGED`；`order-service` 的 `createOrder`（分支消费）
被判为 HIGH 且高于 `auditOrder`（仅日志消费）；`payment-service.validateUser` 被间接命中；
控制器作为 2 层以上间接影响出现；接口契约变更产生 `API_CONTRACT` 影响；报告含模块依赖边。

## 11. 贡献与反馈

- **问题与建议**：[Issues](https://github.com/geek-xin/code-association/issues)
- **变更记录**：[Releases](https://github.com/geek-xin/code-association/releases)
- **深度文档**：[geek-xin.github.io/code-association](https://geek-xin.github.io/code-association/)（源码在 `docs/`）

提交代码前请确认：

```bash
mvn test          # 全部用例必须通过
mvn -q -DskipTests package
```

新增分析能力时，请同时补一个用例：引擎的每一条传播规则都应当有可复现的断言，
否则"这次改动为什么不影响对方"无法被验证。

改动启停脚本时请注意：`start.sh` / `stop.sh` 必须同时能在 Linux 与 macOS 跑，
Windows 的 `start.ps1` / `stop.ps1` 保持 UTF-8 BOM + CRLF、`start.bat` / `stop.bat` 保持纯 ASCII + CRLF。
`release.sh` 会在发布前校验这些编码约束，改坏了会直接失败而不是发出坏包。

## 12. 许可证

[MIT](LICENSE) © geek-xin