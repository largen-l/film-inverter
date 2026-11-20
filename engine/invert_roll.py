"""
Batch-invert a folder of negative scans (the desktop twin of the camera's "Develop" mode).

Examples
--------
  # auto base + roll-level levels, write positives to <folder>/positives
  python invert_roll.py scans/roll01

  # base sampled from a rectangle (fractions x0 y0 x1 y1) on frame DSC01234
  python invert_roll.py scans/roll01 --base-frame DSC01234.ARW --base-rect 0.01 0.4 0.03 0.6

  # reuse a saved profile, punchier, slightly warm
  python invert_roll.py roll01 --profile roll01/roll.json --contrast 1.3 --temp 0.5

Source files are never modified.
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import numpy as np

import negengine as ne

IMG_EXTS = {".jpg", ".jpeg", ".tif", ".tiff", ".png"} | ne.RAW_EXTS


def pick_sources(folder: Path, prefer: str) -> list[Path]:
    """One source per frame: prefer raw (or jpeg) when both exist for the same stem."""
    by_stem: dict[str, list[Path]] = {}
    for p in sorted(folder.iterdir()):
        if p.suffix.lower() in IMG_EXTS and not p.stem.endswith("_POS"):
            by_stem.setdefault(p.stem, []).append(p)
    out = []
    for stem, files in by_stem.items():
        raws = [f for f in files if f.suffix.lower() in ne.RAW_EXTS]
        others = [f for f in files if f.suffix.lower() not in ne.RAW_EXTS]
        if prefer == "raw":
            out.append((raws or others)[0])
        else:
            out.append((others or raws)[0])
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder", type=Path)
    ap.add_argument("--out", type=Path, help="output folder (default: <folder>/positives)")
    ap.add_argument("--prefer", choices=["raw", "jpeg"], default="raw")
    ap.add_argument("--profile", type=Path, help="load an existing roll profile JSON")
    ap.add_argument("--base-frame", help="file name to sample the film base from")
    ap.add_argument("--base-rect", type=float, nargs=4, metavar=("X0", "Y0", "X1", "Y1"))
    ap.add_argument("--film", choices=["c41", "bw", "slide"], default="c41")
    ap.add_argument("--per-frame-levels", action="store_true", help="levels per frame instead of per roll")
    ap.add_argument("--contrast", type=float)
    ap.add_argument("--gamma", type=float)
    ap.add_argument("--temp", type=float)
    ap.add_argument("--tint", type=float)
    ap.add_argument("--max-side", type=int, default=0, help="downscale longest side (0 = full)")
    ap.add_argument("--crop", action="store_true", help="auto-crop each positive to its picture area (no film border)")
    args = ap.parse_args(argv)

    sources = pick_sources(args.folder, args.prefer)
    if not sources:
        print("no images found", file=sys.stderr)
        return 1
    out_dir = args.out or (args.folder / "positives")

    def load(p: Path) -> np.ndarray:
        img = ne.load_linear(p)
        if args.max_side and max(img.shape[:2]) > args.max_side:
            s = int(np.ceil(max(img.shape[:2]) / args.max_side))
            img = img[::s, ::s]
        return img

    t0 = time.time()
    frames = {p: load(p) for p in sources}
    print(f"loaded {len(frames)} frames in {time.time() - t0:.1f}s")

    if args.profile and args.profile.exists():
        prof = ne.RollProfile.load(args.profile)
    else:
        if args.base_frame:
            bf = next((p for p in frames if p.name == args.base_frame), None)
            if bf is None:
                print(f"--base-frame {args.base_frame} not found", file=sys.stderr)
                return 1
            img = frames[bf]
            base = ne.sample_rect(img, tuple(args.base_rect)) if args.base_rect else ne.auto_base(img)
        else:
            # auto: median of each frame's brightest-unclipped estimate
            base = np.median(np.stack([ne.auto_base(f) for f in frames.values()]), axis=0)
        prof = ne.RollProfile(base=base.tolist(), film_type=args.film)

    for k in ("contrast", "gamma", "temp", "tint"):
        v = getattr(args, k)
        if v is not None:
            setattr(prof, k, v)

    if args.per_frame_levels:
        prof.d_lo = prof.d_hi = None
    elif prof.d_lo is None:
        prof = ne.calibrate_roll(list(frames.values()), prof)

    out_dir.mkdir(parents=True, exist_ok=True)
    prof.save(out_dir / "roll.json")
    print("base (linear RGB):", np.round(prof.base, 4))

    for p, img in frames.items():
        t = time.time()
        pos = ne.invert(img, prof)
        info = ne.analyze_frame(img)
        if args.crop:
            pos = ne.crop_to_picture(pos, info)
        ne.save_srgb(pos, out_dir / f"{p.stem}_POS.JPG")
        tilt = "" if info.tilt_deg is None else f", film edge tilt {info.tilt_deg:+.2f} deg"
        print(f"  {p.name} -> {p.stem}_POS.JPG  ({time.time() - t:.2f}s{tilt})")
    print(f"done -> {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
