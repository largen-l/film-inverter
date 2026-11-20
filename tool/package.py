"""Build the single-file `filminverter` executable for the current OS with PyInstaller.

    python tool/package.py            ->  dist/filminverter(.exe)

Run it on each OS (CI does: .github/workflows/tool.yml). Needs: pip install pyinstaller numpy pillow rawpy
"""
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sep = os.pathsep            # PyInstaller --add-data separator: ';' on Windows, ':' elsewhere

args = [
    sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean", "--onefile",
    "--name", "filminverter",
    "--paths", str(ROOT / "tool"),
    "--add-data", f"{ROOT / 'engine' / 'negengine.py'}{sep}engine",
    "--add-data", f"{ROOT / 'engine' / 'invert_roll.py'}{sep}engine",
    "--add-data", f"{ROOT / 'LICENSE'}{sep}.",
    "--hidden-import", "numpy", "--hidden-import", "PIL.Image",
    "--collect-binaries", "rawpy", "--collect-submodules", "rawpy",
    "--distpath", str(ROOT / "dist"), "--workpath", str(ROOT / "build" / "pyinstaller"),
    "--specpath", str(ROOT / "build" / "pyinstaller"),
    str(ROOT / "tool" / "run.py"),
]
print(" ".join(args))
sys.exit(subprocess.call(args))
