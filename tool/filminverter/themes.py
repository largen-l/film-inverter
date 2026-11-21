"""Camera UI themes: the built-in layouts and palettes (keep in sync with Theme.builtIns() in the app), validation,
8.3 file names, and writing .JSN files to the card's FILMINV/THEMES folder."""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Dict, List

KEYS = [("accent", "Accent"), ("onAccent", "Text on accent"), ("text", "Text"), ("textDim", "Dim text"),
        ("bar", "Bottom bar"), ("pill", "Status pills"), ("pillText", "Pill text"), ("chipBorder", "Borders / track")]

LAYOUTS = ["minimal", "camera", "material", "glass", "enlarger", "lighttable", "scope", "safelight"]

BUILTINS: List[Dict] = [
    {"name": "Minimal", "layout": "minimal", "accent": "#f2c14e", "onAccent": "#0b0b0b", "text": "#f2f2f2", "textDim": "#b8b8b8", "bar": "#c7080808", "pill": "#8c000000", "pillText": "#f2f2f2", "chipBorder": "#3a3a3a", "radius": 12},
    {"name": "Camera", "layout": "camera", "accent": "#ff8a3d", "onAccent": "#000000", "text": "#ececec", "textDim": "#bdbdbd", "bar": "#ff000000", "pill": "#ff141414", "pillText": "#ececec", "chipBorder": "#2a2a2a", "radius": 6},
    {"name": "Material", "layout": "material", "accent": "#82d5c8", "onAccent": "#00201b", "text": "#e3e3e3", "textDim": "#bec9c6", "bar": "#ff1d2321", "pill": "#ff111414", "pillText": "#e3e3e3", "chipBorder": "#5b6563", "radius": 20},
    {"name": "Glass", "layout": "glass", "accent": "#ffd60a", "onAccent": "#111111", "text": "#ffffff", "textDim": "#d0d0d0", "bar": "#9e1c1c1e", "pill": "#8c1e1e1e", "pillText": "#ffffff", "chipBorder": "#4dffffff", "radius": 24},
    {"name": "Enlarger", "layout": "enlarger", "accent": "#ff3b2e", "onAccent": "#fff3f1", "text": "#2b2b2b", "textDim": "#3a3a3a", "bar": "#ffbfc2c5", "pill": "#ff2c2a26", "pillText": "#e9c84a", "chipBorder": "#5a5d61", "radius": 6},
    {"name": "Light table", "layout": "lighttable", "accent": "#d1242f", "onAccent": "#ffffff", "text": "#1d1d1d", "textDim": "#4a4a4a", "bar": "#fff4f6f7", "pill": "#fff3e3b5", "pillText": "#e8a44a", "chipBorder": "#2a2118", "radius": 3},
    {"name": "Scope", "layout": "scope", "accent": "#52e08a", "onAccent": "#050505", "text": "#d9d9d9", "textDim": "#8a8a8a", "bar": "#ff050505", "pill": "#b3000000", "pillText": "#52e08a", "chipBorder": "#2c2c2c", "radius": 0},
    {"name": "Safelight", "layout": "safelight", "accent": "#ff4a3d", "onAccent": "#000000", "text": "#ff4a3d", "textDim": "#c0392b", "bar": "#ff000000", "pill": "#ff000000", "pillText": "#ff4a3d", "chipBorder": "#ff4a3d", "radius": 14},
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
    if t.get("layout", "minimal") not in LAYOUTS:
        errs.append("layout must be one of " + ", ".join(LAYOUTS))
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
    out = {"name": t["name"], "layout": t.get("layout", "minimal")}
    for k, _ in KEYS:
        if k in t:
            out[k] = t[k]
    out["radius"] = int(t.get("radius", 12))
    out["textScale"] = round(float(t.get("textScale", 1.0)), 2)
    return json.dumps(out, indent=2)


def load(path: Path) -> Dict:
    t = json.loads(Path(path).read_text(encoding="utf-8"))
    base = dict(next((b for b in BUILTINS if b["layout"] == t.get("layout", "minimal")), BUILTINS[0]))
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
