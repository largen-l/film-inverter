"""Camera side: install the app (Sony-PMCA-RE), find the memory card, fetch the app's logs and outputs.

The card is only READ, except for files the app itself owns: FILMINV/THEMES/*.JSN written by `theme install`.
Camera originals (DCIM) are never touched.
"""
from __future__ import annotations

import datetime as _dt
import shutil
import string
import subprocess
import time
from pathlib import Path
from typing import List, Optional

from . import REPO, download, platforms, toolchain
from .platforms import OS


def default_apk() -> Optional[Path]:
    p = platforms.work_dir() / "build" / "FilmInverter" / "FilmInverter.apk"
    return p if p.exists() else None


def latest_release_apk(log) -> Path:
    url, name = download.github_asset(REPO, r"FilmInverter.*\.apk$")
    return download.fetch(url, platforms.work_dir() / "downloads" / name, log)


def install(apk: Path, log, adb: bool = False) -> None:
    """Install via Sony-PMCA-RE (camera in Mass Storage USB mode), retrying once when the camera re-enumerates
    slowly (through a VM's USB passthrough the first attempt times out)."""
    cfg = toolchain.load_config()
    apk = Path(apk).resolve()
    if not apk.exists():
        raise RuntimeError(f"APK not found: {apk}")
    if adb:
        r = subprocess.run([cfg["adb"], "install", "-r", str(apk)], capture_output=True, text=True)
        log((r.stdout + r.stderr).strip())
        if r.returncode != 0:
            raise RuntimeError("adb install failed")
        return
    if OS != "windows" and not platforms.have_libusb():
        raise RuntimeError("libusb is missing: " + platforms.install_hint("libusb"))
    log("Camera: Menu > Setup > USB Connection = Mass Storage, then connect USB.")
    out = ""
    for attempt in (1, 2):
        out = _stream(cfg["pmca"] + ["install", "-f", str(apk)], cfg.get("pmca_cwd"), log)
        if "Operation timed out" not in out:
            break
        if attempt == 1:
            log("(retrying once: the camera was still switching modes)")
            time.sleep(5)
    if "Task completed successfully" not in out:
        hint = ""
        if OS == "linux" and ("Access denied" in out or "permission" in out.lower()):
            hint = ("\nUSB permission problem: run `filminverter udev` for the one-time rule, or run this with sudo.")
        elif "Communication error 100" in out:
            hint = ("\nThe camera refused the app at the install step. Usually the installed Film Inverter was signed with "
                    "another key (your own build vs. a release APK): uninstall it once on the camera (Menu > Application "
                    "Management), then install again. If it persists, switch the camera off and on.")
        raise RuntimeError("install did not complete (see the output above)" + hint)
    log("Installed. On the camera: Menu > Application > Film Inverter.")


def _stream(cmd: List[str], cwd: Optional[str], log) -> str:
    p = subprocess.Popen(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    lines = []
    assert p.stdout is not None
    for line in p.stdout:
        line = line.rstrip()
        if line:
            lines.append(line)
            log("  " + line)
    p.wait()
    return "\n".join(lines)


# ---------------------------------------------------------------- memory card
def _is_sony_card(p: Path) -> bool:
    try:
        return (p / "DCIM").is_dir() and ((p / "PRIVATE").is_dir() or (p / "AVF_INFO").is_dir() or (p / "FILMINV").is_dir())
    except OSError:
        return False


def find_cards() -> List[Path]:
    """Mounted camera cards (Mass Storage mode): drive letters on Windows, /Volumes on macOS,
    /media, /run/media and /mnt on Linux."""
    cands: List[Path] = []
    if OS == "windows":
        cands = [Path(f"{d}:\\") for d in string.ascii_uppercase if Path(f"{d}:\\").exists()]
    elif OS == "macos":
        cands = list(Path("/Volumes").glob("*"))
    else:
        for base in (Path("/media"), Path("/run/media"), Path("/mnt")):
            if base.exists():
                cands += list(base.glob("*")) + list(base.glob("*/*"))
    return [c for c in cands if _is_sony_card(c)]


def fetch_logs(card: Path, log, dest: Optional[Path] = None) -> Path:
    """Copy FIPROBE.TXT and the app's FILMINV folder (positives, roll profile, lists) to logs/camera-<time>/."""
    stamp = _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    dest = dest or platforms.work_dir() / "logs" / f"camera-{stamp}"
    dest.mkdir(parents=True, exist_ok=True)
    n = 0
    for name in ("FIPROBE.TXT",):
        if (card / name).exists():
            shutil.copy2(card / name, dest / name)
            n += 1
    if (card / "FILMINV").is_dir():
        shutil.copytree(card / "FILMINV", dest / "FILMINV", dirs_exist_ok=True)
        n += sum(1 for _ in (dest / "FILMINV").rglob("*") if _.is_file())
    log(f"copied {n} file(s) from {card} to {dest}")
    return dest


def udev_rule_text() -> str:
    return 'SUBSYSTEM=="usb", ATTR{idVendor}=="054c", MODE="0666"\n'


def udev_instructions() -> str:
    return ("One-time USB permission for Sony cameras (Linux):\n"
            "  echo 'SUBSYSTEM==\"usb\", ATTR{idVendor}==\"054c\", MODE=\"0666\"' | "
            "sudo tee /etc/udev/rules.d/99-sony-camera.rules\n"
            "  sudo udevadm control --reload-rules\n"
            "then unplug and replug the camera.")
