#!/usr/bin/env bash
# Launch the official HytaleServer.jar with the packet tap early-plugin.
# Dumps every S2C/C2S packet (ordered index.ndjson + per-id bins) to $HYTALE_TAP_DIR.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SERVER_DIR="$REPO/server"
TAP_JAR="$SERVER_DIR/earlyplugins/hytale-packettap.jar"
export HYTALE_TAP_DIR="${HYTALE_TAP_DIR:-/tmp/srvtap}"

[ -f "$SERVER_DIR/HytaleServer.jar" ] || { echo "missing HytaleServer.jar"; exit 1; }
[ -f "$TAP_JAR" ] || { echo "missing $TAP_JAR — run tools/packettap/build.sh first"; exit 1; }

rm -rf "$HYTALE_TAP_DIR"; mkdir -p "$HYTALE_TAP_DIR"
echo "Tap output -> $HYTALE_TAP_DIR"
cd "$SERVER_DIR"
exec java -Dhytale.tap.dir="$HYTALE_TAP_DIR" \
  -cp "HytaleServer.jar:$TAP_JAR" \
  com.hypixel.hytale.Main \
  --asset Assets.zip --accept-early-plugins "$@"
