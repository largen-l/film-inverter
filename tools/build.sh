#!/usr/bin/env bash
# Build a PMCA APK without Gradle: aapt -> javac -> d8 -> zipalign -> apksigner (v1). Linux twin of build.ps1.
#   tools/build.sh                 # builds app/FilmInverter
#   tools/build.sh PMCADemo        # builds toolchain-linux/src/PMCADemo (toolchain sanity check)
#   tools/build.sh --dir /some/app # any app folder (AndroidManifest.xml + src/ or src/main/java)
# Output: build/<name>/<name>.apk (debug-signed, v1 only) and <name>-unsigned.apk.
#
# Signing key: toolchain-linux/debug.keystore, or $FI_KEYSTORE. The camera refuses an update signed
# with a different key than the installed app, so to swap between a Windows and a Linux build either
# point FI_KEYSTORE at toolchain/debug.keystore (alias "fi", password "android") or uninstall first.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$ROOT/toolchain-linux"
[[ -f "$TC/env.sh" ]] || { echo "run tools/setup.sh first" >&2; exit 1; }
# shellcheck disable=SC1091
. "$TC/env.sh"

APP=FilmInverter
APPDIR=""
if [[ "${1:-}" == "--dir" ]]; then APPDIR="$(cd "$2" && pwd)"; elif [[ -n "${1:-}" ]]; then APP="$1"; fi
if [[ -z "$APPDIR" ]]; then
  if [[ -d "$ROOT/app/$APP" ]]; then APPDIR="$ROOT/app/$APP"; else APPDIR="$TC/src/$APP"; fi
fi
[[ -d "$APPDIR" ]] || { echo "app folder not found: $APPDIR" >&2; exit 1; }
NAME="$(basename "$APPDIR")"

BT="$FI_BUILD_TOOLS"; AJ="$FI_ANDROID_JAR"
AAPT="$BT/aapt"; ZIPALIGN="$BT/zipalign"
JAVA="$JAVA_HOME/bin/java"; JAVAC="$JAVA_HOME/bin/javac"; JAR="$JAVA_HOME/bin/jar"; KEYTOOL="$JAVA_HOME/bin/keytool"

first_existing() { for p in "$@"; do [[ -e "$p" ]] && { echo "$p"; return 0; }; done; return 0; }

MANIFEST=$(first_existing "$APPDIR/AndroidManifest.xml" "$APPDIR/src/main/AndroidManifest.xml" "$APPDIR/app/src/main/AndroidManifest.xml")
[[ -n "$MANIFEST" ]] || { echo "no AndroidManifest.xml under $APPDIR" >&2; exit 1; }
MODDIR="$(dirname "$MANIFEST")"
# Gradle layout keeps code in <module>/java; flat layout in <app>/src. Never both.
if [[ -d "$MODDIR/java" ]]; then SRCDIRS=("$MODDIR/java")
else SRCDIRS=(); for d in "$APPDIR/src" "$MODDIR/src"; do [[ -d "$d" ]] && SRCDIRS+=("$d"); done
  [[ ${#SRCDIRS[@]} -eq 2 && "${SRCDIRS[0]}" == "${SRCDIRS[1]}" ]] && SRCDIRS=("${SRCDIRS[0]}"); fi
RESDIR=$(first_existing "$APPDIR/res" "$MODDIR/res")
ASSETSDIR=$(first_existing "$APPDIR/assets" "$MODDIR/assets")
echo "app      : $NAME"
echo "manifest : $MANIFEST"
echo "sources  : ${SRCDIRS[*]}"

# ------------------------------------------------------------------ OpenMemories stubs + framework jars (once)
LIBOUT="$TC/build-libs"
STUBS="$LIBOUT/openmemories-stubs.jar"
FW="$LIBOUT/openmemories-framework.jar"
if [[ ! -f "$STUBS" ]]; then
  OM="$TC/src/OpenMemories-Framework"
  STUBSRC="$OM/stubs/src/main/jasmin"     # Jasmin assembly, assembled like upstream's build.gradle
  [[ -d "$STUBSRC" ]] || { echo "OpenMemories stubs (jasmin) not found under $OM" >&2; exit 1; }
  mkdir -p "$LIBOUT/stubs" "$LIBOUT/fw"
  (cd "$STUBSRC" && find . -name '*.j' -print0 | xargs -0 "$JAVA" -jar "$TC/jasmin/jasmin.jar" -d "$LIBOUT/stubs" >/dev/null)
  "$JAR" cf "$STUBS" -C "$LIBOUT/stubs" .
  FWSRC=$(first_existing "$OM/framework/src/main/java" "$OM/framework/src")
  if [[ -n "$FWSRC" ]]; then
    # upstream compiles the framework against android 4.1 (API 16); it only uses API 16 constants,
    # which javac inlines, so the result still runs on API 10.
    find "$FWSRC" -name '*.java' > "$LIBOUT/fw.txt"
    "$JAVAC" -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_SDK_ROOT/platforms/android-16/android.jar" \
      -cp "$STUBS" -d "$LIBOUT/fw" "@$LIBOUT/fw.txt"
    "$JAR" cf "$FW" -C "$LIBOUT/fw" .
  fi
  echo "built OpenMemories stubs/framework jars"
fi

# ------------------------------------------------------------------ build
OUT="$ROOT/build/$NAME"
rm -rf "$OUT"
GEN="$OUT/gen"; CLS="$OUT/classes"; DEX="$OUT/dex"
mkdir -p "$GEN" "$CLS" "$DEX"
RESARGS=(); [[ -n "$RESDIR" ]] && RESARGS=(-S "$RESDIR")
ASSETARGS=(); [[ -n "$ASSETSDIR" ]] && ASSETARGS=(-A "$ASSETSDIR")

echo "[1/6] aapt R.java"
"$AAPT" package -f -m -J "$GEN" -M "$MANIFEST" "${RESARGS[@]}" -I "$AJ"

echo "[2/6] javac"
EXTRA=()   # <app>/libs/*.jar plus toolchain-linux/libs/<name>/*.jar (reference apps)
for d in "$APPDIR/libs" "$TC/libs/$NAME"; do [[ -d "$d" ]] && for j in "$d"/*.jar; do [[ -f "$j" ]] && EXTRA+=("$j"); done; done
CP="$STUBS"; [[ -f "$FW" ]] && CP="$CP:$FW"; for j in "${EXTRA[@]}"; do CP="$CP:$j"; done
find "${SRCDIRS[@]}" "$GEN" -name '*.java' | sort -u > "$OUT/sources.txt"
"$JAVAC" -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJ" -cp "$CP" -d "$CLS" "@$OUT/sources.txt"
"$JAR" cf "$OUT/classes.jar" -C "$CLS" .

echo "[3/6] d8 (min-api 10)"
DEXIN=("$OUT/classes.jar"); [[ -f "$FW" ]] && DEXIN+=("$FW"); DEXIN+=("${EXTRA[@]}")   # framework packaged, stubs not
"$JAVA" -Xmx1024M -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 10 --lib "$AJ" \
  --classpath "$STUBS" --output "$DEX" "${DEXIN[@]}"

echo "[4/6] package"
UNSIGNED="$OUT/$NAME-unsigned.apk"
"$AAPT" package -f -M "$MANIFEST" "${RESARGS[@]}" "${ASSETARGS[@]}" -I "$AJ" -F "$UNSIGNED"
(cd "$DEX" && "$AAPT" add "$UNSIGNED" classes.dex >/dev/null)

echo "[5/6] zipalign"
ALIGNED="$OUT/$NAME-aligned.apk"
"$ZIPALIGN" -f 4 "$UNSIGNED" "$ALIGNED"

echo "[6/6] sign (v1 / JAR signing)"
KS="${FI_KEYSTORE:-$TC/debug.keystore}"
if [[ ! -f "$KS" ]]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android -alias fi \
    -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=film-inverter debug'
fi
"$JAVA" -jar "$BT/lib/apksigner.jar" sign --ks "$KS" --ks-pass pass:android --ks-key-alias fi \
  --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false \
  --min-sdk-version 10 --out "$OUT/$NAME.apk" "$ALIGNED"
rm -f "$ALIGNED"

echo
echo "OK -> $OUT/$NAME.apk"
echo "Install: tools/install.sh $OUT/$NAME.apk"
