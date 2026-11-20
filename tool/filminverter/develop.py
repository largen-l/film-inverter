"""Desktop development of scans (wraps engine/invert_roll.py, the reference engine), plus jadx decompile."""
from __future__ import annotations

import contextlib
import io
import re
import subprocess
import sys
from pathlib import Path
from typing import List

from . import platforms, toolchain


def _engine():
    d = str(platforms.engine_dir())
    if d not in sys.path:
        sys.path.insert(0, d)
    import invert_roll                                 # noqa: E402  (engine/ is put on the path above)
    return invert_roll


class _LogWriter(io.TextIOBase):
    def __init__(self, log):
        self.log, self.buf = log, ""

    def write(self, s):
        self.buf += s
        while "\n" in self.buf:
            line, self.buf = self.buf.split("\n", 1)
            if line.strip():
                self.log(line)
        return len(s)


def develop(argv: List[str], log=None) -> int:
    """Run invert_roll with the given arguments. With `log` (GUI) its output goes there; without (CLI) it
    prints normally. (Redirecting into a log function that itself prints would recurse.)"""
    try:
        ir = _engine()
    except ImportError as e:
        raise RuntimeError(f"the engine needs numpy and pillow (and rawpy for RAW): {e}")
    if log is None:
        try:
            return ir.main(argv) or 0
        except SystemExit as e:
            return int(e.code or 0)
    w = _LogWriter(log)
    with contextlib.redirect_stdout(w), contextlib.redirect_stderr(w):
        try:
            return ir.main(argv) or 0
        except SystemExit as e:                       # argparse errors / --help
            return int(e.code or 0)


def decompile(apk: Path, log) -> Path:
    """Decompile an APK with jadx and summarize which Sony APIs it uses -> docs/api-usage/<name>.txt."""
    cfg = toolchain.load_config()
    apk = Path(apk).resolve()
    name = re.sub(r"[^\w\-.]", "_", apk.stem)
    out = platforms.toolchain_dir() / "decompiled" / name
    r = subprocess.run([cfg["jadx"], "--show-bad-code", "--deobf", "-d", str(out), str(apk)],
                       env=toolchain.java_env(cfg), capture_output=True, text=True)
    if r.returncode != 0:
        log("jadx reported errors (usually fine, partial output is still useful)")
    src = out / "sources"
    files = list(src.rglob("*.java"))
    imports: dict = {}
    hits: List[str] = []
    rx_imp = re.compile(r"^import (com\.sony\.[\w.]+);")
    rx_hit = re.compile(r"(?i)(overlay|blend|preview|liveview|live_view|superimpose|layer|effect|mix|multiexp|alpha)")
    for f in files:
        for i, line in enumerate(f.read_text(errors="replace").splitlines(), 1):
            m = rx_imp.match(line)
            if m:
                imports[m.group(1)] = imports.get(m.group(1), 0) + 1
            elif len(hits) < 400 and rx_hit.search(line) and not line.lstrip().startswith(("import", "//", "*")):
                hits.append(f"{f.relative_to(src)}:{i}: {line.strip()}")
    lines = [f"API usage summary for {apk.name}", "", "== Sony classes imported (count of files)"]
    lines += [f"{n:5d}  {k}" for k, n in sorted(imports.items(), key=lambda kv: -kv[1])]
    lines += ["", "== Lines mentioning live view / overlay / blend / preview / effect / layer"] + hits
    mf = out / "resources" / "AndroidManifest.xml"
    if mf.exists():
        lines += ["", "== Manifest", mf.read_text(errors="replace")]
    dst = platforms.work_dir() / "docs" / "api-usage" / f"{name}.txt"
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text("\n".join(lines), encoding="utf-8")
    log(f"sources: {src}\nsummary: {dst}")
    return dst
