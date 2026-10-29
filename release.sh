#!/usr/bin/env bash
#
# release.sh — 构建、打包并发布 code-association 到 GitHub Release
#
# 用法:
#   ./release.sh                  # 发布默认版本 0.9.0
#   ./release.sh 0.9.0            # 发布指定版本
#   VERSION=0.9.0 ./release.sh    # 同上（环境变量方式）
#
# 产物:
#   build/release/code-association-<version>/            # 干净的发布目录
#   build/release/code-association-<version>.tar.gz      # 压缩包（顶层目录 code-association-<version>/）
#   build/release/code-association-<version>.zip         # 同上
#
# 流程:
#   1. mvn -q -DskipTests package 构建 target/code-association.jar
#   2. 组装干净目录：jar、start.sh / stop.sh（Linux·macOS）、
#      start.ps1 / stop.ps1 / start.bat / stop.bat（Windows）、README.md、
#      application.yml、INSTALL.md、docs/
#   3. 生成 .tar.gz 与 .zip 两个压缩包（两者内容一致，Windows 用户用 zip）
#   4. 校验压缩包内确实包含两个平台的启停脚本（缺失则直接失败）
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
# 依赖: bash、mvn、tar、zip、unzip、gh（已登录）、git
# 说明: 本脚本是「发布者」用的，只在开发机上跑，不随发布包分发，
#       因此不必像 start.sh 那样做成跨平台（Linux/macOS 可跑，Windows 用 WSL/Git Bash）。
set -euo pipefail

cd "$(dirname "$0")"

APP="code-association"
VERSION="${1:-${VERSION:-0.9.0}}"
TAG="v${VERSION}"
RELEASE_ROOT="build/release"
STAGE="${RELEASE_ROOT}/${APP}-${VERSION}"
TARBALL="${RELEASE_ROOT}/${APP}-${VERSION}.tar.gz"
ZIPBALL="${RELEASE_ROOT}/${APP}-${VERSION}.zip"
# 独立 jar 资产：内置自动更新只认 *.jar，不认压缩包
JARBALL="${RELEASE_ROOT}/${APP}-${VERSION}.jar"
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
# 两个平台的启停脚本都进同一个包：tar.gz 与 zip 内容完全一致，
# Windows 用户用 zip 解压后跑 start.bat，Linux / macOS 用户跑 ./start.sh。
for f in start.sh stop.sh start.ps1 stop.ps1 start.bat stop.bat; do
  [ -f "${SRC_DIR}/$f" ] || die "源码树缺少 $f（两个平台的启停脚本都必须存在）"
  cp "${SRC_DIR}/$f" "$STAGE/"
done
cp "${SRC_DIR}/README.md" "$STAGE/"
# 示例配置：与 jar 内置默认配置同源，用户可修改后通过 --spring.config.location 覆盖
cp "${SRC_DIR}/src/main/resources/application.yml" "${STAGE}/application.yml"
if [ -d "${SRC_DIR}/docs" ]; then cp -R "${SRC_DIR}/docs" "${STAGE}/docs"; fi

# 注意：定界符带引号，模板内的反引号 / $(...) 都是纯文本，不会被执行
cat > "${STAGE}/INSTALL.md" <<'INSTALL_TEMPLATE'
# @@APP@@ @@VERSION@@ 安装与运行说明

## 环境要求

- JDK 21+（`java -version` 确认）
- 默认监听端口 9996
- 支持 Linux、macOS、Windows 三个平台，启动脚本随平台分别提供

## 目录说明

```
@@APP@@-@@VERSION@@/
├── target/@@APP@@.jar     # Spring Boot 可执行 jar（各启动脚本默认加载此路径）
├── start.sh  / stop.sh    # Linux / macOS 启停脚本（bash）
├── start.bat / stop.bat   # Windows 启停脚本（双击或命令行执行，内部调用 .ps1）
├── start.ps1 / stop.ps1   # Windows 启停脚本（PowerShell 实现）
├── application.yml        # 示例配置（可修改后通过启动参数覆盖）
├── README.md              # 项目说明
├── docs/                  # 截图等文档
└── INSTALL.md             # 本文件
```

两个平台的脚本读同一份 `target/@@APP@@.jar`，可以混着用（例如先在 Linux 上跑，把整个目录拷到 Windows 继续跑）；
但它们操作的是同一个 pid 文件与同一个端口，同一时间只应该有一个实例。

## 快速开始

### Linux / macOS

```bash
tar -xzf @@APP@@-@@VERSION@@.tar.gz      # 或 unzip @@APP@@-@@VERSION@@.zip
cd @@APP@@-@@VERSION@@
chmod +x start.sh stop.sh
./start.sh                # 默认 9996 端口
./start.sh 8080           # 指定端口
PORT=8080 ./start.sh      # 同上，环境变量方式
curl http://127.0.0.1:9996/api/health
./stop.sh                 # 停止（默认 9996 端口）
./stop.sh 8080            # 停止 8080 上的实例
```

脚本不硬依赖 `python3` / `lsof` / `pgrep` / `seq` / `curl`：每一项都会先探测本机有什么，
再选可用的实现。Linux 上优先用 `setsid` 脱离终端，macOS（没有 `setsid`）用 `python3` 兜底，
两者都没有时退化为 `nohup` 并提示「关闭终端可能带走服务」。

### Windows

```bat
:: 解压 zip 后，在解压出来的目录里执行
start.bat                 :: 默认 9996 端口
start.bat 8080            :: 指定端口
stop.bat                  :: 停止
stop.bat 8080             :: 停止 8080 上的实例
```

也可以直接用 PowerShell（参数同名）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start.ps1 -Port 8080
powershell -NoProfile -ExecutionPolicy Bypass -File .\stop.ps1 -Port 8080
```

`start.bat` / `stop.bat` 只是薄封装，会自动带 `-ExecutionPolicy Bypass`，
所以即使系统策略禁止运行脚本也能用，不需要改注册表。
Windows 上 `start.ps1` 以隐藏窗口在后台启动 java，stdout / stderr 分别重定向到
`logs/console.log` / `logs/console.err.log`（句柄由 java 直接持有，脚本退出后照常写入），
并把反查到的真实 java 进程 PID 写入 `.code-association.pid`。这两份是**进程原始输出**，
每次启动截断；应用日志由 logback 写到 `logs/code-association.log`（按天滚动、保留 7 天）。

### 前台运行（三平台通用，调试用）

```bash
java -jar target/@@APP@@.jar --server.port=9996
```

## 运行时文件（不随包发布）

| 文件 | 用途 | 覆盖方式 |
| --- | --- | --- |
| `.code-association/` | 默认数据目录（SQLite、克隆的仓库） | `DATA_DIR=/path`（Windows：`set DATA_DIR=D:\path`） |
| `logs/` | 应用日志与进程输出：`code-association.log`、`code-association-error.log`、`archive/*.log.gz`（保留 7 天）、`console.log` | `LOG_DIR=/path`（Windows 同理）；进程输出另有 `LOG_FILE=/path/console.log` |
| `.code-association.pid` | 进程号文件，启动脚本写入、停止脚本清理 | 不建议手工修改 |

> Windows 上的 `start.ps1` 会把 stderr 单独写到 `logs/console.err.log`，
> 便于区分启动期错误与正常输出；两个 console 文件都在启动时被截断重写。
> 应用日志（`logs/code-association.log`）不截断，按天滚动并自动保留 7 天。

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
# 包内脚本必须可执行（.ps1 / .bat 在 Windows 上不看这个位，但保持一致也无害）
chmod 755 "${STAGE}/start.sh" "${STAGE}/stop.sh"
chmod 644 "${STAGE}/start.ps1" "${STAGE}/stop.ps1" "${STAGE}/start.bat" "${STAGE}/stop.bat"

# ---------- 3. 生成压缩包 ----------
mkdir -p "$RELEASE_ROOT"
rm -f "$TARBALL" "$ZIPBALL" "$JARBALL"

# 单独发布一个 jar 资产。
# 为什么需要：应用内置的「自动更新」只会替换 jar，它从 release 资产里找 *.jar。
# 如果只发 tar.gz / zip，自动更新就没有可下载的东西（tar/zip 里虽然也有 jar，
# 但要先解包，服务自己不该做这件事），功能等于废的。
log "生成 $JARBALL"
cp "$JAR" "$JARBALL"

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

  # 两个平台的启停脚本都必须进包：Linux/macOS 用 .sh，Windows 用 .bat/.ps1
  for entry in start.sh stop.sh start.ps1 stop.ps1 start.bat stop.bat; do
    printf '%s\n' "$LISTING" | grep -qx "${APP}-${VERSION}/${entry}" \
      || die "$(basename "$archive") 中缺少 ${APP}-${VERSION}/${entry}"
  done
  printf '%s\n' "$LISTING" | grep -qx "${APP}-${VERSION}/target/${APP}.jar" \
    || die "$(basename "$archive") 中缺少 ${APP}-${VERSION}/target/${APP}.jar"

  echo "    OK  $(basename "$archive")：start.sh / stop.sh / start.ps1 / stop.ps1 / start.bat / stop.bat / target/${APP}.jar 均在 ${APP}-${VERSION}/ 下"
done

# 独立 jar 必须是可运行的 Spring Boot 包（内含 BOOT-INF/），而不是普通的薄 jar。
# 自动更新会把下载到的 jar 直接替换到运行位置，装错了服务就起不来。
[ -f "$JARBALL" ] || die "缺少独立 jar 资产：$JARBALL"
# 先把清单读进变量再 grep，不要写成 `unzip ... | grep -q`：
# grep -q 命中后立即退出，unzip 收到 SIGPIPE 被杀，在 pipefail 下整条管道返回 141，
# 于是「校验通过」会被误判成失败（这个坑在写这一行时真踩了一次）。
JAR_LISTING="$(unzip -Z1 "$JARBALL" 2>/dev/null || true)"
printf '%s\n' "$JAR_LISTING" | grep -qx "BOOT-INF/classes/com/geek/association/CodeAssociationApplication.class" \
  || die "$(basename "$JARBALL") 不是可运行的 Spring Boot jar（缺少 BOOT-INF/classes/.../CodeAssociationApplication.class）"
if [ "$(head -c 2 "$JARBALL")" != "PK" ]; then
  die "$(basename "$JARBALL") 不是 zip/jar 格式（自动更新会校验文件头，坏的进不去）"
fi
echo "    OK  $(basename "$JARBALL")：可运行的 Spring Boot jar（自动更新用这个资产）"

# 配置必须带上每日扫描（schedule）配置块，否则打包出来的示例配置是旧的
grep -q 'schedule:' "${STAGE}/application.yml" \
  || die "包内 application.yml 缺少 schedule 配置块（应来自 src/main/resources/application.yml）"
grep -q 'cron: "0 30 2 \* \* \*"' "${STAGE}/application.yml" \
  || die "包内 application.yml 的 schedule.cron 不是预期的每日 02:30"
echo "    OK  包内 application.yml 含 schedule 配置块（默认 02:30）"

# Windows 脚本的编码是硬约束：PowerShell 5.1 会把无 BOM 的文件按 ANSI 解码，
# 中文注释会变成乱码甚至导致语法错误；.bat 则要避开代码页问题，必须是纯 ASCII。
# 这里在发布前用字节级检查兜底，避免把坏文件发出去。
for f in start.ps1 stop.ps1; do
  head -c 3 "${STAGE}/$f" | od -An -tx1 | tr -d ' \n' | grep -qx 'efbbbf' \
    || die "包内 $f 缺少 UTF-8 BOM（Windows PowerShell 5.1 会按 ANSI 解码导致乱码）"
done
for f in start.bat stop.bat; do
  # 用 tr 删掉全部 ASCII 字节，剩下的若不为空就说明有非 ASCII 字符
  if [ -n "$(LC_ALL=C tr -d '\000-\177' < "${STAGE}/$f")" ]; then
    die "包内 $f 含非 ASCII 字符（bat 必须纯 ASCII，否则中文代码页下会乱码）"
  fi
done
for f in start.ps1 stop.ps1 start.bat stop.bat; do
  if ! grep -q $'\r' "${STAGE}/$f"; then
    die "包内 $f 不是 CRLF 换行（Windows 记事本/旧版 PowerShell 会出问题）"
  fi
done
echo "    OK  包内 Windows 脚本编码正确（ps1 带 BOM、bat 纯 ASCII、均为 CRLF）"

# ---------- 5. 发布到 GitHub Release ----------
# 打包自检：SKIP_PUBLISH=1 时只构建并校验压缩包，不碰远端。
# 用途是验证「包内容对不对」，避免为了看一眼包而真的发一个 Release 出去。
cat > "$NOTES" <<'NOTES_TEMPLATE'
## @@APP@@ @@VERSION@@

跨程序变更影响扫描工具（Java / Maven 多模块仓库）：分析一次提交改动了哪些类、方法、
SQL、MQ 消息结构，并推导出对其他模块 / 服务的连带影响与风险等级。

这一版新增 GitLab 私有仓库令牌设置，并重做左侧导航与总览影响卡；接口向后兼容。

### 新增：设置页配置 GitLab 个人访问令牌

私有 GitLab 仓库不再必须先导出 `GITLAB_TOKEN` 再重启。设置页新增「GitLab 私有仓库」卡片：
粘贴个人访问令牌（PAT，scope 至少 `read_repository`）保存即生效，克隆、fetch、列分支都使用它；
只对来源类型 `GITLAB` 生效，`GITHUB` / `GIT` / `LOCAL_*` 不会附加。

令牌存 `{data-dir}/gitlab-settings.json`（原子写、POSIX 600），`GET/PUT /api/gitlab` 只回显掩码；
代码源「凭据环境变量」有值时优先，否则回退到设置页令牌或 yml 的 `code-association.gitlab.token`
（默认读 `GITLAB_TOKEN`）。自建 GitLab 的 `http://` 与 `https://` 地址都支持。

### 改进：单侧栏，默认收起

原来 68px 图标栏 + 268px 文字菜单合成一条 288px 紫色侧栏：展开显示图标 + 标签 + 搜索 +
代码源/状态/最近分析，收起只剩 72px 图标列。**默认收起**；展开后写入 `localStorage` 的
`ca.nav`，下次保持。折叠/展开按钮都在侧栏顶部，状态与 `aria-expanded` 同步。

### 改进：总览影响卡合并

「其他程序的待处理影响」与「对其他程序的影响」合并为一张卡：先给每个对比程序一枚
契约数 / 影响处数汇总 pill，再按风险列出前 6 条影响并标注待复核 / 已确认 / 已忽略；
完整的契约命中、使用点与逐跳传播仍在「影响审核」页。

### 改进：只保留一个添加入口

去掉「代码源」分组标题里重复的加号，只保留侧栏顶部页面标题旁的添加按钮。

### 下载

| 文件 | 说明 |
| --- | --- |
| `@@APP@@-@@VERSION@@.jar` | 可执行 jar 单文件，**应用内「自动更新」用的就是它** |
| `@@APP@@-@@VERSION@@.zip` | **Windows 推荐**（自带解压即可，保留 CRLF） |
| `@@APP@@-@@VERSION@@.tar.gz` | Linux / macOS 推荐 |

两个压缩包内容完全一致，均包含：可执行 jar、`start.sh` / `stop.sh`、
`start.bat` / `stop.bat` / `start.ps1` / `stop.ps1`、`application.yml` 示例配置、
`README.md`、`docs/` 与 `INSTALL.md`。

### 运行

**Linux / macOS**

```bash
tar -xzf @@APP@@-@@VERSION@@.tar.gz   # 或 unzip @@APP@@-@@VERSION@@.zip
cd @@APP@@-@@VERSION@@
chmod +x start.sh stop.sh
./start.sh                          # 默认 http://127.0.0.1:9996
./stop.sh
```

**Windows**

```bat
:: 解压 zip，进入解压目录
start.bat                           :: 默认 http://127.0.0.1:9996
stop.bat
```

需要 JDK 21+。详见包内 `INSTALL.md`。

### 升级说明

从 0.3.0 起可以直接用应用内更新：设置 → 版本与更新 → 应用更新 → 按提示重启。
**0.6.0 起换完 jar 会自己重启**（`auto-restart` 默认开），失败自动回滚到备份 jar；
更早的版本换完只会提示你自己执行 `stop.sh` / `start.sh`。

本版新增 `code-association.gitlab.token` 配置（默认读 `GITLAB_TOKEN`），已有配置不受影响；
设置页令牌保存在数据目录的 `gitlab-settings.json`，删除该文件重启即可回退到 yml / 环境变量。
历史报告不会重写——请重新分析一次，新的侧栏、总览与 GitLab 令牌才会生效。
NOTES_TEMPLATE
render "$NOTES"

# 只验包不发布：本地产物与校验都已完成，到这里直接收尾
if [ "${SKIP_PUBLISH:-0}" = "1" ]; then
  echo
  echo "SKIP_PUBLISH=1：已完成构建、打包与全部校验，跳过 GitHub 发布。"
  echo "  发布目录: $STAGE"
  echo "  tar.gz:   $TARBALL"
  echo "  zip:      $ZIPBALL"
  echo "  Release 说明草稿: $NOTES"
  exit 0
fi

log "发布 GitHub Release: $TAG"
if gh release view "$TAG" >/dev/null 2>&1; then
  echo "    Release $TAG 已存在，更新标题与说明并覆盖资产"
  gh release edit "$TAG" --title "${APP} ${VERSION}" --notes-file "$NOTES"
  gh release upload "$TAG" "$TARBALL" "$ZIPBALL" "$JARBALL" --clobber
else
  gh release create "$TAG" \
    --title "${APP} ${VERSION}" \
    --notes-file "$NOTES" \
    --target "$TARGET_REF" \
    "$TARBALL" "$ZIPBALL" "$JARBALL"
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
ls -lh "$TARBALL" "$ZIPBALL" "$JARBALL" | awk '{print "    "$9 "  "$5}'
