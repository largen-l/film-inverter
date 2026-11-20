#!/usr/bin/env bash
# One-time toolchain setup on Linux (x86_64). Linux twin of tools/setup.ps1.
#
# Everything goes to toolchain-linux/ and .venv-linux/ (git-ignored), so a Windows and a Linux build
# can share one checkout (e.g. WSL on the same folder) without clobbering each other.
#   tools/setup.sh              # JDK 17, Android SDK (android-10, android-16, build-tools 30.0.3),
#                               # jadx, jasmin, nanohttpd, reference sources, PMCA-RE (from source), Python venvs
#   tools/setup.sh --with-ndk   # + Android NDK r16b (only needed for milestone M5)
# Needs: curl unzip tar python3 (with venv). For camera installs also libusb-1.0 (see tools/install.sh).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$ROOT/toolchain-linux"
DL="$TC/downloads"
mkdir -p "$TC" "$DL"
WITH_NDK=0
[[ "${1:-}" == "--with-ndk" ]] && WITH_NDK=1

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing: $1  (e.g. sudo apt install $2)" >&2; exit 1; }; }
need curl curl; need unzip unzip; need tar tar; need python3 "python3 python3-venv"

case "$(uname -m)" in
  x86_64) JARCH=x64 ;;
  aarch64|arm64) JARCH=aarch64
    echo "WARNING: Android build-tools (aapt, zipalign) only ship for x86_64 Linux; the build step will fail on ARM." >&2 ;;
  *) echo "unsupported CPU: $(uname -m)" >&2; exit 1 ;;
esac

# get_file URL NAME [USER_AGENT] -> prints the local path (messages go to stderr)
get_file() {
  local dst="$DL/$2"
  if [[ ! -f "$dst" ]]; then
    echo "  downloading $2" >&2
    if [[ -n "${3:-}" ]]; then curl -fL --retry 3 -A "$3" -o "$dst.part" "$1"
    else curl -fL --retry 3 -o "$dst.part" "$1"; fi
    mv "$dst.part" "$dst"
  fi
  echo "$dst"
}

# expand_to ARCHIVE DEST: unpack; a single top-level folder becomes DEST itself
expand_to() {
  local arc="$1" dest="$2"
  [[ -e "$dest" ]] && return 0
  echo "  extracting -> $dest" >&2
  local tmp="$dest.tmp"
  rm -rf "$tmp"; mkdir -p "$tmp"
  case "$arc" in
    *.zip) unzip -q "$arc" -d "$tmp" ;;
    *) tar -xzf "$arc" -C "$tmp" ;;
  esac
  local entries=("$tmp"/* "$tmp"/.[!.]*)
  local real=()
  for e in "${entries[@]}"; do [[ -e "$e" ]] && real+=("$e"); done
  if [[ ${#real[@]} -eq 1 && -d "${real[0]}" ]]; then mv "${real[0]}" "$dest"; rmdir "$tmp"
  else mv "$tmp" "$dest"; fi
}

# gh_asset OWNER/REPO REGEX -> "url name" of the first matching asset in the latest release
gh_asset() {
  curl -fsSL "https://api.github.com/repos/$1/releases/latest" | python3 -c '
import json, re, sys
r = json.load(sys.stdin); p = re.compile(sys.argv[1])
a = [x for x in r["assets"] if p.search(x["name"])]
if not a: sys.exit("no asset matching " + sys.argv[1])
print(a[0]["browser_download_url"], a[0]["name"])' "$2"
}

echo "== JDK 17 (Temurin)"
jdk=$(get_file "https://api.adoptium.net/v3/binary/latest/17/ga/linux/$JARCH/jdk/hotspot/normal/eclipse" jdk17-linux.tar.gz)
expand_to "$jdk" "$TC/jdk"
export JAVA_HOME="$TC/jdk" PATH="$TC/jdk/bin:$PATH"

echo "== Android SDK"
SDK="$TC/android-sdk"
CMDL="$SDK/cmdline-tools/latest"
if [[ ! -d "$CMDL" ]]; then
  # If this ever 404s, take the current "Command line tools only / Linux" link from
  # https://developer.android.com/studio#command-line-tools-only and update it here.
  z=$(get_file https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip cmdline-tools-linux.zip)
  mkdir -p "$SDK/cmdline-tools"
  expand_to "$z" "$CMDL"
fi
# android-16 is only used to compile OpenMemories-Framework (upstream builds it against android 4.1.1.4);
# apps themselves compile against android-10.
if [[ ! -f "$SDK/platforms/android-10/android.jar" || ! -f "$SDK/platforms/android-16/android.jar" \
      || ! -f "$SDK/build-tools/30.0.3/lib/d8.jar" ]]; then
  echo "  accepting licenses + installing platforms;android-10 platforms;android-16 build-tools;30.0.3 platform-tools"
  (yes || true) | "$CMDL/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null || true
  "$CMDL/bin/sdkmanager" --sdk_root="$SDK" "platforms;android-10" "platforms;android-16" "build-tools;30.0.3" "platform-tools"
  [[ -f "$SDK/platforms/android-10/android.jar" ]] || { echo "sdkmanager did not install platforms;android-10" >&2; exit 1; }
fi

if [[ $WITH_NDK -eq 1 ]]; then
  echo "== Android NDK r16b"
  z=$(get_file https://dl.google.com/android/repository/android-ndk-r16b-linux-x86_64.zip ndk-r16b-linux.zip)
  expand_to "$z" "$TC/ndk-r16b"
fi

echo "== jadx"
if [[ ! -d "$TC/jadx" ]]; then
  read -r url name < <(gh_asset skylot/jadx '^jadx-[0-9.]+\.zip$')
  z=$(get_file "$url" "$name")
  mkdir -p "$TC/jadx"; unzip -q "$z" -d "$TC/jadx"; chmod +x "$TC/jadx/bin/"*
fi

echo "== jasmin 2.4 (assembles the OpenMemories-Framework stubs, which are .j files)"
if [[ ! -f "$TC/jasmin/jasmin.jar" ]]; then
  # SourceForge serves an HTML "download starting" page to browser-like user agents; Wget gets the file.
  z=$(get_file https://downloads.sourceforge.net/project/jasmin/jasmin/jasmin-2.4/jasmin-2.4.zip jasmin-2.4.zip Wget/1.21)
  rm -rf "$TC/jasmin.tmp"; mkdir -p "$TC/jasmin.tmp" "$TC/jasmin"
  unzip -q "$z" -d "$TC/jasmin.tmp"
  cp "$(find "$TC/jasmin.tmp" -name jasmin.jar | head -n1)" "$TC/jasmin/jasmin.jar"
  rm -rf "$TC/jasmin.tmp"
fi

echo "== third-party jars for reference apps (toolchain-linux/libs/<App>/*.jar, packaged by build.sh)"
mkdir -p "$TC/libs/PMCADemo"
if [[ ! -f "$TC/libs/PMCADemo/nanohttpd-2.1.1.jar" ]]; then
  cp "$(get_file https://repo1.maven.org/maven2/com/nanohttpd/nanohttpd/2.1.1/nanohttpd-2.1.1.jar nanohttpd-2.1.1.jar)" \
     "$TC/libs/PMCADemo/nanohttpd-2.1.1.jar"
fi

echo "== reference sources"
mkdir -p "$TC/src"
for r in ma1co/OpenMemories-Framework ma1co/PMCADemo ma1co/OpenMemories-Tweak ma1co/Sony-PMCA-RE voxivoid/recipe-lab-sony-pmca; do
  name="${r#*/}"
  if [[ ! -d "$TC/src/$name" ]]; then
    branch=$(curl -fsSL "https://api.github.com/repos/$r" | python3 -c 'import json,sys; print(json.load(sys.stdin)["default_branch"])')
    z=$(get_file "https://github.com/$r/archive/refs/heads/$branch.zip" "$name.zip")
    expand_to "$z" "$TC/src/$name"
  fi
done

echo "== Sony-PMCA-RE from source (no Linux binary is released)"
if [[ ! -x "$TC/pmca-venv/bin/python" ]]; then
  python3 -m venv "$TC/pmca-venv"
  "$TC/pmca-venv/bin/python" -m pip install --quiet --upgrade pip
  # upstream requirements minus pyinstaller (packaging only) and the Windows-only packages
  grep -v -i -E '^(pyinstaller|comtypes|pywin32)' "$TC/src/Sony-PMCA-RE/requirements.txt" > "$TC/pmca-requirements.txt"
  "$TC/pmca-venv/bin/python" -m pip install --quiet -r "$TC/pmca-requirements.txt"
fi
if ! (ldconfig -p 2>/dev/null | grep -q 'libusb-1.0'); then
  echo "WARNING: libusb-1.0 not found. For camera installs: sudo apt install libusb-1.0-0" >&2
fi

echo "== Python venv (desktop engine)"
if [[ ! -x "$ROOT/.venv-linux/bin/python" ]]; then
  python3 -m venv "$ROOT/.venv-linux"
  "$ROOT/.venv-linux/bin/python" -m pip install --quiet --upgrade pip
  "$ROOT/.venv-linux/bin/python" -m pip install --quiet numpy pillow rawpy pytest
fi

cat > "$TC/env.sh" <<EOF
export JAVA_HOME='$TC/jdk'
export ANDROID_SDK_ROOT='$SDK'
export FI_BUILD_TOOLS='$SDK/build-tools/30.0.3'
export FI_ANDROID_JAR='$SDK/platforms/android-10/android.jar'
export PATH="\$JAVA_HOME/bin:$SDK/platform-tools:$TC/jadx/bin:\$PATH"
EOF

echo
echo "Setup complete. In new shells run:  . toolchain-linux/env.sh"
echo "Next: tools/build.sh PMCADemo   (toolchain sanity check), then tools/build.sh"
