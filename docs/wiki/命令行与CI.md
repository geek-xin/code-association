---
layout: default
title: 命令行与CI
---

# 命令行与 CI

`analyze` 子命令不启动 Web 服务，适合流水线。**`--peer` 是必填的**：
扫描范围只有其他程序，没有对比对象就没有可报告的内容。

## 基本用法

```bash
# 分析最近一次提交对其他程序的影响，高风险则失败（流水线门禁）
java -jar code-association.jar analyze \
     --location /path/to/repo --base HEAD~1 --target HEAD \
     --peer /path/to/order-service --peer https://github.com/org/billing.git \
     --out impact-report.json --fail-on HIGH
```

```bash
# 本地未提交改动
java -jar code-association.jar analyze --location . --mode WORKTREE_DIFF \
     --peer /path/to/other-service --out report.json
```

```bash
# GitHub PR（第 42 号）
java -jar code-association.jar analyze --location https://github.com/org/repo.git \
     --type GITHUB --pr 42 --peer /path/to/consumer --out report.json --fail-on HIGH
```

```bash
# 只评审一批提交（起始提交不含、结束提交含）
java -jar code-association.jar analyze --location . --range origin/main..HEAD \
     --peer /path/to/other-service --out report.json --fail-on HIGH
```

`--range` 与 `--base` + `--target` 等价，只是把两端写进一个参数：从评审工具或 CI 变量里
直接粘 `$BASE..$HEAD` 是常见用法，手工拆开正是两端写反的地方。两端不是同一条提交线上的
祖先关系时（例如两个并行分支），命令以退出码 2 失败并说明是哪一端不成立——不会产出一份
看起来正常、实际在比较两个无关快照的报告。

## 退出码

| 码 | 含义 |
|---|---|
| `0` | 通过 |
| `1` | 命中 `--fail-on` 阈值 |
| `2` | 参数或仓库解析失败（含缺少 `--peer`） |

> 缺少 `--peer` 时**报错退出**而不是产出一份空报告，是为了避免 `--fail-on` 在空列表上静默通过。

## 全部参数

| 参数 | 说明 |
|---|---|
| `--location` | 本地路径或 Git 地址（必填） |
| `--peer` | 被扫描的其他程序，可重复（必填，至少一个） |
| `--type` | `LOCAL_DIR` / `LOCAL_GIT` / `GITHUB` / `GITLAB` / `GIT`，省略时按地址推断 |
| `--mode` | `GIT_DIFF`（默认）/ `COMMIT_RANGE` / `WORKTREE_DIFF` / `PULL_REQUEST` |
| `--base` `--target` | 基线 / 目标引用；`--base` 省略时取目标提交的父提交 |
| `--range` | `<起始>..<结束>`，等价于 `--base` + `--target` 并切到 `COMMIT_RANGE` |
| `--pr` | PR / MR 编号（自动切换为 `PULL_REQUEST`） |
| `--token-env` | 存放访问令牌的环境变量名 |
| `--out` `--format` | 输出文件与格式（`json` / `markdown`） |
| `--fail-on` | `CRITICAL` / `HIGH` / `MEDIUM` / `LOW`，命中阈值时退出码为 1 |

## 生成 PR 评论

```bash
java -jar code-association.jar analyze --location . --base origin/main --target HEAD \
     --peer /path/to/consumer --format markdown --out impact.md
```

输出可直接贴到 PR / MR：

```text
## 跨模块变更影响分析
**结论：** 存在高风险跨模块影响，建议人工评审后再合并

| 模块 | 风险 | 影响符号 | 最深层级 | 影响类型 |
| order-service | HIGH 75 | 6 | 5 | 行为 / 数据 |

1. HIGH 75 · 行为 · 置信度 HIGH
   - 位置：order-service OrderService#createOrder(Long, Money) — .../OrderService.java:18
   - 原因：branch condition changed. OrderService#createOrder(Long, Money) calls
           UserService#isActive(Long). The return value controls a branch condition ...
   - 影响路径：UserService#isActive(Long) -> OrderService#createOrder [CONDITION]
   - 建议验证：UserServiceTest#testIsActive()
```

## GitLab CI

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

`PEER_DIR` 需要提前把被比较的程序检出到流水线工作区（或指向已有代码源）。
私有 GitLab 项目还要令牌：在项目或组的 CI/CD Variables 里加 `GITLAB_TOKEN`（Masked），
流水线里它就是 yml 基线读取的那个环境变量；也可用 `--token-env` 指定别的变量名。

## GitHub Actions

```yaml
- name: Cross-program impact
  run: |
    java -jar code-association.jar analyze \
      --location . --base "origin/${{ github.base_ref }}" --target HEAD \
      --peer ./peer-repo --out impact.json --fail-on HIGH
```

## 私有仓库凭据

凭据来源有两种：**环境变量**（`--token-env` 只写变量名）或本机设置页保存的令牌；都不落库、不写入报告。GitHub：

```bash
export GITHUB_TOKEN=ghp_xxx
java -jar code-association.jar analyze --location https://github.com/org/private.git \
     --type GITHUB --token-env GITHUB_TOKEN --peer /path/to/consumer
```

GitLab 的三种写法：

```bash
# 1) 默认变量名：application.yml 的 code-association.gitlab.token 读 ${GITLAB_TOKEN:}
export GITLAB_TOKEN=glpat-xxxxxxxx
java -jar code-association.jar analyze --location https://gitlab.com/group/private.git \
     --type GITLAB --peer /path/to/consumer --out report.json --fail-on HIGH

# 2) 变量名不叫 GITLAB_TOKEN 时，用 --token-env 指定
java -jar code-association.jar analyze --location https://gitlab.example.com/group/private.git \
     --type GITLAB --token-env MY_GITLAB_PAT --peer /path/to/consumer

# 3) 同机已在设置页保存过令牌：不必再导出环境变量，直接跑
java -jar code-association.jar analyze --location https://gitlab.com/group/private.git \
     --type GITLAB --peer /path/to/consumer
```

| 方式 | 何时生效 |
|---|---|
| `GITLAB_TOKEN` 环境变量 | yml 基线的默认来源，不写 `--token-env` 也能用 |
| `--token-env <变量名>` | 用别的变量名；该变量有值即优先于设置页令牌 |
| 设置页「GitLab 私有仓库」 | 同一 `data-dir` 上的 CLI 直接复用保存的令牌 |

优先级：`--token-env` / 代码源「凭据环境变量」指向的变量有值 → 用它；否则 `GITLAB` 类型
使用设置页快照（没有快照时用 yml 基线）。`GITHUB` / `GIT` 类型不会附加 GitLab 令牌。
部署令牌（deploy token）需要用户名配对，不适用，请用个人访问令牌。

设置页的令牌存在 `{data-dir}/gitlab-settings.json`（POSIX 权限 600），删掉该文件重启即退回
`application.yml` / `GITLAB_TOKEN`。
