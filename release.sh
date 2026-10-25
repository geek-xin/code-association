#!/usr/bin/env bash
#
# release.sh — 构建、打包并发布 code-association 到 GitHub Release
#
# 用法:
#   ./release.sh                  # 发布默认版本 0.2.1
#   ./release.sh 0.3.0            # 发布指定版本
#   VERSION=0.3.0 ./release.sh    # 同上（环境变量方式）
#
# 产物:
#   build/release/code-association-<version>/            # 干净的发布目录
#   build/release/code-association-<version>.tar.gz      # 压缩包（顶层目录 code-association-<version>/）
#   build/release/code-association-<version>.zip         # 同上
#
# 流程:
#   1. mvn -q -DskipTests package 构建 target/code-association.jar
#   2. 组装干净目录：jar、start.sh、stop.sh、README.md、application.yml、INSTALL.md、docs/
#   3. 生成 .tar.gz 与 .zip 两个压缩包
#   4. 校验压缩包内确实包含 start.sh / stop.sh（缺失则直接失败）
#   5. 创建或更新 tag 为 v<version> 的 GitHub Release，并上传两个资产
#
# 幂等性:
#   同一版本可重复执行：本地产物被覆盖；远端资产用 gh release upload --clobber 覆盖；
#   Release 已存在时改为更新标题与说明，不会报错。
#
# 安全性说明:
#   本脚本绝不启动/停止服务，只做构建、打包、发布。
#   写入文档的 heredoc 一律使用**带引号的定界符**（<<'EOF'），
#   文档里的 @@APP@@ / @@VERSION@@ 占位符由 render() 用纯字符串替换填充。
#   —— 绝不能改成不带引号的 heredoc：那样 bash 会执行文档中反引号/$( ) 里的内容
#      （曾经因此误执行过 ./start.sh 与 ./stop.sh）。
#
# 依赖: bash、mvn、tar、zip、unzip、gh（已登录）
set -euo pipefail

cd "$(dirname "$0")"

APP="code-association"
VERSION="${1:-${VERSION:-0.2.1}}"
TAG="v${VERSION}"
RELEASE_ROOT="build/release"
STAGE="${RELEASE_ROOT}/${APP}-${VERSION}"
TARBALL="${RELEASE_ROOT}/${APP}-${VERSION}.tar.gz"
ZIPBALL="${RELEASE_ROOT}/${APP}-${VERSION}.zip"
NOTES="${RELEASE_ROOT}/release-notes-${TAG}.md"

die() { echo "错误: $*" >&2; exit 1; }
log() { echo "==> $*"; }

# 纯字符串替换渲染模板：不做任何命令求值
render() {
  local file="$1" content
  content="$(cat "$file")"
  content="${content//@@APP@@/$APP}"
  content="${content//@@VERSION@@/$VERSION}"
  printf '%s\n' "$content" > "$file"
}

# ---------- 0. 参数与工具检查 ----------
[[ "$VERSION" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]] || die "版本号不合法: '$VERSION'（只允许字母数字 . _ -）"

for bin in mvn tar zip unzip gh git; do
  command -v "$bin" >/dev/null 2>&1 || die "缺少命令: $bin"
done

gh auth status >/dev/null 2>&1 || die "gh 未登录，请先执行 gh auth login"

# 项目要求 Java 21：未显式设置 JAVA_HOME 时优先选 21，避免默认 JDK 版本漂移
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  [ -n "$JAVA_HOME" ] && export JAVA_HOME
fi

REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || true)"
[ -n "$REPO" ] || die "无法确定 GitHub 仓库（确认本目录已关联 origin，且 gh 已登录）"

TARGET_REF="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo main)"
[ "$TARGET_REF" = "HEAD" ] && TARGET_REF="main"

log "仓库: $REPO    版本: $VERSION    tag: $TAG"

# ---------- 1. 构建 jar（在干净的临时副本中构建） ----------
# 为什么不在原地构建：
#   1) 线上服务可能正打开 target/code-association.jar，原地覆盖会让运行中的 JVM
#      之后加载类时抛 ClassNotFoundException（曾经因此让服务异常）。
#   2) 从 git archive 出来的干净树构建，能保证「产物 == 该提交」，不会把未提交的
#      改动（比如别人正在编辑的源文件）悄悄打进包里。
# 需要原地构建时可设置 RELEASE_INPLACE=1。
BUILD_ROOT="${RELEASE_ROOT}/build-tree"
if [ "${RELEASE_INPLACE:-0}" = "1" ]; then
  log "构建 jar（原地）: mvn -q -DskipTests package"
  BUILD_DIR="."
  mvn -q -DskipTests package
else
  log "构建 jar（干净副本）: git archive ${BUILD_REF:-HEAD} -> $BUILD_ROOT"
  if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
    echo "    注意：工作区有未提交改动，这些改动不会进入本次发布（构建取自 ${BUILD_REF:-HEAD}）" >&2
  fi
  rm -rf "$BUILD_ROOT"
  mkdir -p "$BUILD_ROOT"
  git archive "${BUILD_REF:-HEAD}" | tar -x -C "$BUILD_ROOT"
  BUILD_DIR="$BUILD_ROOT"
  ( cd "$BUILD_ROOT" && mvn -q -DskipTests package )
fi
SRC_DIR="$BUILD_DIR"          # 源码根：干净副本，或原地构建时的 .
JAR="${BUILD_DIR}/target/${APP}.jar"
[ -f "$JAR" ] || die "构建完成但找不到 $JAR"

# ---------- 2. 组装发布目录 ----------
log "组装发布目录: $STAGE"
rm -rf "$STAGE"
mkdir -p "${STAGE}/target"

cp "$JAR" "${STAGE}/target/${APP}.jar"
# 脚本与文档同样取自构建所用的那棵树，保证「包内所有文件都对应同一个提交」
cp "${SRC_DIR}/start.sh" "${SRC_DIR}/stop.sh" "${SRC_DIR}/README.md" "$STAGE/"
# 示例配置：与 jar 内置默认配置同源，用户可修改后通过 --spring.config.location 覆盖
cp "${SRC_DIR}/src/main/resources/application.yml" "${STAGE}/application.yml"
if [ -d "${SRC_DIR}/docs" ]; then cp -R "${SRC_DIR}/docs" "${STAGE}/docs"; fi

# 注意：定界符带引号，模板内的反引号 / $(...) 都是纯文本，不会被执行
cat > "${STAGE}/INSTALL.md" <<'INSTALL_TEMPLATE'
# @@APP@@ @@VERSION@@ 安装与运行说明

## 环境要求

- JDK 21+（`java -version` 确认）
- 默认监听端口 9996（可用 `./start.sh 8080` 或 `PORT=8080 ./start.sh` 修改）

## 目录说明

```
@@APP@@-@@VERSION@@/
├── target/@@APP@@.jar     # Spring Boot 可执行 jar（start.sh 默认加载此路径）
├── start.sh               # 启动脚本（后台运行 + 健康检查）
├── stop.sh                # 停止脚本（优雅停止并等待端口释放）
├── application.yml        # 示例配置（可修改后通过启动参数覆盖）
├── README.md              # 项目说明
├── docs/                  # 截图等文档
└── INSTALL.md             # 本文件
```

包内保持与源码仓库一致的 `target/` 目录结构，因此 start.sh / stop.sh 无需任何修改即可直接使用。

## 快速开始

```bash
tar -xzf @@APP@@-@@VERSION@@.tar.gz      # 或 unzip @@APP@@-@@VERSION@@.zip
cd @@APP@@-@@VERSION@@
chmod +x start.sh stop.sh
./start.sh                # 默认 9996 端口
curl http://127.0.0.1:9996/api/health
./stop.sh
```

## 运行时文件（不随包发布）

- `.code-association/`：默认数据目录（可用 `DATA_DIR=/path ./start.sh` 修改）
- `code-association.log`：运行日志（可用 `LOG_FILE=/path/log ./start.sh` 修改）
- `.code-association.pid`：进程号文件，start.sh 写入、stop.sh 清理

## 每日自动扫描（schedule）

服务内置每日自动扫描：每天 **02:30** 检查所有程序，只分析「自上次分析以来有新提交」的那些，
无需人工点按钮。相关参数全部由 `code-association.schedule.*` 控制：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `code-association.schedule.enabled` | `true` | 是否启用每日扫描 |
| `code-association.schedule.cron` | `0 30 2 * * *` | Spring cron（秒 分 时 日 月 周），默认每天 02:30 |
| `code-association.schedule.run-on-startup` | `false` | 启动后是否立刻补跑一次 |
| `code-association.schedule.max-programs-per-run` | `20` | 单次扫描最多分析多少个程序 |
| `code-association.schedule.directory-rescan-minutes` | `1440` | 普通目录（无提交可比较）的重新扫描间隔（分钟） |

### 查看与手动触发

```bash
# 查看调度状态：下次执行时间、是否正在运行、上次结果
curl http://127.0.0.1:9996/api/schedule

# 立即手动触发一次扫描（异步执行，结果通过上面的接口轮询）
curl -X POST http://127.0.0.1:9996/api/schedule/run
```

修改扫描时间：

```bash
# 改成每天 03:00，并关闭启动时补跑
java -jar target/@@APP@@.jar \
  --code-association.schedule.cron="0 0 3 * * *" \
  --code-association.schedule.run-on-startup=false
```

## 自定义配置

```bash
# 方式一：命令行参数
java -jar target/@@APP@@.jar --server.port=8080 --code-association.data-dir=/data/ca

# 方式二：外部配置文件
java -jar target/@@APP@@.jar --spring.config.location=file:./application.yml
```
INSTALL_TEMPLATE
render "${STAGE}/INSTALL.md"

# 统一包内权限：源文件权限可能是 600/711，直接打包会让别人无法读取
find "$STAGE" -type d -exec chmod 755 {} +
find "$STAGE" -type f -exec chmod 644 {} +
# 包内脚本必须可执行
chmod 755 "${STAGE}/start.sh" "${STAGE}/stop.sh"

# ---------- 3. 生成压缩包 ----------
mkdir -p "$RELEASE_ROOT"
rm -f "$TARBALL" "$ZIPBALL"

log "生成 $TARBALL"
# COPYFILE_DISABLE / --no-xattrs: 避免 macOS 打包出 ._ 资源分叉文件
COPYFILE_DISABLE=1 tar --no-xattrs -czf "$TARBALL" -C "$RELEASE_ROOT" "${APP}-${VERSION}"

log "生成 $ZIPBALL"
( cd "$RELEASE_ROOT" && zip -qrX "$(basename "$ZIPBALL")" "${APP}-${VERSION}" )

# ---------- 4. 校验压缩包 ----------
log "校验压缩包内容"
for archive in "$TARBALL" "$ZIPBALL"; do
  case "$archive" in
    *.tar.gz) LISTING="$(tar -tzf "$archive")" ;;
    *.zip)    LISTING="$(unzip -Z1 "$archive")" ;;
    *) die "未知压缩格式: $archive" ;;
  esac

  for entry in start.sh stop.sh; do
    printf '%s\n' "$LISTING" | grep -qx "${APP}-${VERSION}/${entry}" \
      || die "$(basename "$archive") 中缺少 ${APP}-${VERSION}/${entry}"
  done
  printf '%s\n' "$LISTING" | grep -qx "${APP}-${VERSION}/target/${APP}.jar" \
    || die "$(basename "$archive") 中缺少 ${APP}-${VERSION}/target/${APP}.jar"

  echo "    OK  $(basename "$archive")：start.sh / stop.sh / target/${APP}.jar 均在 ${APP}-${VERSION}/ 下"
done

# 配置必须带上每日扫描（schedule）配置块，否则打包出来的示例配置是旧的
grep -q 'schedule:' "${STAGE}/application.yml" \
  || die "包内 application.yml 缺少 schedule 配置块（应来自 src/main/resources/application.yml）"
grep -q 'cron: "0 30 2 \* \* \*"' "${STAGE}/application.yml" \
  || die "包内 application.yml 的 schedule.cron 不是预期的每日 02:30"
echo "    OK  包内 application.yml 含 schedule 配置块（默认 02:30）"

# ---------- 5. 发布到 GitHub Release ----------
cat > "$NOTES" <<'NOTES_TEMPLATE'
## @@APP@@ @@VERSION@@

跨程序变更影响扫描工具（Java / Maven 多模块仓库）：分析一次提交改动了哪些类、方法、SQL、MQ 消息结构，
并推导出对其他模块 / 服务的连带影响与风险等级。

### 本次修复（0.2.1）

0.2.0 的每日扫描有几个实质问题，本次全部修复：

- **提交持久化（关键）**：分析结束时保存 `base_commit` / `head_commit`。此前不保存，
  扫描无法区分「这个提交已经分析过」和「从未分析过」，导致**每个程序每天都被重新分析一遍**。
- **零宽限恢复**：`recoverStaleAnalyses(0)` 现在表示「全部」，不再被当成「1 分钟前」，
  避免刚产生的中断记录一直停留在 RUNNING。
- **位置缺失保护**：源目录已不存在时跳过并给出原因（「位置不可访问」），不再每个周期重试失败。
- **列表查询提速**：`analyses()` / `analysesOfSource()` 不再 SELECT 报告正文
  （单个报告约 7MB，`/api/analyses` 曾超时 >60s，现约 21ms）。
- 新增 `ScheduledScanTest`（4 个用例）：扫描 / 跳过 / 位置缺失 / 孤儿恢复。

实测：扫描耗时 70s，结果 scanned 0 / skipped 4（3 个「没有新提交」+ 1 个「位置不可访问」）；
修复前是每次都 scanned 4 / skipped 0。

### 每日自动扫描

- 每天 **02:30** 自动检查所有程序，只分析有新提交的那些，无需人工触发。
  由 `code-association.schedule.*` 控制（`enabled` / `cron` / `run-on-startup` /
  `max-programs-per-run` / `directory-rescan-minutes`）。
- 手动触发一次扫描：`curl -X POST http://127.0.0.1:9996/api/schedule/run`；
  查看调度状态（下次执行时间、是否运行中、上次结果）：`curl http://127.0.0.1:9996/api/schedule`。
- 影响范围**仅跨程序（peer-only）**，不把本地自身影响计入结果。

### 下载

| 文件 | 说明 |
| --- | --- |
| `@@APP@@-@@VERSION@@.tar.gz` | Linux / macOS 推荐 |
| `@@APP@@-@@VERSION@@.zip` | 通用格式（Windows / 通用） |

两个包内容一致，均包含：可执行 jar（`target/@@APP@@.jar`）、`start.sh`、`stop.sh`、
`application.yml` 示例配置、`README.md`、`docs/` 与 `INSTALL.md`。

### 运行

```bash
tar -xzf @@APP@@-@@VERSION@@.tar.gz   # 或 unzip @@APP@@-@@VERSION@@.zip
cd @@APP@@-@@VERSION@@
chmod +x start.sh stop.sh
./start.sh                          # 默认 http://127.0.0.1:9996
./stop.sh
```

需要 JDK 21+。详见包内 `INSTALL.md`。
NOTES_TEMPLATE
render "$NOTES"

log "发布 GitHub Release: $TAG"
if gh release view "$TAG" >/dev/null 2>&1; then
  echo "    Release $TAG 已存在，更新标题与说明并覆盖资产"
  gh release edit "$TAG" --title "${APP} ${VERSION}" --notes-file "$NOTES"
  gh release upload "$TAG" "$TARBALL" "$ZIPBALL" --clobber
else
  gh release create "$TAG" \
    --title "${APP} ${VERSION}" \
    --notes-file "$NOTES" \
    --target "$TARGET_REF" \
    "$TARBALL" "$ZIPBALL"
fi

# ---------- 6. 汇总 ----------
# 到这里发布已经成功；下面只是展示信息，读取失败不应让脚本以非 0 退出
RELEASE_URL="$(gh release view "$TAG" --json url -q .url 2>/dev/null || echo "https://github.com/${REPO}/releases/tag/${TAG}")"
echo
echo "发布完成"
echo "  仓库:    https://github.com/${REPO}"
echo "  Release: ${RELEASE_URL}"
echo "  资产:"
gh release view "$TAG" --json assets -q '.assets[] | "    - " + .name + "  (" + (.size|tostring) + " bytes)"' 2>/dev/null || echo "    （无法读取资产列表，请用 gh release view $TAG 查看）"
echo "  本地包:"
ls -lh "$TARBALL" "$ZIPBALL" | awk '{print "    "$9 "  "$5}'
