"""Put the camera's raw-editor sidecars under the names darktable and RawTherapee read.

The camera can only create 8.3 file names, so it writes (next to each LTL00001.ARW):
  LTL00001.XMP  Lightroom / Camera Raw  (already the right name, nothing to do)
  LTL00001.DTX  darktable    -> LTL00001.ARW.xmp, plus the ARW's as-shot white balance (see below)
  LTL00001.PP3  RawTherapee  -> LTL00001.ARW.pp3
Run this on the copied folder (or the card). ARW and JPEG files are never touched; an existing .ARW.xmp/.ARW.pp3
(e.g. your own edits) is kept unless --force.

darktable's negative module needs the film base neutral, i.e. white balance "as shot", but its white balance module
stores the multipliers themselves, and Sony keeps them encrypted in the ARW where the camera app can't read them.
So the darktable sidecar gets that entry here, from rawpy. Must match Sidecars.darktableWithWb in the camera app.
"""
from __future__ import annotations

import struct
from pathlib import Path

RENAMES = ((".DTX", ".xmp"), (".PP3", ".pp3"))
NO_BLEND = "gz11eJxjYGBgkGAAgRNODGiAEV0AJ2iwh+CRyscOAAdeGQQ="


def darktable_wb_entry(red: float, blue: float) -> str:
    hexp = struct.pack("<4fi", red, 1.0, blue, 1.0, 0).hex()       # temperature v4, preset 0 = as shot
    return ("     <rdf:li\n      darktable:num=\"0\"\n      darktable:operation=\"temperature\"\n      darktable:enabled=\"1\"\n"
            f"      darktable:modversion=\"4\"\n      darktable:params=\"{hexp}\"\n"
            "      darktable:multi_name=\"\"\n      darktable:multi_name_hand_edited=\"0\"\n      darktable:multi_priority=\"0\"\n"
            f"      darktable:blendop_version=\"13\"\n      darktable:blendop_params=\"{NO_BLEND}\"/>\n")


def darktable_with_wb(xmp: str, red: float, blue: float) -> str:
    if 'darktable:operation="temperature"' in xmp:
        return xmp
    return (xmp.replace('darktable:history_end="1"', 'darktable:history_end="2"')
               .replace('darktable:num="0"', 'darktable:num="1"')
               .replace("<darktable:history>\n    <rdf:Seq>\n",
                        "<darktable:history>\n    <rdf:Seq>\n" + darktable_wb_entry(red, blue)))


def as_shot_wb(raw: Path):
    """(red, blue) multipliers normalized to green, or None without rawpy."""
    try:
        import rawpy
    except ImportError:
        return None
    with rawpy.imread(str(raw)) as r:
        wb = r.camera_whitebalance
    return wb[0] / wb[1], wb[2] / wb[1]


def fix(folder: Path, log, force: bool = False, recursive: bool = True) -> int:
    folder = Path(folder)
    if not folder.is_dir():
        raise RuntimeError(f"not a folder: {folder}")
    files = folder.rglob("*") if recursive else folder.iterdir()
    done = skipped = 0
    warned = False
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
            text = f.read_text(encoding="utf-8")
            note = ""
            if src_ext == ".DTX":
                wb = as_shot_wb(raw)
                if wb is None:
                    if not warned:
                        log("  note: rawpy is not installed, so darktable sidecars get no white balance entry "
                            "(set white balance to 'as shot' by hand): pip install rawpy")
                        warned = True
                else:
                    text = darktable_with_wb(text, *wb)
                    note = f" (white balance as shot: R {wb[0]:.3f} B {wb[1]:.3f})"
            dst.write_text(text, encoding="utf-8")
            f.unlink()
            log(f"  {f.name} -> {dst.name}{note}")
            done += 1
            break
    log(f"{done} sidecar(s) ready, {skipped} skipped; Lightroom's .XMP files need no change")
    return done
