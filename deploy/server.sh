#!/usr/bin/env bash
# Manual control for the campaign server (no systemd unit).
#
#   ./server.sh start              start in the background (survives ssh logout)
#   ./server.sh stop               save + stop gracefully
#   ./server.sh restart
#   ./server.sh status
#   ./server.sh console 'campaign-status'   send one console command
#   ./server.sh attach             follow the log
#
# Everything runs from this directory; logs go to logs/server.log.
set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

PIDFILE="$DIR/server.pid"
LOG="$DIR/logs/server.log"
FIFO="$DIR/console.in"
mkdir -p logs

running(){
  [ -f "$PIDFILE" ] || return 1
  local pid
  pid="$(cat "$PIDFILE" 2>/dev/null)"
  [ -n "${pid:-}" ] && kill -0 "$pid" 2>/dev/null
}

start(){
  if running; then
    echo "already running (pid $(cat "$PIDFILE"))"
    return 0
  fi
  rm -f "$PIDFILE"
  echo "---- $(date -Is) starting ----" >> "$LOG"
  setsid nohup "$DIR/start.sh" >> "$LOG" 2>&1 < /dev/null &
  # start.sh writes its own pid (which stays valid after it execs java)
  for _ in $(seq 1 30); do
    running && break
    sleep 0.5
  done
  if running; then
    echo "started (pid $(cat "$PIDFILE")), log: $LOG"
  else
    echo "failed to start, see $LOG" >&2
    tail -n 20 "$LOG" >&2
    return 1
  fi
}

stop(){
  if ! running; then
    echo "not running"
    rm -f "$PIDFILE"
    return 0
  fi
  local pid
  pid="$(cat "$PIDFILE")"
  if [ -p "$FIFO" ]; then
    timeout 8 bash -c 'echo exit > "$1"' _ "$FIFO" || true
  fi
  for _ in $(seq 1 20); do
    running || break
    sleep 1
  done
  if running; then
    echo "graceful stop timed out, sending TERM"
    kill "$pid" 2>/dev/null || true
    sleep 3
    running && kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$PIDFILE"
  echo "stopped"
}

case "${1:-}" in
  start)   start ;;
  stop)    stop ;;
  restart) stop; start ;;
  status)
    if running; then echo "running (pid $(cat "$PIDFILE"))"; else echo "stopped"; fi ;;
  console)
    shift
    if [ $# -eq 0 ]; then echo "usage: $0 console '<command>'" >&2; exit 1; fi
    if [ ! -p "$FIFO" ]; then echo "console pipe missing - is the server running?" >&2; exit 1; fi
    timeout 5 bash -c 'printf "%s\n" "$1" > "$2"' _ "$*" "$FIFO" ;;
  attach)  tail -n 50 -f "$LOG" ;;
  *)
    sed -n '3,12p' "$0"; exit 1 ;;
esac
