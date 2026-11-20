#!/usr/bin/env bash
# Decompile a PMCA app with jadx and summarise which Sony APIs it uses. Linux twin of decompile.ps1.
#   tools/decompile.sh /path/to/MultipleExposure.apk
# Output: toolchain-linux/decompiled/<name>/ (sources, git-ignored) and docs/api-usage/<name>.txt (summary).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$ROOT/toolchain-linux"
. "$TC/env.sh"

APK="$(cd "$(dirname "${1:?usage: tools/decompile.sh <apk>}")" && pwd)/$(basename "$1")"
NAME="$(basename "$APK" .apk | sed 's/[^A-Za-z0-9_.-]/_/g')"
OUT="$TC/decompiled/$NAME"
"$TC/jadx/bin/jadx" --show-bad-code --deobf -d "$OUT" "$APK" || echo "jadx reported errors (usually fine - partial output is still useful)" >&2

SRC="$OUT/sources"
DST="$ROOT/docs/api-usage/$NAME.txt"
mkdir -p "$ROOT/docs/api-usage"
{
  echo "API usage summary for $(basename "$APK")  ($(date -Is))"
  echo
  echo "== Sony classes imported (count of files)"
  grep -rhoE '^import (com\.sony\.[A-Za-z0-9_.]+);' "$SRC" --include='*.java' | sed -E 's/^import (.*);/\1/' \
    | sort | uniq -c | sort -rn
  echo
  echo "== Lines mentioning live view / overlay / blend / preview / effect / layer"
  grep -rniE '(overlay|blend|preview|liveview|live_view|superimpose|layer|effect|mix|multiexp|alpha)' "$SRC" --include='*.java' \
    | grep -vE ':[0-9]+:\s*(import|//|\*)' | sed "s|^$SRC/||" | head -n 400
  echo
  echo "== Manifest"
  [[ -f "$OUT/resources/AndroidManifest.xml" ]] && cat "$OUT/resources/AndroidManifest.xml"
} > "$DST"
echo "sources : $SRC"
echo "summary : $DST"
