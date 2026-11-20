# Probe run 1: a6500, 2026-11-19

The first on-camera run of the M0 probe (installed as "Neg to Pos", `com.negtopos`). The raw log
(`NTPPROBE.TXT`, 3,635 lines) is kept locally in `logs/probe1/` (git-ignored).

## Platform

| | Result |
|---|---|
| Build | `model=ScalarA device=dslr-diadem` |
| Android | **4.1.2, API 16** (not 2.3 / API 10 as assumed). An API 10 APK still installs and runs |
| Heap | **24 MB** (`maxMemory` = `memoryClass` = 24 MB), 4 CPUs |
| Storage | `/storage/sdcard0` = card root |
| Clock | `java.util.Date` reports **1970** (00:10 after boot). Real time has to come from Sony's TimeUtil / OpenMemories `DateTime` |

## Decode speed (6000×4000 JPEG, 5.6 MB)

| Method | Result |
|---|---|
| `inSampleSize=4` → 1500×1000 | 3.95 s |
| `inSampleSize=2`, `1` | **out of memory** (24 MB heap) |
| `BitmapRegionDecoder` 6000×256 strip | 2.75 s → roughly 45 s for a full frame in strips, decode alone |

So full-resolution output in Java is slow. Options for M2: strip processing (slow but workable for
batch), quarter-size previews, Sony's `JpegExporter` / `OptimizedImage` hardware path, or JNI (Recipe
Lab ships native code).

## Sony API on this body

Found: `CameraEx`, `CameraSequence`, `DeviceBuffer`, `avio.DisplayManager`, `ScalarInput`,
`didep.Settings`, `OptimizedImage(+Factory)`, `ImageAnalyzer`, `ScaleImageFilter`,
`provider.AvindexStore`, `DSP`, `JpegExporter`, `meta.Histogram`, `meta.TakenPhotoInfo`,
`ScalarProperties`, `OptimizedImageView`.
Missing: `media.AvindexStore` (it is `provider.AvindexStore`), `graphics.ImageFilter`.

### Inverted live view lead: the API exists

`CameraEx` (method names from the reflection dump):
- `createGammaTable()`, `setExtendedGammaTable(GammaTable)`, `getExtendedGammaTable()`,
  `isExtendedGammaTableSupported()`; params key `KEY_EXTENDED_GAMMA_TABLE_SUPPORTED`
- `setRGBMatrix(int[])`, `getRGBMatrix()`, `isRGBMatrixSupported()`; keys `KEY_RGB_MATRIX`,
  `KEY_RGB_MATRIX_MODE`, `KEY_RGB_MATRIX_SUPPORTED`
- `GammaTable.setPictureEffectGammaForceOff(boolean)`

So the up209d claim ("live-view grading: gamma tables and an RGB matrix") matches real method names
on the a6500. **Not yet shown:** that a decreasing table is accepted, that it affects live view, and
whether it carries into captured images. Next probe: read `is…Supported()` and the current values,
then (behind a separate button) try an inverting table on live view.

`CameraSequence` has a preview plugin hook: `setPreviewPlugin(DSP)`,
`setOneShotPreviewPluginCallback(PreviewPluginCallback)` → `onPreviewPluginNotify(PreviewPluginResult, …)`.
`DSP` exposes `createProcessor`, `setProgram`, `createImage`, `setArg`, `execute` (device type `SONY_DI_DSP`).
This is a second, harder route (custom DSP program on preview).

## Live preview via android.hardware.Camera

`Camera.open()` succeeds, but `getParameters().getPreviewSize()` returns **null** → NPE at the size
printout (the probe bug). Next probe: null-safe, and try the `CameraEx` route the way PMCADemo's
CameraActivity does.

## Keys (keyCode / scanCode)

| Button | keyCode | scanCode |
|---|---|---|
| Center | 23 (DPAD_CENTER) | 232 |
| MENU | 82 (MENU) | 514 |
| Down | 20 (DPAD_DOWN) | 108 |
| Control wheel / dial step (repeats) | 0 | 528 |
| unknown | 0 | 207 |

Android key codes do arrive for center and MENU, so both paths work.

## What went wrong in this run

- The card had **no JPEG from today** (the frame was RAW only: `LTL00001.ARW`), so the probe inverted
  the newest old JPEG (`DCIM/10161009/LTL00017.JPG`). Its "base" came out neutral (0.90/0.90/0.88):
  not a negative.
- The inversion was slow (a 1/4-size decode, then auto base…). The center button was pressed before
  "safe probes done", so the preview probe ran **at the same time**. MENU was then pressed; the app
  exited and `ExitCompletedReceiver` killed the process **before the positive was written**. No
  `DCIM/NTPTEST/*_POS.JPG` exists, so the playback question is still open.

## Fixes for probe 2

1. Ignore center until the safe probes are done; show a clear "WORKING…/DONE" status.
2. MENU while working → ask to press again (don't kill a half-written output).
3. Null-safe preview probe + the CameraEx preview path.
4. Read-only gamma/RGB-matrix probe; the actual inverting-table test is a separate, explicit button.
5. Pick the newest JPEG **that has a matching ARW** (RAW+JPEG pair), or say so clearly.
6. Timestamps from Sony time, not `java.util.Date`.
