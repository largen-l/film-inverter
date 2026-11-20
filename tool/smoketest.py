"""Smoke test for a built executable (or `python tool/run.py`): basic commands plus developing a synthetic
negative film strip (picture + clear rebate with sprocket holes + unlit edge), checking the positive is written,
auto-cropped and gray stays gray.

    python tool/smoketest.py dist/filminverter[.exe]
    python tool/smoketest.py "python tool/run.py"
"""
import shlex
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image


def strip() -> np.ndarray:
    """Synthetic C-41-like negative (sRGB 8-bit): gray ramp + gray patch, orange base, holes, unlit band."""
    gamma = np.array([0.55, 0.62, 0.70]); dmin = np.array([0.20, 0.55, 0.85]); light = np.array([0.95, 0.92, 0.97])
    scene = np.repeat(np.geomspace(0.02, 1.0, 300)[None, :, None], 160, 0).repeat(3, 2)
    scene[60:100, 120:180] = 0.18                                    # middle-gray patch
    def neg(s):
        d = dmin + gamma * np.clip(np.log10(np.clip(s, 1e-4, None)) + 3.0, 0, None)
        return np.clip(light * 10.0 ** (-d) * 1.45, 0, 1)
    pic = neg(scene)
    reb = neg(np.full((24, 300, 3), 1e-4))
    for x in range(10, 280, 40):
        reb[6:18, x:x + 18] = 0.93
    lin = np.concatenate([pic, reb, np.full((8, 300, 3), 0.001)], 0)
    srgb = np.where(lin <= 0.0031308, lin * 12.92, 1.055 * lin ** (1 / 2.4) - 0.055)
    return (srgb * 255 + 0.5).astype(np.uint8)


def main() -> int:
    exe = shlex.split(sys.argv[1], posix=not sys.platform.startswith("win"))
    def run(*a):
        r = subprocess.run(exe + list(a), capture_output=True, text=True)
        print(f"$ {' '.join(exe + list(a))}  -> {r.returncode}\n{(r.stdout + r.stderr).strip()[-1500:]}")
        return r
    assert run("--version").returncode == 0
    run("doctor")                                                   # informational (toolchain may be missing)
    assert run("theme", "list").returncode == 0
    with tempfile.TemporaryDirectory() as d:
        src = Path(d) / "roll"
        src.mkdir()
        Image.fromarray(strip()).save(src / "FRAME001.PNG")
        out = Path(d) / "out"
        r = run("develop", str(src), "--prefer", "jpeg", "--crop", "--out", str(out))
        assert r.returncode == 0, "develop failed"
        pos = np.asarray(Image.open(out / "FRAME001_POS.JPG").convert("RGB")).astype(float) / 255
        assert pos.shape[0] < 190, f"not cropped: {pos.shape}"
        patch = pos[65:95, 125:175].reshape(-1, 3).mean(0)
        assert patch.max() - patch.min() < 0.06, f"gray patch not neutral: {patch}"
        print(f"positive {pos.shape}, gray patch {patch.round(3)}")
    print("SMOKE TEST PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
