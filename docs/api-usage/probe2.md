# Probe run 2: a6500, 2026-11-19

Probe 0.2 (`com.filminverter`). The raw log, `FIGAMMA.BIN` and the owner's photo of the screen are kept
locally in `logs/probe2/` (git-ignored). Test frame: `LTL00002` (RAW+JPEG, color negative of a cat,
sprocket holes and rebate visible at the bottom).

## Live view tone curve: works, format unknown

- `ParametersModifier.isExtendedGammaTableSupported()` = **true**.
- By default the camera has **no** extended table: `getExtendedGammaTable()` = null.
- `createGammaTable()` returns a **2048-byte** buffer that is *not* initialized: it held a `0xAA` fill with
  random bit flips, different on each run. The probe "inverted" that garbage.
- `setExtendedGammaTable(t)` **changed live view instantly**, and reading the table back matched what was
  written. `setExtendedGammaTable(null)` returned live view to normal.
- The result was mostly white with sharp colored contour lines (owner's photo): a garbage curve.
  **Colored** contours suggest the table holds separate per-channel curves.

So the hardware path for inverted live view is real; what's missing is the table layout. Probe 3 builds
clean identity/inverted curves in 13 candidate layouts (1×1024 / 4×256 planar or interleaved / 1×512 u32 /
2048×u8; LE/BE; 16/12/10/8-bit) for the owner to step through: the layout whose plain curve looks normal is right.

## RGB matrix

`isRGBMatrixSupported()` = true, `getRGBMatrix()` = **null** (not set by default), so the R/B swap test was
skipped. Format (length, scale) still unknown.

## Preview frames

`Camera.setPreviewCallback` via CameraEx: **0 frames in 5 s**, preview size null. Software (app-side)
inverted live view is not possible this way; the hardware tone curve is the route.

## Output files

`mkdirs("DCIM/FITEST")` failed (ENOENT; the camera's DCIM is not writable for new folders), so no
positive was written in-camera. Probe 3 writes to `/FILMINV/` on the card root instead. Whether a
positive can appear in the camera's playback needs a file in a DCF folder plus AvindexStore
registration: an M2 question.

## Exit freeze

After two explorer runs, exiting froze the camera; it showed a "recovering" message and needed a power
cycle. The log ends normally (`=== exit ===`), so the hang came after our code returned. Suspects:
GammaTable buffers never released (12 created), the null-restore, the color-depth switching. Probe 3
releases every table, drops the color-depth switching and logs each lifecycle step with timestamps.

## Touch

No touch events in the log (the owner used the buttons). Still open.

## Desktop engine on the real frame

- JPEG path + base sampled from the rebate between the sprocket holes (`--base-rect 0.08 0.914 0.15 0.926`)
  → a correct, slightly cool positive.
- Auto base is fooled by the **sprocket holes** (bare light, brighter than the base) → blue cast.
- RAW path gives a strong blue cast even with the manual base (base read as R < G in raw space): the RAW
  pipeline needs white balance / camera matrix work. Both are M1 items.

## Keys (additions)

Probe 3 also maps left/right and both dials (ScalarInput `ISV_KEY_LEFT/RIGHT`, `ISV_DIAL_1/2_*`) and up.

## Install under Proxmox

The first `pmca-console install` times out while the camera switches to install mode (054C:06A8); a second
run succeeds. `tools/install.ps1` / `install.sh` now retry once automatically.
