#!/bin/sh
# Reconnects wlan0 when the 5 GHz link silently degrades.
#
# Symptom (2026-11-03): after roughly 60-90 minutes the negotiated rate collapses
# from ~468/585 Mbit/s to a fixed 48/48 Mbit/s and packet loss to the gateway
# climbs to 80%, with 200-500 ms RTT - while RSSI stays at -49 dBm and
# "beacon loss" stays 0. Reconnecting the NetworkManager connection restores
# 0% loss and the full rate immediately, so the association itself is fine and
# the rate control / AP scheduling side is what gets stuck.
#
# Install:
#   install -m 0755 wifi-watchdog.sh /usr/local/sbin/wifi-watchdog.sh
#   install -m 0644 wifi-watchdog.service wifi-watchdog.timer /etc/systemd/system/
#   systemctl daemon-reload
#   systemctl enable --now wifi-watchdog.timer
#
# Check / stop:
#   systemctl list-timers wifi-watchdog.timer
#   systemctl status wifi-watchdog.service
#   systemctl disable --now wifi-watchdog.timer
#
# A reconnect drops the LAN address for a few seconds, which disconnects players,
# so it only runs when the link is already unusable (>50% loss) and at most once
# every 10 minutes.

LOG=/var/log/wifi-watchdog.log
STATE=/run/wifi-watchdog.last
CONNECTION=HUAWEI-SVTYH4_5G

GW=$(ip route 2>/dev/null | awk '/^default/ && /wlan0/ {print $3; exit}')
[ -z "$GW" ] && exit 0

loss(){
    ping -c 10 -i 0.2 -W 2 "$1" 2>/dev/null \
        | sed -n 's/.*, \([0-9][0-9]*\)% packet loss.*/\1/p'
}

LOSS=$(loss "$GW")
[ -z "$LOSS" ] && LOSS=100
[ "$LOSS" -lt 50 ] && exit 0

NOW=$(date +%s)
LAST=$(cat "$STATE" 2>/dev/null || echo 0)
[ $((NOW - LAST)) -lt 600 ] && exit 0
echo "$NOW" > "$STATE"

RATE=$(iw dev wlan0 link 2>/dev/null | sed -n 's/.*tx bitrate:[[:space:]]*\([0-9.]*\).*/\1/p')
echo "$(date '+%F %T') loss=${LOSS}% txrate=${RATE:-?}Mbit -> reconnecting" >> "$LOG"

nmcli connection down "$CONNECTION" >/dev/null 2>&1
sleep 3
nmcli connection up "$CONNECTION" >/dev/null 2>&1
sleep 6

AFTER=$(loss "$GW")
echo "$(date '+%F %T') after reconnect loss=${AFTER:-?}%" >> "$LOG"
