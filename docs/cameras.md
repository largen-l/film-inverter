# Which cameras does Film Inverter work on?

Film Inverter is built and tested on the **Sony a6500** only. It should run on any Sony body that has
**PlayMemories Camera Apps** (`MENU → Application` on the camera), but it depends on more than other apps do:
the inverted live view and the positive JPEGs come from the camera's own image processor (a custom tone curve
through Sony's `CameraEx` API), and that has only been confirmed on the a6500. **Every other body below is
untested.**

**Tried it on your camera? Please
[report it here](https://github.com/largen-l/film-inverter/issues/new?template=camera_report.yml)**, whether it
worked or not. One report per camera is enough to move a row from ❔ to ✅ or ❌.

## Status

✅ works · ⚠️ works with problems · ❌ does not work · ❔ no report yet

| Camera | Model | Film Inverter | Notes |
|---|---|---|---|
| **a6500** | ILCE-6500 | ✅ | the development camera (firmware 1.06, Android 4.1.2) |
| a6300 | ILCE-6300 | ❔ | |
| a6000 | ILCE-6000 | ❔ | older Android (2.3.7); the tone-curve API may differ |
| a5100 | ILCE-5100 | ❔ | no Fn button |
| a5000 | ILCE-5000 | ❔ | |
| a7 | ILCE-7 | ❔ | |
| a7R | ILCE-7R | ❔ | |
| a7S | ILCE-7S | ❔ | |
| a7 II | ILCE-7M2 | ❔ | |
| a7R II | ILCE-7RM2 | ❔ | |
| a7S II | ILCE-7SM2 | ❔ | |
| NEX-6 | NEX-6 | ❔ | |
| NEX-5R | NEX-5R | ❔ | |
| NEX-5T | NEX-5T | ❔ | |
| RX100 III | DSC-RX100M3 | ❔ | |
| RX100 IV | DSC-RX100M4 | ❔ | |
| RX100 V | DSC-RX100M5 | ❔ | |
| RX10 II | DSC-RX10M2 | ❔ | |
| RX10 III | DSC-RX10M3 | ❔ | |
| RX1R II | DSC-RX1RM2 | ❔ | |
| HX90 / HX90V | DSC-HX90 | ❔ | compact: no RAW, so no sidecars; JPEG positives only |
| HX60 / HX60V | DSC-HX60 | ❔ | compact: no RAW |
| WX500 | DSC-WX500 | ❔ | compact: no RAW |
| a68 | ILCA-68 | ❔ | A-mount; app support unconfirmed |
| a99 II | ILCA-99M2 | ❔ | A-mount |

Where this list comes from: Sony's PlayMemories Camera Apps store (closed in 2025) served these bodies. Which ones
actually install and run third-party apps comes from the owner reports collected by
[Recipe Lab](https://github.com/voxivoid/recipe-lab-sony-pmca#compatibility), another app for the same platform.
There, the **a77 II** (ILCA-77M2) was refused by the installer, and the RX100 II and the original RX10 are missing
from the installer's device table.

**No app support at all** (no `MENU → Application`, so no PMCA app can run): a6100, a6400, a6600, a6700, ZV-E10,
a7 III and later, a7C, a9, a1, RX100 VA/VI/VII, RX10 IV, ZV-1, and every other body from 2017 on.

## What a report needs

- The camera model and firmware version (`MENU → Setup → Version`).
- Did it install (`tool/run.py install` or pmca-console)? Does it open?
- **Inverted live view:** does live view turn into a positive?
- **Capture:** does a shot save, and is the JPEG a positive? Two shots in a row?
- **Calibrate film base:** does it finish ("Base neutral")?
- Does **Exit** return to the camera cleanly (no freeze, no battery pull)?
- The log: `FIPROBE.TXT` in the root of the memory card. Its first lines name the camera, firmware and Android
  version (no serial number). Attach it to the report.
