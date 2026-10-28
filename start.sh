#!/usr/bin/env bash
#
# 启动 code-association 服务（跨平台：Linux 与 macOS 共用同一套脚本）。
#
#   ./start.sh                 # 默认 9996 端口，数据目录 ./.code-association
#   ./start.sh 8080            # 指定端口
#   PORT=8080 ./start.sh       # 同上
#   DATA_DIR=/var/lib/ca LOG_FILE=/var/log/ca/console.log ./start.sh
#
# 各平台分别走哪条路径（每处都先探测，缺工具就降级，不硬依赖）：
#
#   脱离终端   Linux: setsid（存在则用）→ 没有则 python3 setsid + 双 fork
#              macOS: 没有 setsid 命令，直接走 python3 setsid + 双 fork
#              都没有: nohup 后台，并明确提示「关闭终端可能带走服务」
#   就绪探测   curl → bash 内建 /dev/tcp 手写 HTTP → 实在不行只看端口监听
#   监听 PID   lsof（Linux/macOS）→ ss -ltnp（Linux）→ netstat -tlnp（Linux 带 PID）
#              → 启动时的 $! / ps 命令行匹配（BSD/macOS 的 netstat 拿不到 PID，只作兜底）
#   数字循环   一律 bash 算术 while，不用 seq（BSD/GNU 行为差异 + 少一个依赖）
#
# 说明：只靠 nohup 不够。进程仍留在调用方的会话/进程组里，终端会话结束时
# 可能被一起清理，所以要尽量让 java 成为独立会话的首进程。
set -euo pipefail

cd "$(dirname "$0")"

PORT="${1:-${PORT:-9996}}"
DATA_DIR="${DATA_DIR:-./.code-association}"
# LOG_FILE 是「进程原始输出」的捕获文件（stdout+stderr，每次启动截断），不是应用日志。
# 两者的分工：logback 初始化之前就失败的报错、JVM 崩溃输出只在这里；结构化应用日志由
# logback 写到 ${LOG_DIR:-./logs}/code-association.log（按天滚动、保留 7 天）。
# 默认落在同一个 logs/ 下，运维只需要看一个目录；要分开就用 LOG_FILE 覆盖。
LOG_FILE="${LOG_FILE:-./logs/console.log}"
PID_FILE="./.code-association.pid"
JAR="target/code-association.jar"
JAR_NAME="code-association.jar"

case "$PORT" in
  ''|*[!0-9]*)
    echo "端口无效：$PORT（应为纯数字，例如 ./start.sh 8080）"
    exit 1
    ;;
esac

# ---------- 工具可用性探测（用 if 形式，避免 set -e 在探测失败时误杀脚本） ----------
have() { command -v "$1" >/dev/null 2>&1; }

HAVE_SETSID=0;   if have setsid;   then HAVE_SETSID=1;   fi
HAVE_PYTHON3=0;  if have python3;  then HAVE_PYTHON3=1;  fi
HAVE_NOHUP=0;    if have nohup;    then HAVE_NOHUP=1;    fi
HAVE_CURL=0;     if have curl;     then HAVE_CURL=1;     fi
HAVE_LSOF=0;     if have lsof;     then HAVE_LSOF=1;     fi
HAVE_SS=0;       if have ss;       then HAVE_SS=1;       fi
HAVE_NETSTAT=0;  if have netstat;  then HAVE_NETSTAT=1;  fi
HAVE_PGREP=0;    if have pgrep;    then HAVE_PGREP=1;    fi
HAVE_PS=0;       if have ps;       then HAVE_PS=1;       fi
HAVE_AWK=0;      if have awk;      then HAVE_AWK=1;      fi

# 当前 bash 是否支持 /dev/tcp（编译时需 --enable-net-redirections）。
# 去连一个必然没开的端口：被拒绝说明支持；报 "No such file or directory" 说明不支持。
HAVE_TCPDEV=1
TCPDEV_ERR="$( (exec 3<>/dev/tcp/127.0.0.1/1) 2>&1 )" || true
case "$TCPDEV_ERR" in
  *"No such file or directory"*|*"not supported"*) HAVE_TCPDEV=0 ;;
esac

# ---------- 端口 / 进程探测 ----------

# 输出监听 $PORT 的 PID（可能多行，可能为空）。按可用工具逐级降级。
listener_pids() {
  if [ "$HAVE_LSOF" = 1 ]; then
    lsof -nP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | awk 'NR > 1 { print $2 }' || true
    return 0
  fi
  if [ "$HAVE_SS" = 1 ]; then
    # Linux：ss -ltnp 的 Process 列形如 users:(("java",pid=1234,fd=99))
    ss -ltnp 2>/dev/null | awk -v p=":$PORT" '
      NR > 1 && substr($4, length($4) - length(p) + 1) == p {
        if (match($0, /pid=[0-9]+/)) print substr($0, RSTART + 4, RLENGTH - 4)
      }' || true
    return 0
  fi
  if [ "$HAVE_NETSTAT" = 1 ]; then
    # Linux 的 netstat -tlnp 末列是 1234/java；BSD/macOS 没有 -p，这里只会输出空
    netstat -tlnp 2>/dev/null | awk -v p=":$PORT" '
      substr($4, length($4) - length(p) + 1) == p && $NF ~ /^[0-9]+\// { split($NF, a, "/"); print a[1] }' || true
    return 0
  fi
  return 0
}

# 用 bash 内建 /dev/tcp 探测端口能否连接。
# 返回 0=可连接(有监听) 1=拒绝连接(空闲) 2=当前 bash 不支持 /dev/tcp
tcp_connect() {
  local err rc
  rc=0
  err="$( (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>&1 )" || rc=$?
  if [ "$rc" -eq 0 ]; then
    return 0
  fi
  case "$err" in
    *"No such file or directory"*|*"not supported"*) return 2 ;;
  esac
  return 1
}

# 输出 busy / free / unknown
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
    # 同时兼容 Linux(0.0.0.0:9996) 与 BSD(127.0.0.1.9996) 两种地址写法
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

# 按命令行匹配本服务的 java 进程（最后兜底）。两个讲究：
#   1) 模式要求「java ... code-association.jar」连起来，避免把日志里、编辑器里、
#      或 grep/tail 命令行里出现的同名文件当成服务进程而误杀；
#   2) 不用 "$JAR_NAME" 整串做 awk 模式 —— awk/ps 自己的命令行里会带上该参数，
#      会造成「匹配到自己」的假阳性，所以拆成两段在 awk 里拼。
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

# 健康检查：优先 curl，其次 /dev/tcp 手写 HTTP，再不行只认端口监听
http_ok() {
  if [ "$HAVE_CURL" = 1 ]; then
    if curl -fsS --max-time 2 "http://127.0.0.1:$PORT/api/health" >/dev/null 2>&1; then
      return 0
    fi
    return 1
  fi
  if [ "$HAVE_TCPDEV" = 1 ]; then
    local resp rc
    rc=0
    resp="$( { exec 3<>"/dev/tcp/127.0.0.1/$PORT" &&
               printf 'GET /api/health HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n' >&3 &&
               IFS= read -r -t 3 line <&3 &&
               printf '%s' "$line"; } 2>/dev/null )" || rc=$?
    case "$resp" in
      *" 200 "*) return 0 ;;
    esac
    return 1
  fi
  # 既没有 curl 也不支持 /dev/tcp：降级为只看端口是否在监听
  case "$(port_state)" in
    busy) return 0 ;;
    *) return 1 ;;
  esac
}

# 取多行 PID 输出的第一行。不用 head，避免 SIGPIPE 让 pipefail 误判失败。
first_pid() {
  # shellcheck disable=SC2086
  set -- $1
  printf '%s' "${1:-}"
}

# 输出一个能代表当前服务进程的 PID（可能为空）
running_pid() {
  local pids
  pids="$(listener_pids)"
  if [ -n "$pids" ]; then first_pid "$pids"; return 0; fi
  if [ -n "${LAUNCH_PID:-}" ] && kill -0 "$LAUNCH_PID" 2>/dev/null; then
    printf '%s' "$LAUNCH_PID"
    return 0
  fi
  pids="$(jar_pids)"
  if [ -n "$pids" ]; then first_pid "$pids"; fi
  return 0
}

# 任一线索成立就认为进程还活着（setsid 可能 fork，所以不能只看 $!）
service_alive() {
  if [ -n "${LAUNCH_PID:-}" ] && kill -0 "$LAUNCH_PID" 2>/dev/null; then return 0; fi
  if [ -n "$(listener_pids)" ]; then return 0; fi
  if [ -n "$(jar_pids)" ]; then return 0; fi
  return 1
}

# ---------- 前置检查 ----------

if [ ! -f "$JAR" ]; then
  echo "未找到 $JAR，先构建："
  echo "  mvn -q -DskipTests package"
  exit 1
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
elif have java; then
  JAVA_BIN="$(command -v java)"
else
  echo "未找到 java，请安装 JDK 21 或设置 JAVA_HOME"
  exit 1
fi

JAR_ABS="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
mkdir -p "$(dirname "$LOG_FILE")" "$DATA_DIR"
LOG_ABS="$(cd "$(dirname "$LOG_FILE")" && pwd)/$(basename "$LOG_FILE")"
case "$DATA_DIR" in
  /*) DATA_ABS="$DATA_DIR" ;;
  *)  DATA_ABS="$PWD/$DATA_DIR" ;;
esac

# 已在运行则拒绝启动
if [ -f "$PID_FILE" ]; then
  OLD_PID="$(tr -d '[:space:]' < "$PID_FILE" 2>/dev/null || true)"
  if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
    echo "已在运行 (PID $OLD_PID)，先执行 ./stop.sh"
    exit 1
  fi
  # 残留的 pid 文件（进程已不在），清掉继续
  rm -f "$PID_FILE"
fi

# 端口已被别的进程占用则拒绝启动，避免 java 启动后才报 Port already in use
STATE="$(port_state)"
if [ "$STATE" = busy ]; then
  OWNER="$(first_pid "$(listener_pids)")"
  echo "端口 $PORT 已被占用${OWNER:+ (PID $OWNER)}，先释放端口或换一个：./start.sh 8080"
  exit 1
fi

# ---------- 启动（脱离终端） ----------

echo "启动中：端口 $PORT，数据目录 $DATA_ABS"
LAUNCH_PID=""

# macOS（无 setsid）走这里：python3 做 setsid + 双 fork，孙进程原地 exec java 并写下自己的 PID
launch_via_python() {
  python3 - "$JAVA_BIN" "$JAR_ABS" "$PORT" "$DATA_ABS" "$LOG_ABS" "$PID_FILE" <<'PY'
import os
import sys

java, jar, port, data_dir, log_file, pid_file = sys.argv[1:7]

pid = os.fork()
if pid > 0:
    # 父进程：等第一层子进程退出（它只负责 setsid + 再 fork），脚本随后做健康检查
    os.waitpid(pid, 0)
    sys.exit(0)

os.setsid()          # 新会话，脱离原终端的控制

if os.fork() > 0:
    os._exit(0)      # 中间进程退出，孙进程被 init 收养

# 孙进程：先记下自己的 PID（父进程已退出，$! 拿不到），再原地 exec java
fd = os.open(pid_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644)
os.write(fd, (str(os.getpid()) + "\n").encode())
os.close(fd)

logfd = os.open(log_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644)
os.dup2(logfd, 1)
os.dup2(logfd, 2)
os.dup2(os.open(os.devnull, os.O_RDONLY), 0)
os.execv(java, [java, "-jar", jar,
                "--server.port=" + port,
                "--code-association.data-dir=" + data_dir])
PY
}

if [ "$HAVE_SETSID" = 1 ]; then
  # Linux：setsid 让 java 成为新会话首进程，天然脱离终端；正常情况 exec 后 PID 不变
  if [ "$HAVE_NOHUP" = 1 ]; then
    setsid nohup "$JAVA_BIN" -jar "$JAR_ABS" \
      "--server.port=$PORT" "--code-association.data-dir=$DATA_ABS" \
      >"$LOG_ABS" 2>&1 </dev/null &
  else
    setsid "$JAVA_BIN" -jar "$JAR_ABS" \
      "--server.port=$PORT" "--code-association.data-dir=$DATA_ABS" \
      >"$LOG_ABS" 2>&1 </dev/null &
  fi
  LAUNCH_PID=$!
elif [ "$HAVE_PYTHON3" = 1 ]; then
  if ! launch_via_python; then
    echo "启动失败：python3 拉起进程时出错"
    exit 1
  fi
  # 孙进程会在 exec 前写 pid 文件，稍等它落盘（最多 2 秒）
  i=0
  while [ "$i" -lt 20 ]; do
    if [ -s "$PID_FILE" ]; then
      LAUNCH_PID="$(tr -d '[:space:]' < "$PID_FILE" 2>/dev/null || true)"
      break
    fi
    sleep 0.1
    i=$((i + 1))
  done
else
  # 兜底：nohup 只能挡住 SIGHUP，进程仍在调用方的会话/进程组里
  if [ "$HAVE_NOHUP" = 1 ]; then
    nohup "$JAVA_BIN" -jar "$JAR_ABS" \
      "--server.port=$PORT" "--code-association.data-dir=$DATA_ABS" \
      >"$LOG_ABS" 2>&1 </dev/null &
  else
    "$JAVA_BIN" -jar "$JAR_ABS" \
      "--server.port=$PORT" "--code-association.data-dir=$DATA_ABS" \
      >"$LOG_ABS" 2>&1 </dev/null &
  fi
  LAUNCH_PID=$!
  echo "提示：本机既没有 setsid 也没有 python3，只能用 nohup 后台启动；关闭终端可能带走服务。"
fi

# 先记下候选 PID，这样即使健康检查超时，stop.sh 也能优先按 pid 文件收尾
if [ -n "${LAUNCH_PID:-}" ]; then
  echo "$LAUNCH_PID" > "$PID_FILE"
fi

# ---------- 等就绪（最多 60 秒） ----------

READY=0
i=0
while [ "$i" -lt 120 ]; do
  if http_ok; then
    READY=1
    break
  fi
  # 启动 3 秒后进程已经不在了，就没必要继续等，直接把进程输出尾部打出来
  if [ "$i" -ge 6 ] && ! service_alive; then
    echo "启动失败，进程已退出。进程输出尾部（$LOG_ABS）："
    tail -20 "$LOG_ABS" 2>/dev/null || true
    rm -f "$PID_FILE"
    exit 1
  fi
  sleep 0.5
  i=$((i + 1))
done

if [ "$READY" = 1 ]; then
  REAL_PID="$(running_pid)"
  if [ -n "$REAL_PID" ]; then
    echo "$REAL_PID" > "$PID_FILE"
  fi
  echo "已启动：http://127.0.0.1:$PORT  (PID ${REAL_PID:-未知})"
  echo "应用日志：${LOG_DIR:-./logs}/code-association.log（ERROR 另见 code-association-error.log）"
  echo "进程输出：$LOG_ABS"
  exit 0
fi

echo "启动超时（60 秒内健康检查未通过），进程可能仍在启动中。"
echo "进程输出尾部（$LOG_ABS）："
tail -20 "$LOG_ABS" 2>/dev/null || true
echo "如需停止：./stop.sh $PORT"
exit 1
