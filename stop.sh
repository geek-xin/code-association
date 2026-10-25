#!/usr/bin/env bash
#
# 停止 code-association 服务。
#
# 关键点：不能杀完进程就返回。Spring Boot 的优雅关闭需要 1~3 秒，
# 端口在这期间仍处于监听状态，紧接着启动新实例会撞上 "Port already in use"。
# 所以这里会等到端口真正释放后再退出。
set -uo pipefail

cd "$(dirname "$0")"

PID_FILE="./.code-association.pid"
PORT="${PORT:-9996}"

port_busy() {
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN > /dev/null 2>&1
}

if [ -f "$PID_FILE" ]; then
  PID="$(cat "$PID_FILE")"
  if kill -0 "$PID" 2>/dev/null; then
    kill "$PID"
    for _ in $(seq 1 40); do
      kill -0 "$PID" 2>/dev/null || break
      sleep 0.25
    done
    kill -9 "$PID" 2>/dev/null || true
    echo "已停止 (PID $PID)"
  else
    echo "进程不存在 (PID $PID)"
  fi
  rm -f "$PID_FILE"
else
  # 没有 pid 文件时按命令行兜底
  PIDS="$(pgrep -f 'code-association.jar' || true)"
  if [ -n "$PIDS" ]; then
    echo "$PIDS" | xargs kill 2>/dev/null || true
    echo "已停止：$PIDS"
  else
    echo "没有正在运行的服务"
  fi
fi

# 等端口真正释放，避免下一次启动撞端口
for _ in $(seq 1 40); do
  if ! port_busy; then
    echo "端口 $PORT 已释放"
    exit 0
  fi
  sleep 0.25
done

echo "警告：端口 $PORT 仍被占用"
lsof -nP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true
exit 1
