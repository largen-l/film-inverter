"""Command line: filminverter <command> ...   (no command = open the GUI)"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from . import __version__, camera, develop, platforms, themes, toolchain


def log(s: str) -> None:
    print(s, flush=True)


def doctor(_args=None) -> int:
    """Check what this system has and say exactly what is missing."""
    ok = True
    log(f"Film Inverter tool {__version__} on {platforms.describe()}")
    log(f"work folder:      {platforms.work_dir()}")
    log(f"toolchain folder: {platforms.toolchain_dir()}")

    def item(good: bool, what: str, fix: str = "") -> None:
        nonlocal ok
        ok = ok and good
        log(f"  [{'ok' if good else '!!'}] {what}" + ("" if good or not fix else f"\n        fix: {fix}"))

    for mod, label in (("numpy", "numpy (engine)"), ("PIL", "Pillow (engine)"), ("rawpy", "rawpy (RAW files, optional)")):
        try:
            __import__(mod)
            item(True, label)
        except ImportError:
            item(mod == "rawpy", label, "pip install numpy pillow rawpy")
    try:
        import tkinter  # noqa: F401
        item(True, "Tk (GUI)")
    except ImportError:
        item(False, "Tk (GUI)", platforms.install_hint("tk"))
    if platforms.OS != "windows":
        item(platforms.have_libusb(), "libusb (camera install)", platforms.install_hint("libusb"))
    try:
        cfg = toolchain.load_config()
        missing = [k for k in ("java_home", "build_tools", "android_jar", "jasmin")
                   if not Path(cfg[k]).exists()]
        item(not missing, "toolchain (setup done)" + (f": missing {missing}" if missing else ""), "filminverter setup")
    except RuntimeError:
        item(False, "toolchain (only needed to build the app)", "filminverter setup")
    cards = camera.find_cards()
    log(f"  [..] camera card: {', '.join(map(str, cards)) if cards else 'not mounted (connect in Mass Storage mode)'}")
    if platforms.OS == "linux":
        rule = Path("/etc/udev/rules.d/99-sony-camera.rules")
        item(rule.exists(), "udev rule for camera USB access", "filminverter udev")
    log("all good" if ok else "some items need attention (see fix lines)")
    return 0 if ok else 1


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="filminverter", description="Film Inverter: camera app tool + desktop engine")
    ap.add_argument("--version", action="version", version=__version__)
    sub = ap.add_subparsers(dest="cmd")
    sub.add_parser("gui", help="open the window (default when no command is given)")
    sub.add_parser("doctor", help="check this system and show what is missing")
    s = sub.add_parser("setup", help="download the build toolchain for this OS (JDK, Android SDK, PMCA-RE, ...)")
    s.add_argument("--with-ndk", action="store_true")
    b = sub.add_parser("build", help="build the camera app (APK)")
    b.add_argument("app", nargs="?", default="FilmInverter", help="FilmInverter (default) or PMCADemo")
    b.add_argument("--dir", help="build any app folder")
    i = sub.add_parser("install", help="install an APK on the camera (USB Mass Storage mode)")
    i.add_argument("apk", nargs="?", help="APK file (default: the last build)")
    i.add_argument("--latest", action="store_true", help="download the latest released APK from GitHub")
    i.add_argument("--adb", action="store_true", help="use adb (needs adb enabled on the camera)")
    d = sub.add_parser("develop", help="invert a folder of scans on the computer (desktop engine)",
                       add_help=False)
    d.add_argument("rest", nargs=argparse.REMAINDER)
    c = sub.add_parser("card", help="camera memory card: find it, copy the app's logs/outputs")
    c.add_argument("action", choices=["find", "logs"])
    t = sub.add_parser("theme", help="camera UI themes")
    t.add_argument("action", choices=["list", "export", "install"])
    t.add_argument("name_or_file", nargs="?", help="built-in theme name (export) or .JSN file (install)")
    t.add_argument("--out", help="export: output folder (default: current)")
    sc = sub.add_parser("sidecars", help="rename the camera's darktable/RawTherapee sidecars (.DTX/.PP3) to .ARW.xmp/.ARW.pp3")
    sc.add_argument("folder", nargs="?", help="folder with the copied ARWs (default: the camera card's DCIM)")
    sc.add_argument("--force", action="store_true", help="replace existing .ARW.xmp/.ARW.pp3 files")
    x = sub.add_parser("decompile", help="decompile a camera app with jadx and summarize its Sony API use")
    x.add_argument("apk")
    sub.add_parser("udev", help="Linux: show the one-time USB permission rule for Sony cameras")
    a = ap.parse_args(argv)

    try:
        if a.cmd in (None, "gui"):
            from . import gui
            return gui.run()
        if a.cmd == "doctor":
            return doctor()
        if a.cmd == "setup":
            toolchain.setup(log, with_ndk=a.with_ndk)
        elif a.cmd == "build":
            from . import build
            build.build(log, app=a.app, app_dir=a.dir)
        elif a.cmd == "install":
            apk = Path(a.apk) if a.apk else (camera.latest_release_apk(log) if a.latest else camera.default_apk())
            if apk is None:
                raise RuntimeError("no APK given and no build found: run `filminverter build` or use --latest")
            camera.install(apk, log, adb=a.adb)
        elif a.cmd == "develop":
            return develop.develop(a.rest)
        elif a.cmd == "card":
            cards = camera.find_cards()
            if not cards:
                raise RuntimeError("no camera card mounted (camera: USB Connection = Mass Storage)")
            if a.action == "find":
                for c_ in cards:
                    log(str(c_))
            else:
                camera.fetch_logs(cards[0], log)
        elif a.cmd == "theme":
            if a.action == "list":
                for th in themes.BUILTINS:
                    log(f"{th['name']:12s} accent {th['accent']}  file {themes.file_name(th['name'])}")
            elif a.action == "export":
                th = next((x for x in themes.BUILTINS if x["name"].lower() == (a.name_or_file or "").lower()), None)
                if th is None:
                    raise RuntimeError("give a built-in theme name (see `theme list`)")
                out = Path(a.out or ".") / themes.file_name(th["name"])
                out.write_text(themes.to_json(th), encoding="utf-8")
                log(f"wrote {out} (edit it, then `theme install {out.name}`)")
            else:
                if not a.name_or_file:
                    raise RuntimeError("give the .JSN file to install")
                cards = camera.find_cards()
                if not cards:
                    raise RuntimeError("no camera card mounted")
                themes.install_to_card(cards[0], themes.load(Path(a.name_or_file)), log, Path(a.name_or_file).stem)
        elif a.cmd == "sidecars":
            from . import sidecars
            folder = Path(a.folder) if a.folder else None
            if folder is None:
                cards = camera.find_cards()
                if not cards:
                    raise RuntimeError("give the folder with your copied ARWs (no camera card mounted)")
                folder = Path(cards[0]) / "DCIM"
            sidecars.fix(folder, log, force=a.force)
        elif a.cmd == "decompile":
            develop.decompile(Path(a.apk), log)
        elif a.cmd == "udev":
            log(camera.udev_instructions())
        return 0
    except KeyboardInterrupt:
        return 130
    except Exception as e:                                          # noqa: BLE001  (show a clean message)
        log(f"error: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
