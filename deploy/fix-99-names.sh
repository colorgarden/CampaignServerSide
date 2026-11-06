#!/usr/bin/env bash
set -u
cd /home/colorgarden/campaign-server/config/saves || exit 1

for f in sector-serpulo-99.msav-backup.old-*; do
  [ -e "$f" ] || continue
  case "$f" in
    *old-2026*) continue ;;
  esac
  mv -- "$f" "sector-serpulo-99.msav-backup.old-20261106-2122"
done

for f in sector-serpulo-99.msav.before-restore-*; do
  [ -e "$f" ] || continue
  case "$f" in
    *restore-2026*) continue ;;
  esac
  mv -- "$f" "sector-serpulo-99.msav.before-restore-20261106-2122"
done

chown colorgarden:colorgarden sector-serpulo-99* 2>/dev/null
ls -1 sector-serpulo-99* | sed 's/^/  /'
