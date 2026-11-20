"""Camera UI themes: the built-in palettes (keep in sync with Theme.builtIns() in the app), validation,
8.3 file names, and writing .JSN files to the card's FILMINV/THEMES folder."""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Dict, List

KEYS = [("accent", "Accent"), ("onAccent", "Text on accent"), ("text", "Text"), ("textDim", "Dim text"),
        ("bar", "Bottom bar"), ("pill", "Status pills"), ("pillText", "Pill text"), ("chipBorder", "Borders / track")]

BUILTINS: List[Dict] = [
    {"name": "Minimal", "accent": "#f2c14e", "onAccent": "#0b0b0b", "text": "#f2f2f2", "textDim": "#b8b8b8", "bar": "#c7080808", "pill": "#8c000000", "pillText": "#f2f2f2", "chipBorder": "#3a3a3a", "radius": 12},
    {"name": "Camera", "accent": "#ff8a3d", "onAccent": "#000000", "text": "#ececec", "textDim": "#bdbdbd", "bar": "#eb000000", "pill": "#cc141414", "pillText": "#ececec", "chipBorder": "#3a3a3a", "radius": 4},
    {"name": "Material", "accent": "#82d5c8", "onAccent": "#00201b", "text": "#e3e3e3", "textDim": "#bec9c6", "bar": "#f01d2321", "pill": "#bf111414", "pillText": "#e3e3e3", "chipBorder": "#5b6563", "radius": 20},
    {"name": "Glass", "accent": "#ffd60a", "onAccent": "#111111", "text": "#ffffff", "textDim": "#d0d0d0", "bar": "#9e1c1c1e", "pill": "#8c1e1e1e", "pillText": "#ffffff", "chipBorder": "#4dffffff", "radius": 24},
    {"name": "Enlarger", "accent": "#d1352b", "onAccent": "#fff3f1", "text": "#1e1e1e", "textDim": "#3a3a3a", "bar": "#f0bfc2c5", "pill": "#cc2c2a26", "pillText": "#e9c84a", "chipBorder": "#5a5d61", "radius": 6},
    {"name": "Light table", "accent": "#d1242f", "onAccent": "#ffffff", "text": "#1d1d1d", "textDim": "#4a4a4a", "bar": "#f0f4f6f7", "pill": "#e6f3e3b5", "pillText": "#1d1d1d", "chipBorder": "#2a2118", "radius": 3},
    {"name": "Scope", "accent": "#52e08a", "onAccent": "#050505", "text": "#d9d9d9", "textDim": "#8a8a8a", "bar": "#f0050505", "pill": "#b3000000", "pillText": "#52e08a", "chipBorder": "#2c2c2c", "radius": 0},
    {"name": "Safelight", "accent": "#ff4a3d", "onAccent": "#000000", "text": "#ff4a3d", "textDim": "#c0392b", "bar": "#ff000000", "pill": "#ff000000", "pillText": "#ff4a3d", "chipBorder": "#ff4a3d", "radius": 14},
]

_COLOR = re.compile(r"^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")


def parse_color(c: str):
    """Android '#AARRGGBB' or '#RRGGBB' -> (r, g, b, a)."""
    if not _COLOR.match(c or ""):
        raise ValueError(f"bad color {c!r} (use #RRGGBB or #AARRGGBB)")
    h = c[1:]
    a = int(h[:2], 16) if len(h) == 8 else 255
    h = h[2:] if len(h) == 8 else h
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a


def make_color(r: int, g: int, b: int, a: int = 255) -> str:
    return f"#{r:02x}{g:02x}{b:02x}" if a >= 255 else f"#{a:02x}{r:02x}{g:02x}{b:02x}"


def validate(t: Dict) -> List[str]:
    errs = []
    if not str(t.get("name", "")).strip():
        errs.append("name is required")
    for k, _ in KEYS:
        if k in t:
            try:
                parse_color(t[k])
            except ValueError as e:
                errs.append(f"{k}: {e}")
    if not 0 <= int(t.get("radius", 12)) <= 40:
        errs.append("radius must be 0-40")
    if not 0.7 <= float(t.get("textScale", 1.0)) <= 1.6:
        errs.append("textScale must be 0.7-1.6")
    return errs


def file_name(name: str) -> str:
    """8.3 name (the camera refuses longer ones): up to 8 of A-Z 0-9 _ plus .JSN."""
    stem = re.sub(r"[^A-Z0-9_]", "", name.upper())[:8] or "THEME"
    return stem + ".JSN"


def to_json(t: Dict) -> str:
    out = {"name": t["name"]}
    for k, _ in KEYS:
        if k in t:
            out[k] = t[k]
    out["radius"] = int(t.get("radius", 12))
    out["textScale"] = round(float(t.get("textScale", 1.0)), 2)
    return json.dumps(out, indent=2)


def load(path: Path) -> Dict:
    t = json.loads(Path(path).read_text(encoding="utf-8"))
    base = dict(BUILTINS[0])
    base.update(t)
    errs = validate(base)
    if errs:
        raise ValueError("; ".join(errs))
    return base


def install_to_card(card: Path, t: Dict, log, fname: str = "") -> Path:
    errs = validate(t)
    if errs:
        raise ValueError("; ".join(errs))
    d = card / "FILMINV" / "THEMES"
    d.mkdir(parents=True, exist_ok=True)
    dest = d / (file_name(fname) if fname else file_name(t["name"]))
    dest.write_text(to_json(t), encoding="utf-8")
    log(f"wrote {dest}  (camera: Menu > Theme to select it)")
    return dest
