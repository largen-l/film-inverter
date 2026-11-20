# Probe runs 5–7 and the 0.8 fixes: a6500, 2026-11-19

Logs in `logs/probe5-FIPROBE.TXT` and `logs/probe7/` (git-ignored).

## In-app capture (0.5/0.6)

- Captures with the POSITIVE curve give full-res positive JPEGs (also `LTL00006`–`08`); RAWs stay negative.
- **A second `takePicture` failed** ("takePicture failed") until live view was reopened. Cause: Sony treats
  `takePicture` as "shutter pressed" and waits for `CameraEx.cancelTakePicture()` ("released"), which
  PMCADemo sends on shutter key-up. 0.7+ calls it ~300 ms after each capture and blocks new captures
  (and MENU, 0.8) for ~3 s while the file is saved.
- The app lists positive captures in `/FILMINV/CAPTURED.TXT`; the in-camera engine skips those JPEGs.

## Engine on the camera

- Frame analysis at 1500×1000 took **11.2 s** on the camera; on a ≤600 px subsample (both engines, still
  bit-identical) **1.6 s**. The whole in-camera positive takes ~8 s at 1500×1000.

## RGB matrix (0.4 / 0.7)

- Owner: identity-at-scale-N (probe 4) changed the picture "but not much"; the 0.7 patterns (red only,
  swap R/B, minus blue at 256–4096) "changed a lot". So the matrix is live and strong. Exact semantics
  (absolute vs offset-from-identity, scale) still to pin down, ideally by measuring captured JPEGs.

## Hang after exit (0.7), and probe 2

- 0.7 session: the app logged a normal exit (settings restored and verified, camera released, `onPause`),
  then **the camera hung in Sony's own software**: power switch ignored, battery pull needed, then
  "Recovering data…" for several minutes; it came back normally. No matrix patterns or captures were used in
  that session, but **98 GammaTables were created and released** in two minutes (each slider step made one).
- Probe 2 hung the same way after exiting, having **leaked 12 GammaTables**. Probes 3/4 (a handful of
  tables, each released) exited cleanly.
- Working theory: GammaTable buffers come from a limited device-memory resource that heavy create/release
  churn (or leaks) degrades; Sony's camera software then fails when it takes the pipeline back.
- **0.8 mitigation:** at most 2 GammaTables per live-view session, reused (write the one not on screen,
  then switch); slider changes coalesced to ≤ 1 per 150 ms; exit order: curve off → 150 ms → stop preview →
  release tables → release camera, each step timed in the log.
- **Confirmed in 0.8:** 426 curve updates with never more than 2 tables allocated, ordered exit in 534 ms,
  camera returned to normal shooting cleanly. Second capture worked; MENU was ignored while saving.

## 0.8 session: other findings

- Owner's settings that looked right: levels full range (input 0..1023), **gamma 0.85** (now the default),
  **colour temp 2700 K + WB amber-blue +2 (B+2)** to cancel the blue cast in live view.
- Quality had reverted to **RAW only** again: all three captures were ARW-only, so the positive JPEG with those
  settings was never written. 0.9 switches `storage-fmt` to `rawjpeg` while live view is open (restored on exit).
- Bug: with no new JPEG, the newest *old* JPEG (`LTL00009`, a real negative) was recorded as a positive capture.
  0.9 only accepts a JPEG written after the capture started; `CAPTURED.TXT` was cleaned by hand.

## 0.9 additions (owner requests)

- Slider inset from the screen edges; **double-tap the slider** resets that row (levels to defaults, WB rows and
  matrix back to the camera's own settings).
- **Flip** button cycling 4 modes (window flipped via reverse-landscape and/or preview `setDisplayOrientation(180)`),
  remembered between runs. Sony's `avio.DisplayManager` only reports `DeviceStatus.viewPattern` (no setter).
