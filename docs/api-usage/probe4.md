# Probe run 4: a6500, 2026-11-19

Probe 0.4 (inverted live view with adjustable rows). The log, the in-camera positive and the owner's photo are
kept locally in `logs/probe4/` (git-ignored).

## First in-camera positive

`/FILMINV/LTL00002.JPG`: a correct positive of the `LTL00002` negative, 1500×1000, made in-camera in ~7 s
(decode, auto base, levels, LUT, encode). Slightly cool overall; the base estimate still uses the old auto-base,
which the sprocket holes fool.

## Filenames: 8.3 only (confirmed)

Write test in `/FILMINV`: `SHORT.TXT` OK, `lower.txt` OK, `AB_CD.TXT` OK, **`LONGNAME_TEST.TXT` ENOENT**.
Base names longer than 8 characters fail with ENOENT. All app output must use 8.3 names; the positive keeps
the original's name in a separate folder.

## Yellow speckles = focus peaking

The owner's focus peaking (yellow) was active and is drawn in the live-view pipeline, so it stays on
inside the app. With peaking off the speckles were gone; with peaking set to red they show red. Peaking is not
a camera parameter (only `Settings.KeyFunction.PEAKING_*` key-assignment constants exist), so the app cannot
switch it. The curve needs no headroom: **top = 1023** is fine.

## RGB matrix

`rgb-matrix-mode=true` + `rgb-matrix=s,0,0,0,s,0,0,0,s` was accepted and read back for every scale tested
(1 … 4096), and `rgb-matrix-mode=false` returns to normal. The owner's photo at ×4096 shows an intact
inverted image. Which scale equals 1.0 (or whether the matrix affects live view at all) is still to be
confirmed by the owner; next step if it's live: a non-identity matrix (channel gains) to cancel the
orange mask.

## White balance rows

Not exercised in this run (no temp / shift changes in the log). Saved and restored values all read back OK:
`whitebalance=custom color-temperture-white-balance=3600 white-balance-shift-mode=false lb=0 cc=0`.

## Capture: the curve is applied to saved JPEGs (full resolution)

- `LTL00003`: Quality had reverted to RAW only, so only the ARW was written.
- `LTL00004` (RAW+JPEG, plain curve, ordinary room scene): normal JPEG.
- **`LTL00005` (RAW+JPEG, POSITIVE curve, top 800, the cat negative on the light source): the 6000×4000
  JPEG is a positive.** Cat and plants read as a positive (blue cast, mask not compensated), sprocket holes
  are black, the dark desk around the strip is light gray. The ARW is the untouched negative.

So `setExtendedGammaTable` before `takePicture` gives **full-resolution in-camera positives with no app-side
processing**: Mode B (Scan) can capture positives directly, at camera speed. Remaining: cancel the orange
mask before the curve (WB / matrix) and put the levels into the curve, so the JPEG needs no post-processing.
`takePicture(null, null, null)` honors the camera's Quality (RAW+JPEG gives both files).

## Settings restore

Leaving live view restored all seven touched keys, verified by reading them back (all `ok`), in 410 ms.
Exit clean.

## Keys (additions)

keyCode 0 / scanCode **516** and **517** seen (unknown buttons; owner to identify).
