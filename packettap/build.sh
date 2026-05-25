#!/usr/bin/env bash
# Builds the Hytale server packet-tap early-plugin.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
JAR="$REPO/server/HytaleServer.jar"
SRC="$HERE/src"
LIB="$HERE/lib"
OUT="$HERE/out"
DEST="$REPO/server/earlyplugins"
ASM_VER="9.10.1"
ASM_JAR="$LIB/asm-$ASM_VER.jar"

[ -f "$JAR" ] || { echo "missing $JAR"; exit 1; }
mkdir -p "$LIB" "$OUT" "$DEST"

for stale in "$LIB"/asm-*.jar; do
  case "$stale" in
    "$ASM_JAR") ;;
    *) [ -f "$stale" ] && rm -f "$stale" ;;
  esac
done

if [ ! -f "$ASM_JAR" ]; then
  echo "Downloading ASM $ASM_VER ..."
  curl -fsSL -o "$ASM_JAR" \
    "https://repo1.maven.org/maven2/org/ow2/asm/asm/$ASM_VER/asm-$ASM_VER.jar" \
    || { echo "ASM download failed — place asm-$ASM_VER.jar in $LIB and re-run"; exit 1; }
fi

rm -rf "$OUT"; mkdir -p "$OUT"
echo "Compiling ..."
javac -d "$OUT" "$SRC/com/hypixel/hytale/plugin/early/PacketTap.java"
javac -cp "$JAR:$ASM_JAR" -d "$OUT" "$SRC/tap/TapTransformer.java"

echo "Assembling jar ..."
STAGE="$OUT/stage"; mkdir -p "$STAGE"
cp -r "$OUT/com" "$OUT/tap" "$STAGE/"
mkdir -p "$STAGE/META-INF/services"
cp "$SRC/META-INF/services/com.hypixel.hytale.plugin.early.ClassTransformer" \
   "$STAGE/META-INF/services/"
( cd "$STAGE" && unzip -oq "$ASM_JAR" 'org/objectweb/asm/*' )
( cd "$STAGE" && jar cf "$DEST/hytale-packettap.jar" . )
echo "Built: $DEST/hytale-packettap.jar"
