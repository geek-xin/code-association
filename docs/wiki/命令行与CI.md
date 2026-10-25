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
| `--mode` | `GIT_DIFF`（默认）/ `WORKTREE_DIFF` / `PULL_REQUEST` |
| `--base` `--target` | 基线 / 目标引用；`--base` 省略时取目标提交的父提交 |
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

## GitHub Actions

```yaml
- name: Cross-program impact
  run: |
    java -jar code-association.jar analyze \
      --location . --base "origin/${{ github.base_ref }}" --target HEAD \
      --peer ./peer-repo --out impact.json --fail-on HIGH
```

## 私有仓库凭据

```bash
export GITHUB_TOKEN=ghp_xxx
java -jar code-association.jar analyze --location https://github.com/org/private.git \
     --type GITHUB --token-env GITHUB_TOKEN --peer /path/to/consumer
```

Token 从**环境变量**读取，不落库、不写入报告。
