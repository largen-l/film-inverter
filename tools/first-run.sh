#!/usr/bin/env bash
# Linux twin of first-run.ps1: setup + both builds + engine tests, logged to logs/first-run-linux.log.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$ROOT/logs"
LOG="$ROOT/logs/first-run-linux.log"
exec > >(tee "$LOG") 2>&1

status=OK
echo "== $(date -Is)  $(uname -srm)  $(. /etc/os-release 2>/dev/null && echo "$PRETTY_NAME")"
echo "== free space: $(df -h "$ROOT" | awk 'NR==2 {print $4}')"
echo; echo "#### STEP 1: setup"
"$ROOT/tools/setup.sh" || status="FAILED: setup"
if [[ $status == OK ]]; then
  echo; echo "#### STEP 2: build PMCADemo (toolchain check)"
  "$ROOT/tools/build.sh" PMCADemo || status="PMCADemo build failed (FilmInverter still attempted)"
  echo; echo "#### STEP 3: build FilmInverter"
  "$ROOT/tools/build.sh" || status="FAILED: FilmInverter build"
  echo; echo "#### STEP 4: engine tests"
  "$ROOT/.venv-linux/bin/python" -m pytest -q "$ROOT/engine/tests" || status="FAILED: engine tests"
fi
echo; echo "#### RESULT: $status"
[[ $status == OK ]]
