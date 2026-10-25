#!/usr/bin/env bash
#
# 把 docs/wiki/ 同步到 GitHub Wiki。
#
#   ./scripts/sync-wiki.sh
#
# 前置条件：Wiki 仓库必须已经存在。GitHub 不允许通过 API 初始化 Wiki，
# 需要先在网页端打开 https://github.com/geek-xin/code-association/wiki
# 点「Create the first page」保存一次，仓库才会被创建。
#
# 为什么需要脚本：docs/wiki/ 里的互链带 .md 后缀（这样在仓库里能直接点开），
# 而 Wiki 页面之间用裸页面名互链。脚本同步时会去掉后缀。
set -euo pipefail

cd "$(dirname "$0")/.."

WIKI_REMOTE="${WIKI_REMOTE:-git@github.com:geek-xin/code-association.wiki.git}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> 克隆 wiki 仓库"
if ! GIT_SSH_COMMAND='ssh -o StrictHostKeyChecking=no -o ConnectTimeout=30' \
     git clone "$WIKI_REMOTE" "$WORK/wiki" 2>/dev/null; then
  cat >&2 <<'MSG'
无法克隆 wiki 仓库。这通常意味着 Wiki 还没有初始化。

请先在浏览器打开：
  https://github.com/geek-xin/code-association/wiki
点击「Create the first page」，随便写一个标题保存。之后本脚本就能工作了。
MSG
  exit 1
fi

echo "==> 复制页面（去掉 .md 后缀的互链）"
for f in docs/wiki/*.md; do
  name="$(basename "$f")"
  # README.md 是仓库内的索引页，不同步到 wiki
  if [ "$name" = "README.md" ]; then
    continue
  fi
  # [页面](页面.md) -> [页面](页面)
  sed -E 's/\]\(([^)]+)\.md\)/](\1)/g' "$f" > "$WORK/wiki/$name"
done

cd "$WORK/wiki"
git add -A
if git diff --cached --quiet; then
  echo "==> 没有变化"
  exit 0
fi
git -c user.name='geek-xin' -c user.email='geek-xin@users.noreply.github.com' \
    commit -q -m "docs: sync wiki from docs/wiki"
GIT_SSH_COMMAND='ssh -o StrictHostKeyChecking=no' git push origin HEAD 2>&1 | tail -3

echo "==> 完成：https://github.com/geek-xin/code-association/wiki"
