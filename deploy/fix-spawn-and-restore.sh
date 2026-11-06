#!/usr/bin/env bash
set -u
DIR=/home/colorgarden/campaign-server
SAVES=$DIR/config/saves

runuser -u colorgarden -- "$DIR/server.sh" console 'campaign-fix-spawn 121 49' >/dev/null
sleep 2
runuser -u colorgarden -- "$DIR/server.sh" stop

cd "$SAVES" || exit 1
cp -p sector-serpulo-99.lost-20261106-210049.msav sector-serpulo-99.msav
rm -f sector-serpulo-99.msav-backup.msav
chown colorgarden:colorgarden sector-serpulo-99.msav
ls -l sector-serpulo-99.msav | awk '{print $5, $6, $7, $8}'

runuser -u colorgarden -- "$DIR/server.sh" start
sleep 26
runuser -u colorgarden -- "$DIR/server.sh" console 'campaign-status' >/dev/null
sleep 3
sed 's/\x1b\[[0-9;]*m//g' "$DIR/logs/server.log" | tail -12
