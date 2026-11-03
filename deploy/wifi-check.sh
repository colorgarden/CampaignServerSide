#!/bin/sh
# Wi-Fi diagnostics for the campaign server.
# Run over the USB link: ssh root@192.168.42.1 'sh /tmp/wifi-check.sh'

echo "=== reg ==="
iw reg get | head -6

echo "=== power_save ==="
iw dev wlan0 get power_save

echo "=== link ==="
iw dev wlan0 link

echo "=== station counters ==="
iw dev wlan0 station dump | grep -E 'tx retries|tx failed|beacon loss|signal|bitrate|connected time'

echo "=== neighbours on 5GHz ==="
iw dev wlan0 scan 2>/dev/null | awk '
    /^BSS/ {bss=$2}
    /SSID:/ {ssid=$0; sub(/^[ \t]*SSID:[ \t]*/, "", ssid)}
    /freq:/ {freq=$2}
    /signal:/ {sig=$2}
    /primary channel:/ {ch=$3}
    /DS Parameter set: channel/ {ch=$4}
    { if (freq != "" && ssid != "" && sig != "") { printf "%-8s %-6s ch=%-4s %s\n", freq, sig, ch, ssid; freq=""; ssid=""; sig=""; ch="" } }
' | sort -n | head -30
