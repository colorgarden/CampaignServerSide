#!/usr/bin/env bash
# Control for the campaign server.
#
#   ./server.sh start              start (systemd when the unit is installed, else nohup)
#   ./server.sh stop               save + stop gracefully
#   ./server.sh restart
#   ./server.sh status
#   ./server.sh console 'campaign-status'   send one console command
#   ./server.sh shell              interactive console (live log + typed commands)
#   ./server.sh tmux               tmux workspace: log on top, commands below
#   ./server.sh attach             follow the log
#   ./server.sh logs               follow the systemd journal
#
# Everything runs from this directory; logs go to logs/server.log.
set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

PIDFILE="$DIR/server.pid"
LOG="$DIR/logs/server.log"
FIFO="$DIR/console.in"
UNIT=campaign-server.service
mkdir -p logs

# the unit is optional: without it the script falls back to setsid + nohup
has_systemd(){
  command -v systemctl >/dev/null 2>&1 && systemctl cat "$UNIT" >/dev/null 2>&1
}

running(){
  if has_systemd; then
    systemctl is-active --quiet "$UNIT"
    return $?
  fi

  [ -f "$PIDFILE" ] || return 1
  local pid
  pid="$(cat "$PIDFILE" 2>/dev/null)"
  [ -n "${pid:-}" ] && kill -0 "$pid" 2>/dev/null
}

# Creates the tmux workspace (top pane: log, bottom pane: commands) if it is not there yet.
ensure_tmux(){
  [ "${NOTMUX:-0}" = "1" ] && return 0
  command -v tmux >/dev/null 2>&1 || return 0

  if tmux has-session -t campaign 2>/dev/null; then
    # a dead input pane would leave a workspace nobody can type into; rebuild it
    local panes
    panes=$(tmux list-panes -t campaign 2>/dev/null | wc -l)
    if [ "${panes:-0}" -ge 2 ] && [ -x "$DIR/.console-input.sh" ]; then
      return 0
    fi
    tmux kill-session -t campaign 2>/dev/null
  fi

  # the input loop lives in its own file: nesting that much quoting inside tmux is unreadable
  cat > "$DIR/.console-input.sh" <<'EOS'
#!/usr/bin/env bash
cd "$(dirname "${BASH_SOURCE[0]}")" || exit 1
export HISTFILE="$PWD/.console_history"
# Ctrl+C must not kill the pane: the loop only ever types into the server console
trap '' INT
echo "commands go to the server console; Ctrl+B then D detaches"
while true; do
  if ! IFS= read -e -r -p "cmd> " line; then
    echo "(input closed)"
    break
  fi
  [ -z "$line" ] && continue
  history -s "$line"
  before=$(wc -l < logs/server.log)
  printf '%s\n' "$line" > console.in
  sleep 0.5
  tail -n +$((before + 1)) logs/server.log | sed 's/\x1b\[[0-9;]*m//g'
done
sleep 3600
EOS
  chmod +x "$DIR/.console-input.sh"

  tmux new-session -d -s campaign -n console -c "$DIR" "tail -n 200 -f '$LOG'" || return 0
  # keep a dead pane visible instead of letting tmux close it silently
  tmux set-option -t campaign remain-on-exit on
  tmux split-window -v -t campaign -c "$DIR" "bash '$DIR/.console-input.sh'"
  tmux select-pane -t campaign:console.1
  tmux set-option -t campaign status-left "[campaign] "
}

# Attaches to the workspace, but only when a real terminal is available.
attach_if_interactive(){
  ensure_tmux
  if [ -t 0 ] && [ -t 1 ] && tmux has-session -t campaign 2>/dev/null; then
    echo "attaching to the tmux workspace (Ctrl+B then D to detach)"
    exec tmux attach -t campaign
  fi
}

start(){
  if running; then
    echo "already running ($(describe_running))"
    attach_if_interactive
    return 0
  fi

  if has_systemd; then
    systemctl start "$UNIT" || { echo "systemctl start failed, see journalctl -u $UNIT" >&2; return 1; }
    for _ in $(seq 1 40); do running && break; sleep 0.5; done
    if running; then
      echo "started via systemd ($(describe_running)), log: $LOG"
      attach_if_interactive
    else
      echo "failed to start, see: journalctl -u $UNIT -n 30 --no-pager" >&2
      return 1
    fi
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
    attach_if_interactive
  else
    echo "failed to start, see $LOG" >&2
    tail -n 20 "$LOG" >&2
    return 1
  fi
}

describe_running(){
  if has_systemd; then
    echo "systemd $(systemctl show -p MainPID --value "$UNIT")"
  else
    echo "pid $(cat "$PIDFILE" 2>/dev/null)"
  fi
}

stop(){
  if ! running; then
    echo "not running"
    rm -f "$PIDFILE"
    return 0
  fi

  # the console exit command is the cleanest shutdown: the server saves before leaving
  if [ -p "$FIFO" ]; then
    timeout 8 bash -c 'echo exit > "$1"' _ "$FIFO" || true
  fi
  for _ in $(seq 1 30); do
    running || break
    sleep 1
  done

  if running; then
    if has_systemd; then
      echo "graceful stop timed out, stopping the unit"
      systemctl stop "$UNIT" || true
      sleep 3
      running && systemctl kill -s SIGKILL "$UNIT" 2>/dev/null || true
    else
      echo "graceful stop timed out, sending TERM"
      local pid
      pid="$(cat "$PIDFILE" 2>/dev/null)"
      [ -n "${pid:-}" ] && kill "$pid" 2>/dev/null || true
      sleep 3
      running && [ -n "${pid:-}" ] && kill -9 "$pid" 2>/dev/null || true
    fi
  fi

  # when systemd owns the process it must not be left in a failed state
  if has_systemd; then
    systemctl reset-failed "$UNIT" 2>/dev/null || true
  fi

  rm -f "$PIDFILE"
  echo "stopped"
}

# Interactive console: shows the live log and forwards every typed line into the FIFO.
shell(){
  if [ ! -p "$FIFO" ]; then
    echo "console pipe missing - is the server running?" >&2
    return 1
  fi
  echo "== interactive console: type a command and press enter, :q or Ctrl+C quits =="
  tail -n 0 -f "$LOG" &
  local tailpid=$!
  local line
  while IFS= read -r -p "> " line; do
    [ "$line" = ":q" ] && break
    [ -z "$line" ] && continue
    printf "%s\n" "$line" > "$FIFO" || break
  done
  kill "$tailpid" 2>/dev/null
  wait "$tailpid" 2>/dev/null
  echo "== left the interactive console; the server keeps running =="
}

# tmux workspace: top pane tails the log, bottom pane forwards typed lines into the FIFO.
# The server itself keeps running through start.sh, so ./server.sh console keeps working too.
tmux_mode(){
  if ! running; then
    NOTMUX=1 start || return 1
  fi

  ensure_tmux
  if tmux has-session -t campaign 2>/dev/null; then
    echo "attached: top pane = live log (Ctrl+B then [ to scroll), bottom pane = commands"
    echo "detach with Ctrl+B then D - the server keeps running"
    exec tmux attach -t campaign
  fi
  echo "tmux is not available here" >&2
  return 1
}

case "${1:-}" in
  start)              start ;;
  stop)               stop ;;
  restart)            stop; NOTMUX=1 start ;;
  status)
    if running; then
      echo "running ($(describe_running))"
      if has_systemd; then systemctl is-enabled --quiet "$UNIT" && echo "unit enabled at boot"; fi
    else
      echo "stopped"
    fi ;;
  console)
    shift
    if [ $# -eq 0 ]; then echo "usage: $0 console '<command>'" >&2; exit 1; fi
    if [ ! -p "$FIFO" ]; then echo "console pipe missing - is the server running?" >&2; exit 1; fi
    timeout 5 bash -c 'printf "%s\n" "$1" > "$2"' _ "$*" "$FIFO" ;;
  shell)              shell ;;
  tmux)               tmux_mode ;;
  attach)             tail -n 50 -f "$LOG" ;;
  logs)
    if has_systemd; then journalctl -u "$UNIT" -f -n 80; else tail -n 80 -f "$LOG"; fi ;;
  *)
    sed -n '2,15p' "$0"; exit 1 ;;
esac
