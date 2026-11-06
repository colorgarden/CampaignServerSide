#!/usr/bin/env bash
# Daily snapshot of the campaign server state, keeping five days of history.
#
# Saved: every sector map, the mod config, the blueprints, and Mindustry's settings.bin
# (which also holds the admin list and the per-sector economy info).
# Older snapshots are pruned: everything beyond RETENTION_DAYS days is deleted.
set -uo pipefail

DIR=/home/colorgarden/campaign-server
STORE=$DIR/config/backups
RETENTION_DAYS=5
STAMP=$(date +%Y%m%d-%H%M%S)
TARGET=$STORE/$STAMP

mkdir -p "$TARGET"

# sector saves: the live file plus the rotated backups and the preserved lost copies
if [ -d "$DIR/config/saves" ]; then
  find "$DIR/config/saves" -maxdepth 1 -type f \( -name '*.msav' -o -name '*.msav-backup.msav' -o -name '*.lost-*.msav' \) \
    -exec cp -p {} "$TARGET"/ \;
fi

# mod config and the blueprint library
MODDIR="$DIR/config/mods/Campaign Server Side"
if [ -d "$MODDIR" ]; then
  [ -f "$MODDIR/config.json" ] && cp -p "$MODDIR/config.json" "$TARGET"/
  if [ -d "$MODDIR/blueprints" ]; then
    mkdir -p "$TARGET/blueprints"
    cp -p "$MODDIR"/blueprints/* "$TARGET/blueprints"/ 2>/dev/null
  fi
fi

# Mindustry settings: admin list, bans, per-sector info
[ -f "$DIR/config/settings.bin" ] && cp -p "$DIR/config/settings.bin" "$TARGET"/

# a snapshot with nothing in it is not worth keeping
if [ -z "$(ls -A "$TARGET")" ]; then
  rmdir "$TARGET" 2>/dev/null
  echo "$(date -Is) nothing to back up"
  exit 0
fi

chown -R colorgarden:colorgarden "$STORE" 2>/dev/null

# prune: anything older than the retention window
find "$STORE" -mindepth 1 -maxdepth 1 -type d -mtime +$RETENTION_DAYS -print -exec rm -rf {} + 2>/dev/null

echo "$(date -Is) backup ok: $TARGET ($(find "$TARGET" -type f | wc -l) files), kept: $(find "$STORE" -mindepth 1 -maxdepth 1 -type d | wc -l) snapshots"
