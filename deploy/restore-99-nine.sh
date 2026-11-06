#!/usr/bin/env bash
set -u
DIR=/home/colorgarden/campaign-server
SAVES=$DIR/config/saves
SRC=$SAVES/sector-serpulo-99.lost-20261106-210049.msav
STAMP=20261106-2127

cd "$SAVES" || exit 1

[ -f "$SRC" ] || { echo "missing $SRC" >&2; exit 1; }

# keep whatever is there now
if [ -f sector-serpulo-99.msav ]; then
  mv -f sector-serpulo-99.msav "sector-serpulo-99.msav.prev-$STAMP"
fi
if [ -f sector-serpulo-99.msav-backup.msav ]; then
  mv -f sector-serpulo-99.msav-backup.msav "sector-serpulo-99.msav-backup.old-$STAMP"
fi

cp -p "$SRC" sector-serpulo-99.msav
chown colorgarden:colorgarden sector-serpulo-99* 2>/dev/null

echo "restored from: $(basename "$SRC")"
ls -la --time-style=+%m-%d_%H:%M sector-serpulo-99.msav* | awk '{print $5, $6, $7}'
