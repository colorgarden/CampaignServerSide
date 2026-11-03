#!/bin/sh
# Re-creates the Mindustry port mappings so the router's UPnP lease never expires.
#
# The router caps LeaseDuration at 7 days (604800s) even when 0 is requested, so a
# mapping added once silently disappears a week later. Re-adding the same entry
# refreshes it and is idempotent.
#
# The internal address is read from wlan0 each time, so it follows a DHCP change.

PORT=6567
IP=$(ip -4 -br addr show wlan0 2>/dev/null | awk '{print $3}' | cut -d/ -f1)
[ -z "$IP" ] && exit 0

for proto in udp tcp; do
    python3 /usr/local/sbin/igd-add.py "$proto" "$PORT" "$IP" "$PORT"
done
