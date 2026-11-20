# Probe run 3: a6500, 2026-11-19

Probe 0.3 (curve-format explorer). The log and the owner's 14 photos of the screen are kept locally in
`logs/probe3/` (git-ignored). The scene was the `LTL00002` color negative (cat) on a light source.

## Result: the extended gamma table format

**`CameraEx.GammaTable` = 1 curve × 1024 entries, uint16 little-endian, output range 0–1023 (10-bit).**
One curve is shared by R, G and B.

Evidence (photos):
- **#3 (1×1024, u16 LE, 10-bit), plain:** looks like the normal live view of the negative.
- **#3 inverted** (`out[i] = 1023 - i`): a clean, **live positive** of the cat, strongly blue because the
  orange mask is not compensated yet.
- **#2, #8, #9:** look like #3 with about 4× the gain and clipped highlights. That is what you get if the
  output scale is 10-bit (12-bit values, or a 256-entry ramp repeated 4×).
- **#1, #4, #5, #6, #10–#13:** wrong layouts or scales; mostly yellow/white with noise.

Small **yellow speckles** appear in the brightest areas with #3 (in the shadows when inverted). They are
probably values overflowing past the top of the output range further down the pipeline; next step: test
headroom (max 1023 vs ~1000 vs ~960).

To use it: `t = cameraEx.createGammaTable()` (2048 bytes, **uninitialized**, so always fill all of it),
`t.write(bytes)`, `cameraEx.setExtendedGammaTable(t)`, then release the previous table.
`setExtendedGammaTable(null)` returns to the camera's own curve. Changes are instant.

## Inverted live view: proven

The plan's lead #1 works on the a6500: a decreasing 1024-entry table inverts live view in hardware at full
frame rate. Remaining for a usable view: cancel the orange mask (custom WB on the base, or the RGB matrix,
whose format is still unknown since `getRGBMatrix()` returns null by default), set the tone/contrast from
the roll profile, and check whether the table also affects **captured** images.

## Touch: works

Touch events reach the app (`dispatchTouchEvent` with x/y in a ~640×480 space) and Buttons respond to taps.
Touch UI is viable; keys stay the primary input.

## Keys (additions)

| Button | keyCode | scanCode |
|---|---|---|
| Left | 21 (DPAD_LEFT) | 105 |
| Right | 22 (DPAD_RIGHT) | 106 |

## Exit: clean

With every table released and no color-depth switching, leaving the explorer restored and released the
camera in ~150–190 ms, and exiting the app was clean (`onPause` logged, no freeze, no "recovering").
The probe 2 freeze was most likely the 12 unreleased GammaTable buffers.

## Output files: 8.3 names only (likely)

`/FILMINV` was created (`mkdirs` true), but opening `FILMINV/LTL00002_POS.JPG` failed with ENOENT, as did
`DCIM/FITEST/…_POS.JPG` in probe 2. Every file the app *has* written uses an 8.3 name (`FIPROBE.TXT`,
`FIGAMMA.BIN`, `NTPPROBE.TXT`). Working hypothesis: the camera's filesystem layer only accepts 8.3 names.
Next probe: write `FILMINV/LTL00002.JPG` and also try one long name to confirm.
