#!/usr/bin/env bash
# Sends one console command to the running Mindustry server.
#   ./console.sh 'campaign-status'
#   ./console.sh 'campaign-goto groundZero'
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIFO="$DIR/console.in"

if [ ! -p "$FIFO" ]; then
  echo "console.in does not exist - is the server running?" >&2
  exit 1
fi
if [ $# -eq 0 ]; then
  echo "usage: $0 '<console command>'" >&2
  exit 1
fi

timeout 5 bash -c 'printf "%s\n" "$1" > "$2"' _ "$*" "$FIFO"
