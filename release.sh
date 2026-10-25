#!/usr/bin/env bash
#
# release.sh — 构建、打包并发布 code-association 到 GitHub Release
#
# 用法:
#   ./release.sh                  # 发布默认版本 0.1.0
#   ./release.sh 0.2.0            # 发布指定版本
#   VERSION=0.2.0 ./release.sh    # 同上（环境变量方式）
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
# 依赖: bash、mvn、tar、zip、unzip、gh（已登录）
set -euo pipefail

cd "$(dirname "$0")"

APP="code-association"
VERSION="${1:-${VERSION:-0.1.0}}"
TAG="v${VERSION}"
RELEASE_ROOT="build/release"
STAGE="${RELEASE_ROOT}/${APP}-${VERSION}"
TARBALL="${RELEASE_ROOT}/${APP}-${VERSION}.tar.gz"
ZIPBALL="${RELEASE_ROOT}/${APP}-${VERSION}.zip"
NOTES="${RELEASE_ROOT}/release-notes-${TAG}.md"

die() { echo "错误: $*" >&2; exit 1; }
log() { echo "==> $*"; }

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

# ---------- 1. 构建 jar ----------
log "构建 jar: mvn -q -DskipTests package"
mvn -q -DskipTests package
JAR="target/${APP}.jar"
[ -f "$JAR" ] || die "构建完成但找不到 $JAR"

# ---------- 2. 组装发布目录 ----------
log "组装发布目录: $STAGE"
rm -rf "$STAGE"
mkdir -p "${STAGE}/target"

cp "$JAR" "${STAGE}/target/${APP}.jar"
cp start.sh stop.sh README.md "$STAGE/"
# 示例配置：与 jar 内置默认配置同源，用户可修改后通过 --spring.config.location 覆盖
cp src/main/resources/application.yml "${STAGE}/application.yml"
[ -d docs ] && cp -R docs "${STAGE}/docs"

cat > "${STAGE}/INSTALL.md" <<EOF
# ${APP} ${VERSION} 安装与运行说明

## 环境要求

- JDK 21+（`java -version` 确认）
- 默认监听端口 9996（可用 `./start.sh 8080` 或 `PORT=8080 ./start.sh` 修改）

## 目录说明

```
${APP}-${VERSION}/
├── target/${APP}.jar     # Spring Boot 可执行 jar（start.sh 默认加载此路径）
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
tar -xzf ${APP}-${VERSION}.tar.gz      # 或 unzip ${APP}-${VERSION}.zip
cd ${APP}-${VERSION}
chmod +x start.sh stop.sh
./start.sh                # 默认 9996 端口
curl http://127.0.0.1:9996/api/health
./stop.sh
```

## 运行时文件（不随包发布）

- `.code-association/`：默认数据目录（可用 `DATA_DIR=/path ./start.sh` 修改）
- `code-association.log`：运行日志（可用 `LOG_FILE=/path/log ./start.sh` 修改）
- `.code-association.pid`：进程号文件，start.sh 写入、stop.sh 清理

## 自定义配置

```bash
# 方式一：命令行参数
java -jar target/${APP}.jar --server.port=8080 --code-association.data-dir=/data/ca

# 方式二：外部配置文件
java -jar target/${APP}.jar --spring.config.location=file:./application.yml
```
EOF

# 包内脚本必须可执行
chmod +x "${STAGE}/start.sh" "${STAGE}/stop.sh"

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

# ---------- 5. 发布到 GitHub Release ----------
cat > "$NOTES" <<EOF
## ${APP} ${VERSION}

跨程序变更影响扫描工具（Java / Maven 多模块仓库）：分析一次提交改动了哪些类、方法、SQL、MQ 消息结构，
并推导出对其他模块 / 服务的连带影响与风险等级。

### 下载

| 文件 | 说明 |
| --- | --- |
| `${APP}-${VERSION}.tar.gz` | Linux / macOS 推荐 |
| `${APP}-${VERSION}.zip` | 通用格式（Windows / 通用） |

两个包内容一致，均包含：可执行 jar（`target/${APP}.jar`）、`start.sh`、`stop.sh`、
`application.yml` 示例配置、`README.md`、`docs/` 与 `INSTALL.md`。

### 运行

```bash
tar -xzf ${APP}-${VERSION}.tar.gz   # 或 unzip ${APP}-${VERSION}.zip
cd ${APP}-${VERSION}
chmod +x start.sh stop.sh
./start.sh                          # 默认 http://127.0.0.1:9996
./stop.sh
```

需要 JDK 21+。详见包内 `INSTALL.md`。
EOF

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
RELEASE_URL="$(gh release view "$TAG" --json url -q .url)"
echo
echo "发布完成"
echo "  仓库:    https://github.com/${REPO}"
echo "  Release: ${RELEASE_URL}"
echo "  资产:"
gh release view "$TAG" --json assets -q '.assets[] | "    - " + .name + "  (" + (.size|tostring) + " bytes)"'
echo "  本地包:"
ls -lh "$TARBALL" "$ZIPBALL" | awk '{print "    " $9 "  " $5}'
