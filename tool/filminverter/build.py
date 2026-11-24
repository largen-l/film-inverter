"""Build a PMCA APK without Gradle (port of the old build.ps1 / build.sh): aapt -> javac -> d8 -> zipalign ->
apksigner (v1 JAR signing, which the camera's Android needs). Same steps and results on every OS."""
from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path
from typing import List, Optional

from . import platforms, toolchain
from .platforms import EXE, OS


def _run(cmd: List[str], log, env=None, cwd=None, quiet: bool = False) -> str:
    r = subprocess.run([str(c) for c in cmd], env=env, cwd=cwd, capture_output=True, text=True)
    out = (r.stdout or "") + (r.stderr or "")
    if r.returncode != 0:
        raise RuntimeError(f"{Path(str(cmd[0])).name} failed ({r.returncode}):\n{out[-3000:]}")
    if not quiet and out.strip():
        log(out.strip()[-1500:])
    return out


def _first(*paths: Path) -> Optional[Path]:
    return next((p for p in paths if p.exists()), None)


def _java_files(dirs: List[Path]) -> List[str]:
    seen, out = set(), []
    for d in dirs:
        for p in sorted(d.rglob("*.java")):
            s = str(p.resolve())
            if s not in seen:
                seen.add(s)
                out.append(s)
    return out


def _argfile(path: Path, files: List[str]) -> str:
    path.write_text("\n".join('"' + f.replace("\\", "/") + '"' for f in files) + "\n", encoding="utf-8")
    return "@" + str(path)


def _openmemories_jars(cfg: dict, log) -> tuple[Path, Optional[Path]]:
    tc = platforms.toolchain_dir()
    out = tc / "build-libs"
    stubs, fw = out / "openmemories-stubs.jar", out / "openmemories-framework.jar"
    if stubs.exists():
        return stubs, fw if fw.exists() else None
    java = Path(cfg["java_home"]) / "bin"
    om = Path(cfg["src"]) / "OpenMemories-Framework"
    stub_src = om / "stubs" / "src" / "main" / "jasmin"           # Jasmin assembly, as in upstream build.gradle
    if not stub_src.exists():
        raise RuntimeError(f"OpenMemories stubs not found under {om}")
    (out / "stubs").mkdir(parents=True, exist_ok=True)
    (out / "fw").mkdir(parents=True, exist_ok=True)
    rel = [str(p.relative_to(stub_src)) for p in sorted(stub_src.rglob("*.j"))]   # relative: Windows 32K cmd limit
    _run([java / f"java{EXE}", "-jar", cfg["jasmin"], "-d", out / "stubs", *rel], log, cwd=stub_src, quiet=True)
    _run([java / f"jar{EXE}", "cf", stubs, "-C", out / "stubs", "."], log)
    fw_src = _first(om / "framework" / "src" / "main" / "java", om / "framework" / "src")
    if fw_src:
        # upstream compiles the framework against android 4.1 (API 16); only inlined constants, so it runs on API 10
        _run([java / f"javac{EXE}", "-nowarn", "-source", "8", "-target", "8", "-bootclasspath", cfg["android16_jar"],
              "-cp", stubs, "-d", out / "fw", _argfile(out / "fw.txt", _java_files([fw_src]))], log)
        _run([java / f"jar{EXE}", "cf", fw, "-C", out / "fw", "."], log)
    log("built OpenMemories stubs/framework jars")
    return stubs, fw if fw.exists() else None


def build(log, app: str = "FilmInverter", app_dir: Optional[str] = None) -> Path:
    cfg = toolchain.load_config()
    root = platforms.work_dir()
    if app_dir:
        adir = Path(app_dir).resolve()
    elif (root / "app" / app).exists():
        adir = root / "app" / app
    else:
        adir = Path(cfg["src"]) / app
    if not adir.exists():
        if platforms.FROZEN:
            raise RuntimeError("building the camera app needs the source code: clone the repository and run "
                               "`python tool/run.py build` there (the executable can still install a released APK)")
        raise RuntimeError(f"app folder not found: {adir}")
    name = adir.name
    manifest = _first(adir / "AndroidManifest.xml", adir / "src/main/AndroidManifest.xml", adir / "app/src/main/AndroidManifest.xml")
    if not manifest:
        raise RuntimeError(f"no AndroidManifest.xml under {adir}")
    mod = manifest.parent
    src_dirs = [mod / "java"] if (mod / "java").exists() else [d for d in {adir / "src", mod / "src"} if d.exists()]
    res = _first(adir / "res", mod / "res")
    assets = _first(adir / "assets", mod / "assets")
    log(f"app {name}: {manifest}")

    java = Path(cfg["java_home"]) / "bin"
    bt = Path(cfg["build_tools"])
    aj = cfg["android_jar"]
    aapt = cfg.get("aapt") or bt / f"aapt{EXE}"               # set by setup; native tools on ARM Linux
    zipalign = cfg.get("zipalign") or bt / f"zipalign{EXE}"
    stubs, fw = _openmemories_jars(cfg, log)

    out = root / "build" / name
    if out.exists():
        shutil.rmtree(out)
    gen, cls, dex = out / "gen", out / "classes", out / "dex"
    for d in (gen, cls, dex):
        d.mkdir(parents=True)
    res_args = ["-S", res] if res else []
    asset_args = ["-A", assets] if assets else []

    log("[1/6] aapt R.java")
    _run([aapt, "package", "-f", "-m", "-J", gen, "-M", manifest, *res_args, "-I", aj], log)

    log("[2/6] javac")
    extra = []
    for d in (adir / "libs", Path(cfg["libs"]) / name):
        if d.exists():
            extra += sorted(str(p) for p in d.glob("*.jar"))
    cp = os.pathsep.join([str(stubs)] + ([str(fw)] if fw else []) + extra)
    _run([java / f"javac{EXE}", "-nowarn", "-encoding", "UTF-8", "-source", "8", "-target", "8", "-bootclasspath", aj,
          "-cp", cp, "-d", cls, _argfile(out / "sources.txt", _java_files(src_dirs + [gen]))], log)
    _run([java / f"jar{EXE}", "cf", out / "classes.jar", "-C", cls, "."], log)

    log("[3/6] d8 (min-api 10)")
    dex_in = [out / "classes.jar"] + ([fw] if fw else []) + extra       # framework is packaged, stubs are not
    _run([java / f"java{EXE}", "-Xmx1024M", "-cp", bt / "lib" / "d8.jar", "com.android.tools.r8.D8", "--release",
          "--min-api", "10", "--lib", aj, "--classpath", stubs, "--output", dex, *dex_in], log)

    log("[4/6] package")
    unsigned = out / f"{name}-unsigned.apk"
    _run([aapt, "package", "-f", "-M", manifest, *res_args, *asset_args, "-I", aj, "-F", unsigned], log)
    _run([aapt, "add", unsigned, "classes.dex"], log, cwd=dex, quiet=True)

    log("[5/6] zipalign")
    aligned = out / f"{name}-aligned.apk"
    _run([zipalign, "-f", "4", unsigned, aligned], log)

    log("[6/6] sign (v1 / JAR signing)")
    ks = Path(os.environ.get("FI_KEYSTORE") or (platforms.toolchain_dir() / "debug.keystore"))
    if not ks.exists():
        _run([java / f"keytool{EXE}", "-genkeypair", "-keystore", ks, "-storepass", "android", "-keypass", "android",
              "-alias", "fi", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
              "-dname", "CN=film-inverter debug"], log, quiet=True)
    apk = out / f"{name}.apk"
    _run([java / f"java{EXE}", "-jar", bt / "lib" / "apksigner.jar", "sign", "--ks", ks, "--ks-pass", "pass:android",
          "--ks-key-alias", "fi", "--v1-signing-enabled", "true", "--v2-signing-enabled", "false",
          "--v3-signing-enabled", "false", "--min-sdk-version", "10", "--out", apk, aligned], log)
    aligned.unlink()
    log(f"OK -> {apk}")
    return apk
