"""Toolchain setup (port of the old setup.ps1 / setup.sh) for Windows, macOS and Linux.

Downloads into the per-OS toolchain folder (git-ignored): Temurin JDK 17, Android SDK (android-10 and -16,
build-tools 30.0.3, platform-tools), jadx, jasmin 2.4, nanohttpd (PMCADemo), reference sources and
Sony-PMCA-RE (release binary on Windows/macOS, from source in a venv on Linux). Paths go to toolchain.json.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

from . import download, platforms
from .platforms import ARCH, EXE, OS

CMDLINE_TOOLS = "11076708"   # if dl.google.com ever 404s, take the current number from developer.android.com/studio
SDK_PACKAGES = ["platforms;android-10", "platforms;android-16", "build-tools;30.0.3", "platform-tools"]
REF_REPOS = ["ma1co/OpenMemories-Framework", "ma1co/PMCADemo", "ma1co/OpenMemories-Tweak",
             "ma1co/Sony-PMCA-RE", "voxivoid/recipe-lab-sony-pmca"]


def config_path() -> Path:
    return platforms.toolchain_dir() / "toolchain.json"


def load_config() -> dict:
    p = config_path()
    if not p.exists():
        raise RuntimeError("toolchain not set up yet: run `filminverter setup` first")
    return json.loads(p.read_text())


def java_env(cfg: dict) -> dict:
    env = dict(os.environ)
    env["JAVA_HOME"] = cfg["java_home"]
    env["PATH"] = str(Path(cfg["java_home"]) / "bin") + os.pathsep + env.get("PATH", "")
    return env


def setup(log, with_ndk: bool = False) -> dict:
    tc = platforms.toolchain_dir()
    dl = tc / "downloads"
    dl.mkdir(parents=True, exist_ok=True)
    log(f"toolchain: {tc}  ({platforms.describe()})")
    if OS == "linux" and ARCH != "x64":
        log("WARNING: Android build-tools (aapt, zipalign) only exist for x86_64 Linux; building will fail on ARM.")

    # ---- JDK 17
    log("== JDK 17 (Temurin)")
    jos = {"windows": "windows", "macos": "mac", "linux": "linux"}[OS]
    ext = "zip" if OS == "windows" else "tar.gz"
    jdk = tc / "jdk"
    if not jdk.exists():
        jdk_arc = download.fetch(f"https://api.adoptium.net/v3/binary/latest/17/ga/{jos}/{ARCH}/jdk/hotspot/normal/eclipse",
                                 dl / f"jdk17-{OS}-{ARCH}.{ext}", log)
        download.extract(jdk_arc, jdk, log)
    java_home = jdk / "Contents" / "Home" if (jdk / "Contents" / "Home").exists() else jdk
    env = dict(os.environ, JAVA_HOME=str(java_home))

    # ---- Android SDK
    log("== Android SDK")
    sdk = tc / "android-sdk"
    latest = sdk / "cmdline-tools" / "latest"
    if not latest.exists():
        cos = {"windows": "win", "macos": "mac", "linux": "linux"}[OS]
        z = download.fetch(f"https://dl.google.com/android/repository/commandlinetools-{cos}-{CMDLINE_TOOLS}_latest.zip",
                           dl / f"cmdline-tools-{OS}.zip", log)
        (sdk / "cmdline-tools").mkdir(parents=True, exist_ok=True)
        download.extract(z, latest, log)
    sdkm = latest / "bin" / ("sdkmanager.bat" if OS == "windows" else "sdkmanager")
    if OS != "windows":
        sdkm.chmod(0o755)
    bt = sdk / "build-tools" / "30.0.3"
    if not ((sdk / "platforms/android-10/android.jar").exists() and (sdk / "platforms/android-16/android.jar").exists()
            and (bt / "lib/d8.jar").exists()):
        log("  accepting SDK licenses + installing " + ", ".join(SDK_PACKAGES))
        # sdkmanager reads the answers from stdin (a plain pipe works on every OS)
        subprocess.run([str(sdkm), f"--sdk_root={sdk}", "--licenses"], input="y\n" * 40, text=True, env=env,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        r = subprocess.run([str(sdkm), f"--sdk_root={sdk}", *SDK_PACKAGES], env=env, capture_output=True, text=True)
        if not (sdk / "platforms/android-10/android.jar").exists():
            raise RuntimeError("sdkmanager did not install the SDK packages:\n" + (r.stdout + r.stderr)[-2000:])
    if OS != "windows":
        for tool in ("aapt", "zipalign"):
            p = bt / tool
            if p.exists():
                p.chmod(0o755)

    if with_ndk:
        log("== Android NDK r16b (not available for Apple Silicon natively)")
        nos = {"windows": "windows-x86_64", "macos": "darwin-x86_64", "linux": "linux-x86_64"}[OS]
        z = download.fetch(f"https://dl.google.com/android/repository/android-ndk-r16b-{nos}.zip", dl / f"ndk-r16b-{OS}.zip", log)
        download.extract(z, tc / "ndk-r16b", log)

    # ---- jadx
    log("== jadx")
    jadx = tc / "jadx"
    if not jadx.exists():
        url, name = download.github_asset("skylot/jadx", r"^jadx-[\d.]+\.zip$")
        z = download.fetch(url, dl / name, log)
        import zipfile
        jadx.mkdir(parents=True)
        with zipfile.ZipFile(z) as zf:
            zf.extractall(jadx)
        if OS != "windows":
            for p in (jadx / "bin").iterdir():
                p.chmod(0o755)

    # ---- jasmin (the OpenMemories stubs are Jasmin assembly)
    log("== jasmin 2.4")
    jasmin = tc / "jasmin" / "jasmin.jar"
    if not jasmin.exists():
        # SourceForge sends browsers an HTML page; a Wget user agent gets the file
        z = download.fetch("https://downloads.sourceforge.net/project/jasmin/jasmin/jasmin-2.4/jasmin-2.4.zip",
                           dl / "jasmin-2.4.zip", log, user_agent="Wget/1.21")
        import zipfile
        with zipfile.ZipFile(z) as zf:
            name = next(n for n in zf.namelist() if n.endswith("/jasmin.jar") or n == "jasmin.jar")
            jasmin.parent.mkdir(parents=True, exist_ok=True)
            jasmin.write_bytes(zf.read(name))

    # ---- third-party jar for the PMCADemo reference build
    nano = tc / "libs" / "PMCADemo" / "nanohttpd-2.1.1.jar"
    if not nano.exists():
        nano.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(download.fetch("https://repo1.maven.org/maven2/com/nanohttpd/nanohttpd/2.1.1/nanohttpd-2.1.1.jar",
                                   dl / "nanohttpd-2.1.1.jar", log), nano)

    # ---- reference sources
    log("== reference sources")
    src = tc / "src"
    for repo in REF_REPOS:
        name = repo.split("/")[1]
        if not (src / name).exists():
            branch = download.github_json(f"repos/{repo}")["default_branch"]
            z = download.fetch(f"https://github.com/{repo}/archive/refs/heads/{branch}.zip", dl / f"{name}.zip", log)
            download.extract(z, src / name, log)

    # ---- Sony-PMCA-RE (camera installer)
    log("== Sony-PMCA-RE")
    pmca_cmd, pmca_cwd = _setup_pmca(tc, dl, src, log)

    cfg = {
        "os": OS, "arch": ARCH, "java_home": str(java_home), "sdk": str(sdk), "build_tools": str(bt),
        "android_jar": str(sdk / "platforms/android-10/android.jar"),
        "android16_jar": str(sdk / "platforms/android-16/android.jar"),
        "jasmin": str(jasmin), "jadx": str(jadx / "bin" / ("jadx.bat" if OS == "windows" else "jadx")),
        "src": str(src), "libs": str(tc / "libs"), "pmca": pmca_cmd, "pmca_cwd": pmca_cwd,
        "adb": str(sdk / "platform-tools" / f"adb{EXE}"),
    }
    config_path().write_text(json.dumps(cfg, indent=2))
    log(f"Setup complete. Config: {config_path()}")
    if OS != "windows" and not platforms.have_libusb():
        log("NOTE: libusb not found; camera installs need it: " + platforms.install_hint("libusb"))
    return cfg


def _setup_pmca(tc: Path, dl: Path, src: Path, log):
    pm = tc / "pmca"
    if OS == "windows":
        existing = sorted(pm.glob("*console*.exe")) if pm.exists() else []
        if not existing:
            url, name = download.github_asset("ma1co/Sony-PMCA-RE", r"^pmca-console-.*-win\.exe$")
            pm.mkdir(parents=True, exist_ok=True)
            shutil.copy(download.fetch(url, dl / name, log), pm / name)
            existing = [pm / name]
        return [str(existing[0])], None
    if OS == "macos":
        exe = pm / "pmca-console"
        if not exe.exists():
            url, name = download.github_asset("ma1co/Sony-PMCA-RE", r"^pmca-console-.*-osx$")
            pm.mkdir(parents=True, exist_ok=True)
            shutil.copy(download.fetch(url, dl / name, log), exe)
            exe.chmod(0o755)
        return [str(exe)], None
    # Linux: no binary release; run from source with its own venv (needs python3-venv and libusb)
    venv = tc / "pmca-venv"
    py = venv / "bin" / "python"
    if not py.exists():
        base_py = shutil.which("python3") or sys.executable
        r = subprocess.run([base_py, "-m", "venv", str(venv)], capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError("could not create a Python venv for Sony-PMCA-RE: "
                               + platforms.install_hint("venv") + "\n" + r.stderr[-800:])
        reqs = (src / "Sony-PMCA-RE" / "requirements.txt").read_text().splitlines()
        reqs = [x for x in reqs if x.strip() and not x.lower().startswith(("pyinstaller", "comtypes", "pywin32"))]
        (tc / "pmca-requirements.txt").write_text("\n".join(reqs) + "\n")
        subprocess.run([str(py), "-m", "pip", "install", "--quiet", "--upgrade", "pip"], check=True)
        subprocess.run([str(py), "-m", "pip", "install", "--quiet", "-r", str(tc / "pmca-requirements.txt")], check=True)
    return [str(py), "pmca-console.py"], str(src / "Sony-PMCA-RE")
