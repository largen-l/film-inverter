"""
Synthetic tests: build a fake colour negative from a known scene with a simple
film model (orange mask + different layer contrasts), run the engine, and check
that (a) grey stays grey, (b) tones stay in order, (c) the roll profile
round-trips. Run:  python -m pytest engine/tests   (or just python test_negengine.py)
"""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import negengine as ne  # noqa: E402

# crude C-41 model: per-layer contrast (gamma) and base density (orange mask)
LAYER_GAMMA = np.array([0.55, 0.62, 0.70], np.float32)   # R, G, B layers differ
DMIN = np.array([0.20, 0.55, 0.85], np.float32)          # orange mask: blue densest
LIGHT = np.array([0.95, 0.92, 0.97], np.float32)         # slightly uneven light source


def make_negative(scene_lin: np.ndarray, exposure_offset: float = 0.0) -> np.ndarray:
    """Scene linear RGB -> what the camera sees through the negative (linear)."""
    logE = np.log10(np.clip(scene_lin, 1e-4, None)) + exposure_offset
    d = DMIN + LAYER_GAMMA * np.clip(logE + 3.0, 0.0, None)  # no exposure -> density stays at Dmin (clear base)
    return (LIGHT * 10.0 ** (-d)).astype(np.float32) * 1.45  # base lands at ~0.9: exposed to the right


def grey_ramp(h=64, w=256):
    v = np.geomspace(0.01, 1.0, w, dtype=np.float32)
    return np.repeat(np.repeat(v[None, :, None], h, 0), 3, 2)


def colour_chart():
    patches = np.array([
        [0.40, 0.20, 0.10], [0.10, 0.30, 0.60], [0.15, 0.45, 0.15],
        [0.60, 0.55, 0.10], [0.50, 0.10, 0.10], [0.18, 0.18, 0.18],
    ], np.float32)
    return np.repeat(np.repeat(patches[None], 40, 0), 40, 1).reshape(40, 6 * 40, 3)


def with_base_strip(neg):
    """Add a strip of clear film base (scene exposure ~0) on the left, like a frame rebate."""
    h, w, _ = neg.shape
    base = make_negative(np.full((h, 12, 3), 1e-4, np.float32))
    return np.concatenate([base, neg], 1)


def test_grey_stays_neutral():
    neg = with_base_strip(make_negative(grey_ramp()))
    base = ne.sample_rect(neg, (0.0, 0.0, 0.03, 1.0))
    prof = ne.RollProfile(base=base.tolist())
    pos = ne.invert(neg, prof)[:, 20:]               # skip the base strip
    spread = (pos.max(axis=2) - pos.min(axis=2))      # per-pixel channel disagreement
    assert float(np.median(spread)) < 0.03, np.median(spread)


def test_tones_monotonic():
    neg = with_base_strip(make_negative(grey_ramp()))
    base = ne.sample_rect(neg, (0.0, 0.0, 0.03, 1.0))
    pos = ne.invert(neg, ne.RollProfile(base=base.tolist()))[:, 20:]
    lum = pos.mean(axis=(0, 2))
    assert np.all(np.diff(lum[2:-2]) >= -1e-3)          # brighter scene -> brighter positive
    assert lum[-1] - lum[0] > 0.8                        # uses most of the range


def test_auto_base_close_to_true_base():
    neg = with_base_strip(make_negative(grey_ramp()))
    true_base = ne.sample_rect(neg, (0.0, 0.0, 0.03, 1.0))
    guess = ne.auto_base(neg)
    assert np.allclose(guess, true_base, rtol=0.15), (guess, true_base)


def test_roll_levels_consistent_across_frames():
    # same chart shot at two exposures within one roll: roll levels keep the grey patch neutral in both
    a = with_base_strip(make_negative(colour_chart(), 0.0))
    b = with_base_strip(make_negative(np.concatenate([colour_chart(), grey_ramp(40, 240)], 0), 0.3))
    base = ne.sample_rect(a, (0.0, 0.0, 0.03, 1.0))
    prof = ne.calibrate_roll([a, b], ne.RollProfile(base=base.tolist()))
    pa = ne.invert(a, prof)
    grey = pa[5:35, 12 + 5 * 40 + 5: 12 + 6 * 40 - 5].reshape(-1, 3).mean(0)
    assert grey.max() - grey.min() < 0.04, grey


def film_strip(scene):
    """
    Picture + what a real 35 mm scan has below it (probe 2 cat frame): clear rebate (film base) with
    sprocket holes (bare light: brighter than the base and neutral, but not clipped), then an unlit
    black band outside the film.
    """
    neg = make_negative(scene)
    h, w, _ = neg.shape
    rebate = make_negative(np.full((18, w, 3), 1e-4, np.float32))
    for x in range(8, w - 16, 40):                     # holes: 16 px wide every 40 px
        rebate[4:14, x:x + 16] = 0.93
    unlit = np.full((6, w, 3), 0.001, np.float32)
    return np.concatenate([neg, rebate, unlit], 0), rebate[0, 0].copy()


def scene_chart_and_ramp():
    return np.concatenate([colour_chart(), grey_ramp(40, 240)], 0)


def test_frame_analysis_finds_picture_and_base():
    strip, true_base = film_strip(scene_chart_and_ramp())
    info = ne.analyse_frame(strip)
    x0, y0, x1, y1 = info.rect
    assert info.border_found
    assert y1 <= 80 and y1 >= 70, info.rect          # picture is rows 0..79; border starts at 80
    assert x0 == 0 and y0 == 0 and x1 == strip.shape[1], info.rect
    assert info.base is not None and np.allclose(info.base, true_base, rtol=0.05), (info.base, true_base)


def test_auto_base_ignores_sprocket_holes():
    strip, true_base = film_strip(scene_chart_and_ramp())
    assert np.allclose(ne.auto_base(strip), true_base, rtol=0.05)


def test_auto_levels_ignore_film_border():
    # fully automatic (auto base, per-frame levels): the grey patch must stay grey despite holes + unlit band
    strip, _ = film_strip(scene_chart_and_ramp())
    pos = ne.invert(strip, ne.RollProfile(base=ne.auto_base(strip).tolist()))
    grey = pos[5:35, 5 * 40 + 5: 6 * 40 - 5].reshape(-1, 3).mean(0)
    assert grey.max() - grey.min() < 0.04, grey


def test_profile_roundtrip(tmp_path=None):
    import tempfile
    p = ne.RollProfile(base=[0.5, 0.3, 0.2], d_lo=[0.1, 0.1, 0.1], d_hi=[1, 1, 1], contrast=1.2)
    d = Path(tmp_path or tempfile.mkdtemp())
    p.save(d / "roll.json")
    q = ne.RollProfile.load(d / "roll.json")
    assert q == ne.RollProfile.from_json(p.to_json())
    assert q.contrast == 1.2


def test_srgb_roundtrip():
    x = np.linspace(0, 1, 101, dtype=np.float32)
    assert np.allclose(ne.linear_to_srgb(ne.srgb_to_linear(x)), x, atol=1e-5)


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
