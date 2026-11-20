# Plan

Condensed from the original planning doc (2026-11-19).

## Platform facts

- a6500 = **CXD90014** (BIONZ X), 4× Cortex-A5. Not one of the signed-firmware chips (CXD90045/57), so
  Sony-PMCA-RE's updater shell should work (code runs in update mode; firmware untouched). There is
  **no CFW** for the a6500, and none is needed.
- Custom apps: Android app targeting **API 10 (Android 2.3.x)** per Sony-PMCA-RE. Installed via
  `pmca-console install -f app.apk`. Owner has confirmed app installs work on this body.
- Sony APIs via OpenMemories-Framework (wrappers) + stubs (compile-only).
- Native code is viable (Recipe Lab ships JNI). For API 10 the last suitable NDK is r16b.
- Recipe Lab notes: app-written camera settings work on a6500; Picture Effects are dropped with RAW;
  its quality control offers RAW+JPG and JPEG but not RAW-only from inside an app.

## Architecture

- **Mode A: Develop** (first): shoot natively (all Sony manual controls, any Quality), open app,
  pick base, batch-invert new frames → `*_POS.JPG` (+ XMP sidecar later).
- **Mode B: Scan**: in-app capture (CameraEx), manual controls, roll lock, instant inverted review,
  inverted live view if possible.
- Shared: inversion engine, roll profile (JSON on card), output writer.

## Outputs (follow the camera's Quality setting)

| Quality | Result | Engine |
|---|---|---|
| RAW | ARW + positive JPEG + optional XMP | needs RAW engine (v2); until then ARW + XMP |
| RAW+JPEG | ARW + positive JPEG; negative JPEG kept or replaced | v1 (JPEG LUT) |
| JPEG | positive JPEG; negative kept or replaced | v1 |

The ARW is always the negative (sensor data). XMP sidecar makes it open inverted in LR/ACR.

## Calibration

- **White balance:** per roll, semi-auto. Point at clear base → app sets custom WB from it (cancels the
  orange mask in-camera), sets exposure so base sits just under clipping, saves base RGB. Locked for
  the roll; manual override (re-pick base, temp/tint).
- **Levels:** auto per roll (default; percentiles over all frames, base pixels excluded), auto per
  frame, or manual (black/white point, contrast, per channel). Presets per film stock.

## Inverted live view: leads, in order

1. **Live-view grading via gamma tables** (claimed by up209d/open-memories-app-ai, unverified). A
   decreasing gamma table would invert live view in the hardware pipeline. Verify by decompiling the
   Sony apps it was derived from.
2. **Multiple/Double Exposure blend modes**: if a subtract/difference mode exists, blend a solid
   white (or base-coloured) layer.
3. **Preview frames to the app** (`Camera.setPreviewCallback`): software inversion overlay. The M0 probe tests this.
4. Fallback: normal live view + magnify/peaking + inverted review ~1–2 s after each shot.

## Milestones

| # | Milestone | Done when |
|---|---|---|
| M0 | Spikes | probe app run on camera; AFSPROBE.TXT answers: heap, decode speed, Sony classes, preview frames, playback indexing; Multiple Exposure decompiled |
| M1 | Desktop engine | good positives on 2–3 C-41 stocks from real a6500 scans |
| M2 | Mode A Develop | card scan, base picker, batch invert, roll profile, full-res strip processing |
| M3 | Mode B Scan | capture, manual controls, roll lock, instant review, frame counter |
| M4 | Inverted live view | if a lead pans out |
| M5 | RAW engine + XMP | JNI ARW decode, half-size linear inversion, sidecars |
| M6 | Release | presets, B&W/slide, docs, testers on other PMCA bodies |

## Risks

Bricking risk is low for apps (Android layer, uninstallable); avoid blind writes to settings backup
(IDs differ by body). Android 2.3 heap is small → tile/strip processing. JPEG path has a quality
ceiling (8-bit, tone-mapped) → RAW engine later.
