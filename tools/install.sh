#!/usr/bin/env bash
# Install an APK on the camera with Sony-PMCA-RE (run from source). Linux twin of install.ps1.
#   tools/install.sh build/FilmInverter/FilmInverter.apk
#   tools/install.sh --adb build/FilmInverter/FilmInverter.apk   # if Tweak's adb is enabled
#
# Camera: Menu > Setup > USB Connection = Mass Storage, then connect USB.
# USB access without sudo (one-time):
#   sudo cp tools/linux/99-sony-camera.rules /etc/udev/rules.d/ && sudo udevadm control --reload-rules
#   then re-plug the camera. Without the rule, run this script with sudo.
# In a VM, pass the camera through by USB *port*: it changes product ID when switching to install mode.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$ROOT/toolchain-linux"
ADB=0
if [[ "${1:-}" == "--adb" ]]; then ADB=1; shift; fi
APK="$(cd "$(dirname "${1:?usage: tools/install.sh [--adb] <apk>}")" && pwd)/$(basename "$1")"
[[ -f "$APK" ]] || { echo "not found: $APK" >&2; exit 1; }

if [[ $ADB -eq 1 ]]; then
  . "$TC/env.sh"
  exec adb install -r "$APK"
fi

PY="$TC/pmca-venv/bin/python"
[[ -x "$PY" ]] || { echo "PMCA-RE venv missing - run tools/setup.sh" >&2; exit 1; }
cd "$TC/src/Sony-PMCA-RE"   # pmca-console.py imports its package from here
exec "$PY" pmca-console.py install -f "$APK"
