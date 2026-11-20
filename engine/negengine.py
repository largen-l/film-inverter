"""
negengine - colour-negative inversion engine (desktop reference implementation).

This is the reference the in-camera Java/C code is ported from, so it is kept
deliberately simple: plain numpy, no scipy, every step explicit.

Pipeline (per frame, sharing one RollProfile across the roll):
    1. load -> linear RGB float32 in [0, 1]
         - camera JPEG/TIFF: sRGB-decoded
         - ARW/DNG (if rawpy is installed): half-size, linear, no auto-bright
    2. normalise each channel by the film base (clear, unexposed film) -> base == 1.0
    3. density  D = -log10(v / base)          (0 at base, grows with exposure)
    4. per-channel black/white points in density, taken from the whole roll
       (percentiles), which also neutralises the different layer contrasts
    5. normalised log-exposure p in [0, 1] -> print curve -> sRGB-ish output

Nothing here ever writes back to the source file.
"""
from __future__ import annotations

import dataclasses
import json
import math
from pathlib import Path
from typing import Iterable, Optional, Sequence, Tuple

import numpy as np

EPS = 1e-6
RAW_EXTS = {".arw", ".dng", ".nef", ".cr2", ".cr3", ".raf", ".orf", ".rw2"}


# --------------------------------------------------------------------------- #
# colour helpers
# --------------------------------------------------------------------------- #
def srgb_to_linear(x: np.ndarray) -> np.ndarray:
    x = np.clip(x, 0.0, 1.0)
    return np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4).astype(np.float32)


def linear_to_srgb(x: np.ndarray) -> np.ndarray:
    x = np.clip(x, 0.0, 1.0)
    return np.where(x <= 0.0031308, x * 12.92, 1.055 * np.power(x, 1 / 2.4) - 0.055).astype(np.float32)


# --------------------------------------------------------------------------- #
# loading / saving
# --------------------------------------------------------------------------- #
def load_linear(path: str | Path, raw_half_size: bool = True) -> np.ndarray:
    """Return HxWx3 float32 linear RGB in [0,1]."""
    path = Path(path)
    if path.suffix.lower() in RAW_EXTS:
        try:
            import rawpy  # type: ignore
        except ImportError as e:  # pragma: no cover - depends on environment
            raise RuntimeError("rawpy is needed to read raw files: pip install rawpy") from e
        with rawpy.imread(str(path)) as raw:
            rgb = raw.postprocess(
                half_size=raw_half_size,      # 2x2 binning, no demosaic interpolation (same as camera v2 plan)
                gamma=(1, 1),                 # linear
                no_auto_bright=True,
                output_bps=16,
                use_camera_wb=False,
                use_auto_wb=False,
                user_wb=[1.0, 1.0, 1.0, 1.0], # neutral: the film base does the balancing
                output_color=rawpy.ColorSpace.sRGB,
            )
        return (rgb.astype(np.float32) / 65535.0)

    from PIL import Image
    im = Image.open(path)
    if im.mode in ("I;16", "I;16B", "I"):
        arr = np.asarray(im, dtype=np.float32) / 65535.0
        arr = np.stack([arr] * 3, -1)
    else:
        im = im.convert("RGB")
        arr = np.asarray(im, dtype=np.float32) / 255.0
    return srgb_to_linear(arr)


def save_srgb(img_srgb: np.ndarray, path: str | Path, quality: int = 95) -> None:
    from PIL import Image
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    u8 = (np.clip(img_srgb, 0, 1) * 255.0 + 0.5).astype(np.uint8)
    im = Image.fromarray(u8, "RGB")
    if path.suffix.lower() in (".jpg", ".jpeg"):
        im.save(path, quality=quality, subsampling=0)
    else:
        im.save(path)


# --------------------------------------------------------------------------- #
# film base
# --------------------------------------------------------------------------- #
Rect = Tuple[float, float, float, float]  # x0, y0, x1, y1 as fractions of width/height


def sample_rect(lin: np.ndarray, rect: Rect) -> np.ndarray:
    h, w, _ = lin.shape
    x0, y0, x1, y1 = rect
    xa, xb = sorted((int(x0 * w), int(x1 * w)))
    ya, yb = sorted((int(y0 * h), int(y1 * h)))
    patch = lin[ya:max(yb, ya + 1), xa:max(xb, xa + 1)].reshape(-1, 3)
    return np.median(patch, axis=0).astype(np.float32)


def auto_base(lin: np.ndarray, top_fraction: float = 0.02, clip: float = 0.985) -> np.ndarray:
    """
    Guess the film base colour: on a negative the base is the *brightest* film area.
    Sprocket holes / bare light-source are brighter still but usually clipped or
    near-white, so pixels with any channel >= `clip` are excluded.
    Prefer an explicit rect (sample_rect) whenever possible.
    """
    px = lin.reshape(-1, 3)
    px = px[::max(1, px.shape[0] // 400_000)]               # subsample for speed
    px = px[(px < clip).all(axis=1)]
    if px.shape[0] == 0:
        raise ValueError("everything is clipped - lower exposure")
    lum = px @ np.array([0.2126, 0.7152, 0.0722], np.float32)
    thr = np.quantile(lum, 1.0 - top_fraction)
    return np.median(px[lum >= thr], axis=0).astype(np.float32)


# --------------------------------------------------------------------------- #
# roll profile
# --------------------------------------------------------------------------- #
@dataclasses.dataclass
class RollProfile:
    base: Sequence[float]                       # linear RGB of clear film base
    d_lo: Optional[Sequence[float]] = None      # per-channel density black point (-> output black... see note)
    d_hi: Optional[Sequence[float]] = None      # per-channel density white point
    lo_pct: float = 0.1                         # percentiles used when levels are auto
    hi_pct: float = 99.9
    contrast: float = 1.0                       # 1.0 = neutral, >1 punchier S-curve
    gamma: float = 1.0                          # midtone brightness, >1 brighter
    temp: float = 0.0                           # + warmer / - cooler (density-space nudge)
    tint: float = 0.0                           # + magenta / - green
    film_type: str = "c41"                      # c41 | bw | slide

    # NOTE on naming: in *density* d_lo is the thinnest negative area (= darkest
    # positive shadow) and d_hi the densest (= brightest positive highlight).

    def to_json(self) -> str:
        d = dataclasses.asdict(self)
        for k in ("base", "d_lo", "d_hi"):
            if d[k] is not None:
                d[k] = [round(float(v), 6) for v in d[k]]
        return json.dumps(d, indent=2)

    @classmethod
    def from_json(cls, s: str) -> "RollProfile":
        return cls(**json.loads(s))

    def save(self, path: str | Path) -> None:
        Path(path).write_text(self.to_json())

    @classmethod
    def load(cls, path: str | Path) -> "RollProfile":
        return cls.from_json(Path(path).read_text())


# --------------------------------------------------------------------------- #
# core maths
# --------------------------------------------------------------------------- #
def to_density(lin: np.ndarray, base: Sequence[float]) -> np.ndarray:
    b = np.asarray(base, np.float32).reshape(1, 1, 3)
    t = np.clip(lin / np.maximum(b, EPS), EPS, 1.0)
    return (-np.log10(t)).astype(np.float32)


def density_levels(densities: Iterable[np.ndarray], lo_pct: float, hi_pct: float,
                   max_samples_per_frame: int = 200_000,
                   base_exclude: float = 0.04) -> Tuple[np.ndarray, np.ndarray]:
    """
    Per-channel percentiles over one or many frames (roll-level levels).
    Pixels that are just clear film base (rebate, gaps between frames, sprocket
    area: all channels within `base_exclude` density of the base) are ignored,
    otherwise any visible rebate would drag the black point down.
    """
    samples = []
    for d in densities:
        flat = d.reshape(-1, 3)
        step = max(1, flat.shape[0] // max_samples_per_frame)
        flat = flat[::step]
        keep = flat.max(axis=1) > base_exclude
        if keep.sum() > 100:
            flat = flat[keep]
        samples.append(flat)
    allpx = np.concatenate(samples, 0)
    lo = np.percentile(allpx, lo_pct, axis=0)
    hi = np.percentile(allpx, hi_pct, axis=0)
    hi = np.maximum(hi, lo + 0.05)
    return lo.astype(np.float32), hi.astype(np.float32)


def print_curve(p: np.ndarray, contrast: float, gamma: float) -> np.ndarray:
    """Map normalised log-exposure p (0..1) to output (0..1, display referred)."""
    p = np.clip(p, 0.0, 1.0)
    if gamma != 1.0:
        p = p ** (1.0 / gamma)
    if contrast != 1.0:
        # symmetric S-curve around 0.5 via a scaled logistic, renormalised to hit 0 and 1
        k = 4.0 * contrast
        s = 1.0 / (1.0 + np.exp(-k * (p - 0.5)))
        s0 = 1.0 / (1.0 + math.exp(k * 0.5))
        s1 = 1.0 / (1.0 + math.exp(-k * 0.5))
        p = (s - s0) / (s1 - s0)
    return p.astype(np.float32)


def invert(lin: np.ndarray, prof: RollProfile) -> np.ndarray:
    """Linear negative scan -> display-referred positive (sRGB-encoded floats 0..1)."""
    if prof.film_type == "slide":
        return linear_to_srgb(lin)

    d = to_density(lin, prof.base)
    if prof.film_type == "bw":
        d = np.repeat(d.mean(axis=2, keepdims=True), 3, axis=2)

    if prof.d_lo is None or prof.d_hi is None:
        lo, hi = density_levels([d], prof.lo_pct, prof.hi_pct)
    else:
        lo, hi = np.asarray(prof.d_lo, np.float32), np.asarray(prof.d_hi, np.float32)

    # white-balance nudges are applied as density offsets (like a colour-head filtration)
    span = (hi - lo)
    # temp: +R -B (warmer); tint: -G (more magenta). 1.0 ~= 5% of the channel's range.
    shift = np.array([prof.temp, -prof.tint, -prof.temp], np.float32) * 0.05 * span

    p = (d - lo.reshape(1, 1, 3) + shift.reshape(1, 1, 3)) / span.reshape(1, 1, 3)
    # p is proportional to log-exposure; the print curve below treats it as
    # perceptual lightness, which is close to how paper renders log-exposure.
    return print_curve(p, prof.contrast, prof.gamma)


def calibrate_roll(frames_lin: Sequence[np.ndarray], prof: RollProfile) -> RollProfile:
    """Fill in d_lo/d_hi from all frames of a roll (keeps colour consistent across the roll)."""
    lo, hi = density_levels((to_density(f, prof.base) for f in frames_lin), prof.lo_pct, prof.hi_pct)
    return dataclasses.replace(prof, d_lo=lo.tolist(), d_hi=hi.tolist())
