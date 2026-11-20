"""
negengine - color-negative inversion engine (desktop reference implementation).

This is the reference the in-camera Java/C code is ported from, so it is kept
deliberately simple: plain numpy, no scipy, every step explicit.

Pipeline (per frame, sharing one RollProfile across the roll):
    1. load -> linear RGB float32 in [0, 1]
         - camera JPEG/TIFF: sRGB-decoded
         - ARW/DNG (if rawpy is installed): half-size, linear, no auto-bright
    2. normalize each channel by the film base (clear, unexposed film) -> base == 1.0
    3. density  D = -log10(v / base)          (0 at base, grows with exposure)
    4. per-channel black/white points in density, taken from the whole roll
       (percentiles over each frame's *picture area*, see analyze_frame), which also
       neutralizes the different layer contrasts
    5. normalized log-exposure p in [0, 1] -> print curve -> sRGB-ish output

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
# color helpers
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
                # White balance BEFORE the color matrix, like the camera's own JPEG. With neutral (1,1,1,1) WB
                # the camera->sRGB matrix mixes the unbalanced orange mask across channels, which the per-channel
                # base division cannot undo (probe 2 cat frame: strong blue-violet cast). The base division still
                # does the actual balancing; camera WB just keeps the matrix in its intended range.
                use_camera_wb=True,
                use_auto_wb=False,
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


LUM = np.array([0.2126, 0.7152, 0.0722], np.float32)

# Frame analysis constants (NegEngine.java uses the same values; keep them in sync).
FRAME_LINE_FRAC = 0.6     # a row/column is film border when >= 60% of its pixels look like border
FRAME_MAX_BORDER = 0.25   # never treat more than 25% of a side as border
FRAME_INSET = 0.015       # step this far inside a detected border (blurry gate edge)
FRAME_BRIGHT_REL = 0.9    # "bright" = luminance >= 90% of the picture center's 99th-percentile luminance
FRAME_DARK = 0.004        # "unlit" = every channel below this (outside the film, no light)
CLIP = 0.985              # any channel at or above this counts as clipped
HOLE_RATIO = 1.25         # bright border pixels spanning more than this (p95/p5) contain sprocket holes
FRAME_LINE_REL = 0.85     # a bright border line's median luminance must be >= 85% of the brightest edge line's
FRAME_GAP = 0.03          # tolerate up to 3% of the side of non-border lines (edge markings) while peeling
FRAME_MAX_SIDE = 600      # analyze a subsampled copy at most this long (the camera took 11 s at 1500 px)


@dataclasses.dataclass
class FrameInfo:
    rect: Tuple[int, int, int, int]       # picture area x0, y0, x1, y1 in pixels (x1/y1 exclusive)
    border_found: bool                    # False -> rect is the whole frame
    base: Optional[np.ndarray]            # film base from the rebate (linear RGB), None if not found
    tilt_deg: Optional[float] = None      # film edge angle; + = image rotated clockwise. None if no edge


def _border_lines(lum: np.ndarray, bright: np.ndarray, dark: np.ndarray) -> Tuple[int, int]:
    """
    Border extent along axis 0 of the given 2-D arrays (rows; pass transposes for columns).
    A line is border when >= FRAME_LINE_FRAC of it is bright-or-unlit AND it is either mostly unlit or about
    as bright as the brightest such line near the edges (its median: sprocket holes are a minority of a rebate
    line). The second condition stops a dark-scene area along an edge (a thin, bright-ish negative) from being
    taken for rebate. Peeling from each edge tolerates short runs of other lines (printed edge markings).
    """
    n = lum.shape[0]
    zone = max(1, int(n * FRAME_MAX_BORDER))
    gap = max(2, int(n * FRAME_GAP))
    frac_any = (bright | dark).mean(axis=1)
    frac_dark = dark.mean(axis=1)
    frac_bright = bright.mean(axis=1)
    med = np.median(lum, axis=1)
    edge = np.r_[0:zone, n - zone:n]
    cand = edge[(frac_bright[edge] >= FRAME_LINE_FRAC)]
    lref = float(med[cand].max()) if cand.size else math.inf
    is_border = (frac_any >= FRAME_LINE_FRAC) & ((frac_dark >= frac_bright) | (med >= FRAME_LINE_REL * lref))

    def peel(order) -> int:
        last, miss = -1, 0
        for k, i in enumerate(order):
            if is_border[i]:
                last, miss = k, 0
            else:
                miss += 1
                if miss > gap:
                    break
        return last + 1                       # number of border lines from this edge

    a = peel(range(zone))
    b = n - peel(range(n - 1, n - 1 - zone, -1))
    return a, b


def analyze_frame(lin: np.ndarray) -> FrameInfo:
    """
    Find the picture area of a scanned negative and, from the film border around it, the film base.

    The border of a 35 mm scan is clear rebate (= film base), sprocket holes (bare light, brighter than
    the base) and sometimes unlit space outside the film (black). Those pixels are all either "bright"
    or "unlit"; picture content mostly is neither. Rows/columns that are mostly border are peeled off
    from each edge. Levels must come from the picture area only: the unlit edge alone drags the white
    points to density ~2.5 and turns everything blue (probe 2 cat frame).

    Works on every s-th pixel (s chosen so the long side is <= FRAME_MAX_SIDE) and scales the result back;
    NegEngine.analyzeFrame subsamples the same way, so both engines give identical answers.
    """
    full_h, full_w, _ = lin.shape
    s = max(1, math.ceil(max(full_h, full_w) / FRAME_MAX_SIDE))
    info = _analyze_small(lin[::s, ::s])
    if s == 1:
        return info
    x0, y0, x1, y1 = info.rect
    return FrameInfo(rect=(x0 * s, y0 * s, min(full_w, x1 * s), min(full_h, y1 * s)),
                     border_found=info.border_found, base=info.base, tilt_deg=info.tilt_deg)


TILT_MIN_POINTS = 20      # need this many edge samples along a border to trust its angle
TILT_RUN = 3              # the picture starts where this many pixels in a row are not border-like
TILT_REJECT = 3.0         # drop samples further than this (px, small image) from the first line fit


def _fit_slope(t: Sequence[float], v: Sequence[float]) -> Optional[float]:
    """Least-squares slope of v over t, refit once without samples > TILT_REJECT from the first fit."""
    t = np.asarray(t, np.float64)
    v = np.asarray(v, np.float64)
    for _ in range(2):
        if t.size < TILT_MIN_POINTS:
            return None
        tm, vm = t.mean(), v.mean()
        den = float(((t - tm) ** 2).sum())
        if den == 0:
            return None
        b = float(((t - tm) * (v - vm)).sum() / den)
        a = vm - b * tm
        keep = np.abs(v - (a + b * t)) <= TILT_REJECT
        t, v = t[keep], v[keep]
    return b


def _edge_tilt(border_px: np.ndarray, y0: int, y1: int, x0: int, x1: int) -> Optional[float]:
    """
    Angle of the film border edges (degrees, + = clockwise). For every border side found, walk each line
    across the picture and note where the picture starts (TILT_RUN non-border pixels in a row), fit a
    straight line, convert its slope to an angle; average the sides. Same algorithm in NegEngine.java.
    """
    h, w = border_px.shape
    gap = max(2, int(h * FRAME_GAP)), max(2, int(w * FRAME_GAP))
    angles = []

    def first_run(seq) -> Optional[int]:
        run = 0
        for k, b in enumerate(seq):
            run = 0 if b else run + 1
            if run == TILT_RUN:
                return k - TILT_RUN + 1
        return None

    def horizontal(top: bool):
        ts, vs = [], []
        lo, hi = x0 + (x1 - x0) // 20, x1 - (x1 - x0) // 20
        for x in range(lo, hi):
            if top:
                start = max(0, y0 - 2 * gap[0])
                k = first_run(border_px[start: min(h, y0 + 3 * gap[0]), x])
                if k is not None:
                    ts.append(x); vs.append(start + k)
            else:
                start = min(h, y1 + 2 * gap[0])
                seg = border_px[max(0, y1 - 3 * gap[0]): start, x][::-1]
                k = first_run(seg)
                if k is not None:
                    ts.append(x); vs.append(start - 1 - k)
        b = _fit_slope(ts, vs)
        if b is not None:
            angles.append(math.degrees(math.atan(b)))

    def vertical(left: bool):
        ts, vs = [], []
        lo, hi = y0 + (y1 - y0) // 20, y1 - (y1 - y0) // 20
        for y in range(lo, hi):
            if left:
                start = max(0, x0 - 2 * gap[1])
                k = first_run(border_px[y, start: min(w, x0 + 3 * gap[1])])
                if k is not None:
                    ts.append(y); vs.append(start + k)
            else:
                start = min(w, x1 + 2 * gap[1])
                seg = border_px[y, max(0, x1 - 3 * gap[1]): start][::-1]
                k = first_run(seg)
                if k is not None:
                    ts.append(y); vs.append(start - 1 - k)
        b = _fit_slope(ts, vs)
        if b is not None:
            angles.append(-math.degrees(math.atan(b)))

    if y0 > 0:
        horizontal(True)
    if y1 < h:
        horizontal(False)
    if x0 > 0:
        vertical(True)
    if x1 < w:
        vertical(False)
    return float(np.mean(angles)) if angles else None


def _analyze_small(lin: np.ndarray) -> FrameInfo:
    h, w, _ = lin.shape
    lum = lin @ LUM
    mx = lin.max(axis=2)
    # Reference brightness from the middle of the frame, which is always picture: clear film base is at least
    # as bright as the thinnest picture area, sprocket holes are brighter still. (The whole frame's brightest
    # pixels are the holes, ~3x the base on the probe 2 frame, so they cannot be the reference.)
    center = lum[h // 4: h - h // 4, w // 4: w - w // 4]
    ref = float(np.percentile(center, 99.0))
    bright = lum >= FRAME_BRIGHT_REL * ref
    dark = mx < FRAME_DARK

    y0, y1 = _border_lines(lum, bright, dark)                                        # rows, all columns
    x0, x1 = _border_lines(lum[y0:y1].T, bright[y0:y1].T, dark[y0:y1].T)             # columns, inside rows
    y0, y1 = _border_lines(lum[:, x0:x1], bright[:, x0:x1], dark[:, x0:x1])           # rows again
    found = x0 > 0 or y0 > 0 or x1 < w or y1 < h

    base = None
    if found:
        outside = np.ones((h, w), bool)
        outside[y0:y1, x0:x1] = False
        cand = outside & bright & (mx < CLIP)
        if cand.sum() >= 200:
            px = lin[cand]
            bl = px @ LUM
            p5, p95 = np.percentile(bl, [5, 95])
            if p95 > HOLE_RATIO * p5:                                 # holes present: keep the dimmer group
                px = px[bl < math.sqrt(p5 * p95)]
            base = np.median(px, axis=0).astype(np.float32)

    iy, ix = int(round(FRAME_INSET * h)), int(round(FRAME_INSET * w))
    rect = (x0 + ix if x0 > 0 else 0, y0 + iy if y0 > 0 else 0,
            x1 - ix if x1 < w else w, y1 - iy if y1 < h else h)
    if rect[2] - rect[0] < w // 4 or rect[3] - rect[1] < h // 4:      # implausible: use the whole frame
        return FrameInfo(rect=(0, 0, w, h), border_found=False, base=base)
    tilt = _edge_tilt(bright | dark, y0, y1, x0, x1) if found else None
    return FrameInfo(rect=rect, border_found=found, base=base, tilt_deg=tilt)


def crop_to_picture(img: np.ndarray, info: FrameInfo) -> np.ndarray:
    """Auto-crop: the picture area without film border (same rect the levels are measured on)."""
    return picture_area(img, info)


def picture_area(img: np.ndarray, info: FrameInfo) -> np.ndarray:
    x0, y0, x1, y1 = info.rect
    return img[y0:y1, x0:x1]


def auto_base(lin: np.ndarray, top_fraction: float = 0.02, clip: float = CLIP) -> np.ndarray:
    """
    Film base color. First choice: the rebate found by analyze_frame (sprocket holes excluded).
    Fallback when no film border is visible: the *brightest* film area, excluding pixels with any
    channel >= `clip` (bare light). Prefer an explicit rect (sample_rect) whenever possible.
    """
    info = analyze_frame(lin)
    if info.base is not None:
        return info.base
    px = lin.reshape(-1, 3)
    px = px[::max(1, px.shape[0] // 400_000)]               # subsample for speed
    px = px[(px < clip).all(axis=1)]
    if px.shape[0] == 0:
        raise ValueError("everything is clipped - lower exposure")
    lum = px @ LUM
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
    """Map normalized log-exposure p (0..1) to output (0..1, display referred)."""
    p = np.clip(p, 0.0, 1.0)
    if gamma != 1.0:
        p = p ** (1.0 / gamma)
    if contrast != 1.0:
        # symmetric S-curve around 0.5 via a scaled logistic, renormalized to hit 0 and 1
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
        lo, hi = density_levels([picture_area(d, analyze_frame(lin))], prof.lo_pct, prof.hi_pct)
    else:
        lo, hi = np.asarray(prof.d_lo, np.float32), np.asarray(prof.d_hi, np.float32)

    # white-balance nudges are applied as density offsets (like a color-head filtration)
    span = (hi - lo)
    # temp: +R -B (warmer); tint: -G (more magenta). 1.0 ~= 5% of the channel's range.
    shift = np.array([prof.temp, -prof.tint, -prof.temp], np.float32) * 0.05 * span

    p = (d - lo.reshape(1, 1, 3) + shift.reshape(1, 1, 3)) / span.reshape(1, 1, 3)
    # p is proportional to log-exposure; the print curve below treats it as
    # perceptual lightness, which is close to how paper renders log-exposure.
    return print_curve(p, prof.contrast, prof.gamma)


def calibrate_roll(frames_lin: Sequence[np.ndarray], prof: RollProfile) -> RollProfile:
    """Fill in d_lo/d_hi from all frames of a roll (keeps color consistent across the roll)."""
    lo, hi = density_levels((picture_area(to_density(f, prof.base), analyze_frame(f)) for f in frames_lin),
                            prof.lo_pct, prof.hi_pct)
    return dataclasses.replace(prof, d_lo=lo.tolist(), d_hi=hi.tolist())
