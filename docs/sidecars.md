# Raw-editor sidecars

With **Menu › Save: RAW+JPEG** (or RAW only) and **Menu › Sidecars** on, the camera writes a small settings file next
to each ARW, so the RAW opens as a positive in your editor. The ARW itself is always the untouched negative.

| Editor | The camera writes | After copying the card |
|---|---|---|
| Lightroom Classic / Lightroom / Camera Raw (Photoshop, Bridge) | `LTL00001.XMP` | nothing to do |
| darktable | `LTL00001.DTX` | `filminverter sidecars <folder>` → `LTL00001.ARW.xmp` |
| RawTherapee (5.9 or newer) | `LTL00001.PP3` | `filminverter sidecars <folder>` → `LTL00001.ARW.pp3` |

The camera can only create 8.3 file names, and darktable and RawTherapee only read `NAME.ARW.xmp` / `NAME.ARW.pp3`,
hence the rename. For darktable the command also adds the ARW's as-shot white balance (it needs rawpy:
`pip install rawpy`; the camera can't read those multipliers, Sony encrypts them).

```
python tool/run.py sidecars D:\photos\roll02
```

Existing `.ARW.xmp` / `.ARW.pp3` files (your own edits) are kept unless you add `--force`.

## What the sidecars do

All of them rely on **Calibrate film base** (or a saved film in Menu › Film): the white balance recorded in the RAW is
the one that makes the clear film base neutral, so the base is gray in each editor's "as shot" rendering.

- **Lightroom:** white balance *As Shot*, linear master curve, and one inverted point curve per color channel: the
  film base maps to black, a density 1.6 above it to white, with C-41's stronger red contrast. Lightroom's sliders
  work backwards on an inverted picture (Exposure darkens, Blacks affects the highlights); adjust the curves instead.
- **darktable:** white balance *as shot* + **negadoctor** (its negative module) with the film base as D min. darktable's
  default workflow modules are left off so the white balance stays as shot; turn on filmic/sigmoid or color
  calibration afterwards if you like.
- **RawTherapee:** **Film Negative** with the film base as the reference (mapped to near-black), red/blue ratio
  1.1 / 0.95, then +2.5 EV with Black 1500.

They give a neutral black base and a starting point, not a finished picture: set the levels or exposure per frame.

## How the numbers were tuned

On the a6500 with Kodak Ektacolor Pro 160 (roll02, calibrated at 2800 K, G1; base 177/179/179 in the camera's
measurement JPEG), with darktable 5.6.2 and RawTherapee 5.13 on Windows, rendering with `darktable-cli` and
`rawtherapee-cli` from the same files the camera writes (`app/FilmInverter/test/SidecarsDesktopTest.java`):

- **Where the base sits:** a frame of nothing but clear film, rendered with a range of base factors. At **0.38** times
  the base's linear value in the camera JPEG, both editors render it neutral black (RawTherapee 6/255, its own
  convention; darktable 1.5/255). The RAW itself agrees: rawpy's linear base is 0.17 against the JPEG's 0.45.
- **darktable white balance:** without it the base reached negadoctor orange (darktable's default white balance is a
  fixed "camera reference"), giving a black or blue picture.
- **RawTherapee look:** with its defaults (ratios 1.36 / 0.86, no exposure) the picture was correct but very dark and
  warm; +2.5 EV, Black 1500 and ratios 1.1 / 0.95 bring it close to darktable's brightness and color.
- **Lightroom** could not be tested here (no license). Its numbers use the same 0.38 and assume Lightroom's curve
  works in a gamma-encoded space; a power-law inversion keeps its shape under any gamma, so mainly the black point may
  be off. The owner's Lightroom test decides.

## Testing in Lightroom

1. On the camera: **Menu › Save: RAW+JPEG**, **Menu › Sidecars: Lightroom** (or *all three*), then load your film in
   **Menu › Film** (or *Calibrate film base*). Take a few shots: the toast says "+ 1 sidecar".
2. Copy the card's `DCIM/<folder>` to the laptop. Each `LTL000NN.ARW` needs its `LTL000NN.XMP` in the same folder.
3. Lightroom Classic: **File › Import**, pick that folder. Lightroom reads the XMP files during import.
   Already imported? Select the photos, then **Metadata › Read Metadata from File**.
4. Open one in **Develop**. Please report:
   - Does it open as a positive? Is the film border (clear base) black, and neutral (not tinted)?
   - Too dark, too bright, too flat? A screenshot next to the camera's own positive JPEG helps.
   - In **Tone Curve › Point Curve › Red/Green/Blue**, the curves should go from top-left to bottom-right.
