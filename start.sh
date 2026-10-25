#!/usr/bin/env bash
#
# 启动 code-association 服务。
#
#   ./start.sh                 # 默认 9996 端口，数据目录 ./.code-association
#   ./start.sh 8080            # 指定端口
#   PORT=8080 ./start.sh       # 同上
#
# 说明：macOS 没有 setsid 命令，所以用一个内联 Python 做 setsid + 双 fork，
# 让 Java 进程成为独立会话的首进程（PPID=1、PGID 独立）。
# 只靠 nohup 不够：进程仍留在调用方的进程组里，会话结束时会被一起清理。
set -euo pipefail

cd "$(dirname "$0")"

PORT="${1:-${PORT:-9996}}"
DATA_DIR="${DATA_DIR:-./.code-association}"
LOG_FILE="${LOG_FILE:-./code-association.log}"
PID_FILE="./.code-association.pid"

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "已在运行 (PID $(cat "$PID_FILE"))，先执行 ./stop.sh"
  exit 1
fi

JAR="target/code-association.jar"
if [ ! -f "$JAR" ]; then
  echo "未找到 $JAR，先构建："
  echo "  mvn -q -DskipTests package"
  exit 1
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
else
  JAVA_BIN="$(command -v java)"
fi

JAR_ABS="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
LOG_ABS="$(cd "$(dirname "$LOG_FILE")" && pwd)/$(basename "$LOG_FILE")"
case "$DATA_DIR" in
  /*) DATA_ABS="$DATA_DIR" ;;
  *) DATA_ABS="$(pwd)/$DATA_DIR" ;;
esac

python3 - "$JAVA_BIN" "$JAR_ABS" "$PORT" "$DATA_ABS" "$LOG_ABS" "$PID_FILE" <<'PY'
import os
import sys

java, jar, port, data_dir, log_file, pid_file = sys.argv[1:7]

pid = os.fork()
if pid > 0:
    # 父进程：等第一层子进程退出后返回，脚本继续做健康检查
    os.waitpid(pid, 0)
    sys.exit(0)

os.setsid()          # 新会话，脱离原终端的控制

pid2 = os.fork()
if pid2 > 0:
    os._exit(0)      # 中间进程退出，孙进程被 init 收养

# 孙进程：重定向标准流后原地 exec java，成为真正的服务进程
fd = os.open(log_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644)
os.dup2(fd, 1)
os.dup2(fd, 2)
os.dup2(os.open(os.devnull, os.O_RDONLY), 0)
os.execv(java, [java, "-jar", jar,
                "--server.port=" + port,
                "--code-association.data-dir=" + data_dir])
PY

# 记录真实监听进程的 PID（孙进程的 PID 由端口反查，最可靠）
for _ in $(seq 1 60); do
  REAL_PID="$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | awk 'NR==2{print $2}' || true)"
  if [ -n "${REAL_PID:-}" ]; then
    echo "$REAL_PID" > "$PID_FILE"
    break
  fi
  sleep 0.5
done

# 等待端口就绪
for i in $(seq 1 60); do
  if curl -fsS --max-time 2 "http://127.0.0.1:$PORT/api/health" > /dev/null 2>&1; then
    echo "已启动：http://127.0.0.1:$PORT  (PID $(cat "$PID_FILE" 2>/dev/null || echo '?'))"
    echo "日志：$LOG_FILE"
    exit 0
  fi
  # 进程已退出就不用再等，直接把日志尾部打出来
  if [ -f "$PID_FILE" ] && ! kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "启动失败，进程已退出。日志尾部："
    tail -20 "$LOG_FILE" 2>/dev/null || true
    rm -f "$PID_FILE"
    exit 1
  fi
  sleep 0.5
done

echo "启动超时，请查看日志：$LOG_FILE"
tail -20 "$LOG_FILE" 2>/dev/null || true
exit 1