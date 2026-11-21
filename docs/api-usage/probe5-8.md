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
  **color temp 2700 K + WB amber-blue +2 (B+2)** to cancel the blue cast in live view.
- Quality had reverted to **RAW only** again: all three captures were ARW-only, so the positive JPEG with those
  settings was never written. 0.9 switches `storage-fmt` to `rawjpeg` while live view is open (restored on exit).
- Bug: with no new JPEG, the newest *old* JPEG (`LTL00009`, a real negative) was recorded as a positive capture.
  0.9 only accepts a JPEG written after the capture started; `CAPTURED.TXT` was cleaned by hand.

## 0.9 results → 0.10

- **Flip:** modes 2 and 3 (window rotated via reverse landscape) turn everything upside down, live view included;
  modes 1 and 4 look identical, so the camera ignores `Camera.setDisplayOrientation(180)`. 0.10: Flip on/off =
  window rotation only.
- Double-tap reset works. RAW+JPEG forcing works (`storage-fmt` = `rawjpeg` at capture, restored on exit).
- In-app captures `LTL00013` (gamma 0.85) and `LTL00015` (gamma 1.15) are full-res positives with a cyan-blue
  cast; neither had the warm WB on (camera's custom WB at capture), so the 2700 K test is still open.
- Capture→JPEG matching was still wrong (named the previous file): the app clock is 1970 while file times are
  real, and the JPEG can take > 3 s to appear. 0.10 compares against the newest JPEG *before* the shot and polls
  up to 10 s, using only file times.

## 0.13 first app session (2026-11-20) → 0.14

- Calibration's first measurement shot **was taken** (`DCIM/11261120/LTL00001.JPG`, 3008×2000, Q25, identity
  curve: a clean shot of the orange base, so the JPEG-only/small-size settings work), but the app never saw it:
  it landed in a **new day folder** created mid-session and appeared later than the 10 s wait.
- After that **the camera saved nothing**: four more measurement shots and two normal captures left no ARW or
  JPEG. Likely cause: the app restarted preview (and fired the next `takePicture`) while the first shot was
  still being saved, and `takePicture`/`cancelTakePicture` were called from a worker thread.
- 0.14: one `shoot()` routine for captures and measurements: camera calls on the UI thread, shutter and JPEG
  callbacks logged with times, new files found by name across all DCIM folders (no file times), up to 30 s,
  preview restarted only after the file stops growing; measurements decode the callback bytes when available.
- Menu taller than the screen: now in a ScrollView that follows the cursor.

## 0.14 session → 0.15

- Measurement shot: shutter callback at +109 ms, no JPEG callback, no new file seen in 30 s, yet
  `LTL00002.JPG` (3008×2000) is on the card. A normal capture (shutter at +319 ms) and the next measurement
  (+326 ms) saved **nothing**. In those two the 300 ms release came *before* the shutter fired.
- The ScrollView around the menu took key focus: the d-pad scrolled it instead of moving the cursor.
- 0.15: no picture-size / jpeg-quality changes in live view (measurement = `storage-fmt` jpeg only, decoded at
  ~750 px); release the shutter 500 ms after Sony's `ShutterListener` fires (2.5 s fallback, as Recipe Lab
  holds it); `StoreImageCompleteListener` and `OnCaptureStatusListener` logged, and the stored file name used
  when Android's listing doesn't show it; log lines carry seconds since start; scroll views never take focus.

## 0.15/0.16 session → 0.17: the cause

- Measurement shot (JPEG only): takePicture +12 ms, Sony `capture start` and `shutter` at +128 ms, released at
  +637 ms; `LTL00003.JPG` is on the card, but Android never saw it in 30 s and `StoreImageCompleteListener` never
  fired. Every later capture (RAW+JPEG): no shutter until the 2.5 s release, then `capture start`/`shutter`/
  `capture end` within 3 ms, and **no file at all**.
- What changed since probe 0.10 (the last version that saved every shot): 0.10 restarted live view at a fixed
  3 s after takePicture and looked for the file independently. **0.12–0.16 waited for the file before
  restarting live view.** The camera apparently completes the save only once live view is back, so the app waited
  for a file that never came, and the camera saved nothing after the first shot of the session.
- 0.17: `shoot()` is 0.10's sequence again (takePicture, release at 300 ms, startPreview at 3 s, then look for the
  file by name for up to 15 s); the Sony capture listeners are gone (not used by 0.10). Measurement still switches
  `storage-fmt` to `jpeg` around its shot: the remaining unknown if 0.17 still fails.

## 0.17 session → 0.18

- Still: the first shot of each session is on the card (`LTL00004`, `LTL00005`), Android never lists it, and nothing
  after it is saved. Every broken session since 0.13 started with a measurement shot that switched `storage-fmt` to
  `jpeg`; probe 0.10 (all shots saved) never switched. 0.18 changes no camera setting for measurement shots (they are
  RAW+JPEG like any capture) and logs what Android lists in the newest DCIM folder at start and on every timeout.
- Display: the a6500 panel is 16:9 but the framebuffer is 640x480, so pixels show 4/3 wide (circles looked squashed).
  The 3:2 live view spans the full height and x ~50..590. The 0.16 layout windows (SurfaceView resized) cropped the
  picture: the camera does not scale its live view to the surface. 0.18: live view always full screen, themes only
  decorate the side bars, panels hide after 5 s idle (Menu > Controls), round shapes and text corrected by 0.75.

## 0.18 session → 0.19: one shot per session, since probe 8

- 0.18: every measurement and capture failed, exit hung (battery pull, "Recovering data"), and the whole 0.18 log was
  lost: what Android wrote never reached the card. No new photos.
- Re-reading the probe logs: since the release fix (probe 8) **no live-view session ever saved more than its first
  shot** (probe 8: 2nd and 3rd capture produced nothing; probes 9/10 took one capture per session). "Captures worked"
  was one per session.
- Recipe Lab (same platform) saw the same "no more shots until the app restarts" when it touched the card with the
  camera open, opens/closes its log per line, and takes many frames in a row with: takePicture, hold 2.5 s,
  cancelTakePicture + startPreview, next frame. Our app kept FIPROBE.TXT open for the whole session.
- 0.19: the log is buffered and appended (open, write, close) about once a second, never during a shot; shots use
  Recipe Lab's sequence; Menu > Circle shape (75-100 %, default 100: the 0.75 of 0.18 looked worse).

## 0.20: reopen the camera after every shot

- Owner: captures "worked before the GUI". Probe 4's log shows how: the owner left and re-entered live view (camera
  released and opened again) before every capture, and each re-entered session saved its one shot (LTL00005, 06, 08).
  The probe UI had that button; 0.12+ never leaves live view, so only the first shot of a session was saved.
- 0.20: `shoot()` holds the shutter 2.5 s, releases it, closes the camera (`restoreCamera`) and opens live view again
  (`startLive`), then finds the file. Cost: ~2 GammaTables per shot (watch for the old many-tables hang on exit).

## 0.20 session → 0.21: every shot saved

- **Fixed:** all 16 shots of the session saved (3 captures, 12 measurement shots, 1 capture), each ~5.7 s from
  takePicture to file, live view back after ~4.8 s. Exit was clean (no hang) after ~30 reopens.
- Calibration 1 (from 2800 K) converged in one step: 2800 K, G–M −1, base codes 180/182/181. Its measured slopes
  (d ln(R/G), d ln(B/G) per mired: −0.0031 / 0.0031; per G–M step: 0.0648 / 0.0503) are now built in.
- Calibration 2 (after Reset roll, so from the camera's 5500 K) learned its slopes with red near clipping and swung
  2500 K → 7600 K → 2600 K/G6, ending "super pink" (base codes 137/188/171). Auto levels can't fix a color cast.
- 0.21: calibration starts at the last good result (else 2800 K), uses the built-in slopes (Broyden-updated), limits
  each step to 40 mired / 6 G–M, rejects clipped or worse shots and ends on the best: usually 2 shots, at most 4.
  Measurement shots are small JPEG-only again (with a fallback to the camera's format if one fails to save), listed in
  `FILMINV/MEASURE.TXT`. Positive captures are listed in `FILMINV/POSITIVE.TXT` for the new **Review captures** screen.
- Circle shape: owner picked **80 %** (now the default).

## 0.9 additions (owner requests)

- Slider inset from the screen edges; **double-tap the slider** resets that row (levels to defaults, WB rows and
  matrix back to the camera's own settings).
- **Flip** button cycling 4 modes (window flipped via reverse-landscape and/or preview `setDisplayOrientation(180)`),
  remembered between runs. Sony's `avio.DisplayManager` only reports `DeviceStatus.viewPattern` (no setter).
