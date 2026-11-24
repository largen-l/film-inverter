"""OS / CPU detection, project paths, and per-distro dependency hints."""
from __future__ import annotations

import os
import platform
import shutil
import sys
from pathlib import Path

FROZEN = getattr(sys, "frozen", False)          # running as a PyInstaller executable

if sys.platform.startswith("win"):
    OS = "windows"
elif sys.platform == "darwin":
    OS = "macos"
else:
    OS = "linux"

_m = platform.machine().lower()
ARCH = "aarch64" if _m in ("arm64", "aarch64") else "x64" if _m in ("x86_64", "amd64") else _m
EXE = ".exe" if OS == "windows" else ""


def repo_root() -> Path | None:
    """The source checkout (tool/filminverter/ -> repo), or None when running as a standalone executable."""
    if FROZEN:
        return None
    root = Path(__file__).resolve().parents[2]
    return root if (root / "engine" / "negengine.py").exists() else None


def bundle_dir() -> Path:
    """Where bundled read-only files live (engine/, rules): PyInstaller's temp dir or the checkout."""
    if FROZEN:
        return Path(getattr(sys, "_MEIPASS"))
    return repo_root() or Path(__file__).resolve().parents[2]


def work_dir() -> Path:
    """Writable base: the checkout when running from source, else ~/FilmInverter."""
    root = repo_root()
    if root is not None:
        return root
    d = Path.home() / "FilmInverter"
    d.mkdir(parents=True, exist_ok=True)
    return d


def toolchain_dir() -> Path:
    """Per-OS toolchain folder (Windows keeps the historical name 'toolchain')."""
    return work_dir() / ("toolchain" if OS == "windows" else f"toolchain-{OS}")


def engine_dir() -> Path:
    return bundle_dir() / "engine"


def linux_family() -> str:
    """debian | fedora | arch | other, from /etc/os-release (ID and ID_LIKE)."""
    try:
        text = Path("/etc/os-release").read_text()
    except OSError:
        return "other"
    ids = []
    for line in text.splitlines():
        if line.startswith(("ID=", "ID_LIKE=")):
            ids += line.split("=", 1)[1].strip().strip('"').lower().split()
    for fam, names in (("debian", ("debian", "ubuntu", "linuxmint", "pop", "raspbian")),
                       ("fedora", ("fedora", "rhel", "centos", "rocky", "almalinux")),
                       ("arch", ("arch", "manjaro", "endeavouros", "garuda"))):
        if any(n in ids for n in names):
            return fam
    return "other"


# package names per family for what the tool needs from the system
_PKGS = {
    "debian": {"tk": "python3-tk", "libusb": "libusb-1.0-0", "venv": "python3-venv", "unzip": "unzip",
               "android-tools": "aapt zipalign adb"},   # native builds for ARM (Google ships x86_64 only)
    "fedora": {"tk": "python3-tkinter", "libusb": "libusb1", "venv": "python3", "unzip": "unzip"},
    "arch": {"tk": "tk", "libusb": "libusb", "venv": "python", "unzip": "unzip"},
}
_INSTALL = {"debian": "sudo apt install", "fedora": "sudo dnf install", "arch": "sudo pacman -S"}


def install_hint(*needs: str) -> str:
    """A copy-paste command that installs the named needs ('tk', 'libusb', 'venv') on this system."""
    if OS == "macos":
        if "tk" in needs:
            return "Install Python from python.org (it includes Tk), or: brew install python-tk"
        return "brew install " + " ".join("libusb" if n == "libusb" else n for n in needs if n != "venv")
    if OS == "windows":
        return "Install Python 3 from python.org (Tk is included)."
    fam = linux_family()
    if fam in _PKGS and all(n in _PKGS[fam] for n in needs):
        return _INSTALL[fam] + " " + " ".join(_PKGS[fam][n] for n in needs)
    return "Install these with your package manager: " + ", ".join(needs)


def have_libusb() -> bool:
    if OS == "windows":
        return True                                  # pmca-console uses the built-in Windows drivers
    import ctypes.util
    return ctypes.util.find_library("usb-1.0") is not None


def which(name: str) -> str | None:
    return shutil.which(name)


def describe() -> str:
    extra = f" ({linux_family()} family)" if OS == "linux" else ""
    return f"{OS}{extra} {ARCH}, Python {platform.python_version()}" + (" [executable]" if FROZEN else " [source]")


def open_path(p: Path) -> None:
    """Open a folder in the system file manager."""
    import subprocess
    if OS == "windows":
        os.startfile(str(p))                         # type: ignore[attr-defined]
    elif OS == "macos":
        subprocess.Popen(["open", str(p)])
    else:
        subprocess.Popen(["xdg-open", str(p)])
