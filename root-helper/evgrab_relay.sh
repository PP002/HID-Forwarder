#!/system/bin/sh
# Standalone root helper for HID Forwarder (Play Store non-root edition).
# Exclusively grabs an external USB keyboard (/dev/input/eventN) via EVIOCGRAB
# and streams 8-byte Boot Keyboard HID reports into the app's TCP relay (127.0.0.1:9876).
#
# Usage (as root / su or Termux tsu):
#   sh evgrab_relay.sh [/dev/input/eventN]
# Press Ctrl+Alt+Q on the grabbed keyboard to stop and release it.

set -eu

EVGRAB_BIN="${EVGRAB_BIN:-$(dirname "$0")/evgrab}"
PORT="${HID_RELAY_PORT:-9876}"
EVENT_NODE="${1:-}"

if [ ! -x "$EVGRAB_BIN" ]; then
    echo "evgrab binary not found or not executable at: $EVGRAB_BIN" >&2
    echo "Compile evgrab.c with Android NDK clang or Termux gcc first (see root-helper/README.md)." >&2
    exit 1
fi

if [ -z "$EVENT_NODE" ]; then
    EVENT_NODE=$(awk '
        BEGIN { RS=""; FS="\n" }
        {
            ev=""; handlers=""; vendor=""
            for (i=1; i<=NF; i++) {
                if ($i ~ /^I:.*Vendor=056a/) vendor="056a"
                if ($i ~ /^H: Handlers=/) handlers=$i
                if ($i ~ /^B: EV=/) { sub(/^B: EV=/, "", $i); ev=$i }
            }
            if (vendor == "056a") next
            # EV_KEY (0x2) + EV_REP (0x100000) heuristic matching keyboards (ends in ...120013 / ...100003 etc.)
            if (ev ~ /1[02468ace]00[0-9a-fA-F][2367abef]$/ && match(handlers, /event[0-9]+/)) {
                print "/dev/input/" substr(handlers, RSTART, RLENGTH)
                exit
            }
        }
    ' /proc/bus/input/devices)
fi

if [ -z "$EVENT_NODE" ]; then
    echo "No external keyboard event node found. Pass /dev/input/eventN explicitly." >&2
    exit 1
fi

echo "Grabbing $EVENT_NODE -> 127.0.0.1:$PORT (press Ctrl+Alt+Q to release)..."
if command -v socat >/dev/null 2>&1; then
    exec "$EVGRAB_BIN" "$EVENT_NODE" | socat -u STDIN TCP:127.0.0.1:"$PORT"
elif command -v nc >/dev/null 2>&1; then
    exec "$EVGRAB_BIN" "$EVENT_NODE" | nc 127.0.0.1 "$PORT"
else
    exec "$EVGRAB_BIN" "$EVENT_NODE" | toybox nc 127.0.0.1 "$PORT"
fi
