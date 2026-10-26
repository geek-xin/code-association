#!/usr/bin/env bash
#
# 停止 code-association 服务（跨平台：Linux 与 macOS 共用同一套脚本）。
#
#   ./stop.sh              # 默认 9996 端口
#   ./stop.sh 8080         # 指定端口
#   PORT=8080 ./stop.sh    # 同上
#
# 关键点：不能杀完进程就返回。Spring Boot 的优雅关闭需要 1~3 秒，
# 端口在这期间仍处于监听状态，紧接着启动新实例会撞上 "Port already in use"。
# 所以这里会等到端口真正释放后再退出。
#
# 各平台分别走哪条路径（每处都先探测，缺工具就降级，不硬依赖）：
#
#   找 PID    pid 文件 → lsof（Linux/macOS）→ ss -ltnp（Linux）→ netstat -tlnp（Linux 带 PID）
#             → pgrep / ps + 命令行匹配 jar 名（最后兜底）
#   端口状态  lsof → ss → netstat -an（Linux/BSD 通用，但拿不到 PID）→ bash 内建 /dev/tcp
#   数字循环  一律 bash 算术 while，不用 seq
#
# 说明：与 start.sh 里的探测函数刻意重复一份，避免为了共享代码再引入第三个文件。
set -uo pipefail

cd "$(dirname "$0")"

PID_FILE="./.code-association.pid"
PORT="${1:-${PORT:-9996}}"
JAR_NAME="code-association.jar"

case "$PORT" in
  ''|*[!0-9]*)
    echo "端口无效：$PORT（应为纯数字，例如 ./stop.sh 8080）"
    exit 1
    ;;
esac

# ---------- 工具可用性探测 ----------
have() { command -v "$1" >/dev/null 2>&1; }

HAVE_LSOF=0;     if have lsof;     then HAVE_LSOF=1;     fi
HAVE_SS=0;       if have ss;       then HAVE_SS=1;       fi
HAVE_NETSTAT=0;  if have netstat;  then HAVE_NETSTAT=1;  fi
HAVE_PGREP=0;    if have pgrep;    then HAVE_PGREP=1;    fi
HAVE_PS=0;       if have ps;       then HAVE_PS=1;       fi
HAVE_AWK=0;      if have awk;      then HAVE_AWK=1;      fi

HAVE_TCPDEV=1
TCPDEV_ERR="$( (exec 3<>/dev/tcp/127.0.0.1/1) 2>&1 )" || true
case "$TCPDEV_ERR" in
  *"No such file or directory"*|*"not supported"*) HAVE_TCPDEV=0 ;;
esac

# ---------- 端口 / 进程探测 ----------

listener_pids() {
  if [ "$HAVE_LSOF" = 1 ]; then
    lsof -nP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | awk 'NR > 1 { print $2 }' || true
    return 0
  fi
  if [ "$HAVE_SS" = 1 ]; then
    ss -ltnp 2>/dev/null | awk -v p=":$PORT" '
      NR > 1 && substr($4, length($4) - length(p) + 1) == p {
        if (match($0, /pid=[0-9]+/)) print substr($0, RSTART + 4, RLENGTH - 4)
      }' || true
    return 0
  fi
  if [ "$HAVE_NETSTAT" = 1 ]; then
    netstat -tlnp 2>/dev/null | awk -v p=":$PORT" '
      substr($4, length($4) - length(p) + 1) == p && $NF ~ /^[0-9]+\// { split($NF, a, "/"); print a[1] }' || true
    return 0
  fi
  return 0
}

tcp_connect() {
  local err rc
  rc=0
  err="$( (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>&1 )" || rc=$?
  if [ "$rc" -eq 0 ]; then return 0; fi
  case "$err" in
    *"No such file or directory"*|*"not supported"*) return 2 ;;
  esac
  return 1
}

port_state() {
  if [ "$HAVE_LSOF" = 1 ]; then
    if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then echo busy; else echo free; fi
    return 0
  fi
  if [ "$HAVE_SS" = 1 ]; then
    if ss -ltn 2>/dev/null | awk -v p=":$PORT" '
         NR > 1 && substr($4, length($4) - length(p) + 1) == p { f = 1 }
         END { exit !f }'; then
      echo busy
    else
      echo free
    fi
    return 0
  fi
  if [ "$HAVE_NETSTAT" = 1 ]; then
    if netstat -an 2>/dev/null | awk -v p=":$PORT" -v q=".$PORT" '
         /LISTEN/ {
           for (i = 1; i <= NF; i++) {
             a = $i; n = length(a)
             if (substr(a, n - length(p) + 1) == p || substr(a, n - length(q) + 1) == q) { f = 1 }
           }
         }
         END { exit !f }'; then
      echo busy
    else
      echo free
    fi
    return 0
  fi
  local rc
  rc=0
  tcp_connect || rc=$?
  case "$rc" in
    0) echo busy ;;
    1) echo free ;;
    *) echo unknown ;;
  esac
  return 0
}

# 按命令行匹配本服务的 java 进程。两点讲究：
#   1) 要求「java ... code-association.jar」连起来，避免把日志里、编辑器里或
#      grep/tail 命令行里出现的同名文件当成服务进程而误杀；
#   2) 模式拆成两段在 awk 里拼，避免 awk/ps 自己的命令行带上整串模式造成自匹配。
jar_pids() {
  local p1 p2
  p1="code-association"
  p2=".jar"
  if [ "$HAVE_PGREP" = 1 ]; then
    pgrep -f "java.*$p1[.]jar" 2>/dev/null || true
    return 0
  fi
  if [ "$HAVE_PS" = 1 ] && [ "$HAVE_AWK" = 1 ]; then
    ps -eo pid=,args= 2>/dev/null | awk -v a="$p1" -v b="$p2" -v me="$$" '
      $1 != me && index($0, "java") && index($0, a b) { print $1 }' || true
  fi
  return 0
}

first_pid() {
  # shellcheck disable=SC2086
  set -- $1
  printf '%s' "${1:-}"
}

# 校验 PID 是否确实是本服务的 java 进程，避免 PID 被复用后误杀无关进程。
# 拿不到命令行时信任调用方（pid 文件 / 端口反查结果）。
pid_is_ours() {
  local pid="$1" cmd
  if [ "$HAVE_PS" = 1 ]; then
    cmd="$(ps -p "$pid" -o args= 2>/dev/null || true)"
    if [ -n "$cmd" ]; then
      case "$cmd" in
        *"$JAR_NAME"*) return 0 ;;
        *) return 1 ;;
      esac
    fi
  fi
  return 0
}

# SIGTERM 先礼后兵，10 秒不退再 SIGKILL
stop_pid() {
  local pid="$1" waited=0
  kill -0 "$pid" 2>/dev/null || return 1
  kill -TERM "$pid" 2>/dev/null || true
  while [ "$waited" -lt 40 ]; do
    kill -0 "$pid" 2>/dev/null || return 0
    sleep 0.25
    waited=$((waited + 1))
  done
  echo "进程 $pid 10 秒内未退出，强制 SIGKILL"
  kill -KILL "$pid" 2>/dev/null || true
  sleep 1
  return 0
}

# ---------- 1. 优先按 pid 文件停止 ----------
KILLED=0

if [ -f "$PID_FILE" ]; then
  PID="$(tr -d '[:space:]' < "$PID_FILE" 2>/dev/null || true)"
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    if pid_is_ours "$PID"; then
      stop_pid "$PID"
      echo "已停止 (PID $PID)"
      KILLED=1
    else
      echo "PID 文件里的 $PID 不是本服务（PID 可能已被复用），跳过"
    fi
  else
    echo "进程不存在 (PID ${PID:-?})"
  fi
  rm -f "$PID_FILE"
fi

# ---------- 2. 没有 pid 文件 / 进程不对：按端口找，再按命令行兜底 ----------
if [ "$KILLED" = 0 ]; then
  STATE="$(port_state)"
  PIDS=""
  if [ "$STATE" = busy ]; then
    PIDS="$(listener_pids)"
  fi
  if [ -z "$PIDS" ]; then
    PIDS="$(jar_pids)"
  fi

  if [ -n "$PIDS" ]; then
    for p in $PIDS; do
      if pid_is_ours "$p"; then
        stop_pid "$p"
        echo "已停止 (PID $p)"
        KILLED=1
      else
        echo "跳过非本服务进程 $p"
      fi
    done
  fi

  if [ "$KILLED" = 0 ]; then
    if [ "$STATE" = busy ]; then
      echo "端口 $PORT 被占用，但无法定位其 PID（当前环境缺 lsof / ss / netstat -p）"
      echo "请手动处理，例如：ss -ltnp | grep :$PORT"
    elif [ "$STATE" = unknown ]; then
      echo "没有正在运行的服务（当前环境无法可靠探测端口状态）"
    else
      echo "没有正在运行的服务"
    fi
  fi
fi

# ---------- 3. 等端口真正释放（避免下一次启动撞端口） ----------
waited=0
while [ "$waited" -lt 40 ]; do
  STATE="$(port_state)"
  if [ "$STATE" != busy ]; then
    if [ "$STATE" = unknown ]; then
      echo "提示：当前环境无法可靠检测端口状态，跳过端口释放确认"
    fi
    echo "端口 $PORT 已释放"
    exit 0
  fi
  sleep 0.25
  waited=$((waited + 1))
done

echo "警告：端口 $PORT 仍被占用"
OWNERS="$(listener_pids)"
if [ -n "$OWNERS" ]; then
  echo "占用进程：$(printf '%s' "$OWNERS" | tr '\n' ' ')"
else
  echo "（当前环境无法列出占用进程，可用 ss -ltnp / lsof -iTCP:$PORT 查看）"
fi
exit 1
