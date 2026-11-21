"""Put the camera's raw-editor sidecars under the names darktable and RawTherapee read.

The camera can only create 8.3 file names, so it writes (next to each LTL00001.ARW):
  LTL00001.XMP  Lightroom / Camera Raw  (already the right name)
  LTL00001.DTX  darktable    -> LTL00001.ARW.xmp
  LTL00001.PP3  RawTherapee  -> LTL00001.ARW.pp3
Run this on the copied folder (or the card). ARW and JPEG files are never touched; an existing .ARW.xmp/.ARW.pp3
(e.g. your own edits) is kept unless --force.
"""
from __future__ import annotations

from pathlib import Path

RENAMES = ((".DTX", ".xmp"), (".PP3", ".pp3"))


def fix(folder: Path, log, force: bool = False, recursive: bool = True) -> int:
    folder = Path(folder)
    if not folder.is_dir():
        raise RuntimeError(f"not a folder: {folder}")
    files = folder.rglob("*") if recursive else folder.iterdir()
    done = skipped = 0
    for f in sorted(files):
        if not f.is_file():
            continue
        for src_ext, dst_ext in RENAMES:
            if f.suffix.upper() != src_ext:
                continue
            raw = next((r for r in f.parent.glob(f.stem + ".*") if r.suffix.upper() == ".ARW"), None)
            if raw is None:
                log(f"  skip {f.name}: no {f.stem}.ARW next to it")
                skipped += 1
                break
            dst = raw.with_name(raw.name + dst_ext)
            if dst.exists() and not force:
                log(f"  keep {dst.name} (exists; --force replaces it)")
                skipped += 1
                break
            if dst.exists():
                dst.unlink()
            f.rename(dst)
            log(f"  {f.name} -> {dst.name}")
            done += 1
            break
    log(f"{done} sidecar(s) renamed, {skipped} skipped; Lightroom's .XMP files need no change")
    return done
