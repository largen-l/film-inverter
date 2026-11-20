# Negative to Positive

In-camera **colour negative inversion** for the **Sony a6500**, built as a PlayMemories Camera App (PMCA),
installed with [Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE). Plus a desktop reference engine.

> Status: **early / M0 (feasibility probes)**. Not ready for everyday use.
> Unofficial, not affiliated with Sony. Installing custom apps is at your own risk.

## Goals

- Shoot film negatives with the a6500 (copy stand / light source) and get **positives in camera**.
- **The RAW is never touched.** The camera writes its ARW as usual; the app adds a positive JPEG
  (and optionally a Lightroom/ACR XMP sidecar that opens the ARW pre-inverted).
- Output follows the camera's Quality setting: RAW, RAW+JPEG or JPEG, with "keep negative JPEG" on/off.
- **Manual controls** throughout; roll-level calibration (custom WB on the film base, exposure locked
  per roll, black/white points from the whole roll) so frames match like a lab scan.
- Inverted **live view** if the platform allows it (being probed; see `docs/plan.md`).

## Layout

| Path | What |
|---|---|
| `engine/` | Python reference engine (`negengine.py`) + batch CLI (`invert_roll.py`) + tests |
| `app/NegToPos/` | The camera app (Java, Android 2.3 / API 10). Currently the M0 probe build |
| `app/NegToPos/src/.../engine/NegEngine.java` | LUT-based port of the engine for 8-bit camera JPEGs |
| `tools/` | Windows scripts: `setup.ps1`, `build.ps1`, `install.ps1`, `decompile.ps1`, `publish-github.ps1` |
| `docs/` | Plan, checkpoints, API findings |
| `toolchain/`, `scans/`, `build/` | Local only (git-ignored) |

## Quick start (Windows)

```powershell
powershell -ExecutionPolicy Bypass -File tools\setup.ps1     # JDK, Android SDK (API 10), jadx, PMCA-RE, sources
. .\toolchain\env.ps1
tools\build.ps1 -App PMCADemo                                # toolchain sanity check
tools\build.ps1                                              # build\NegToPos\NegToPos.apk
tools\install.ps1 build\NegToPos\NegToPos.apk          # camera in Mass Storage USB mode
```

Desktop engine:

```powershell
.venv\Scripts\python engine\invert_roll.py scans\roll01 --base-frame DSC01234.ARW --base-rect 0.01 0.4 0.03 0.6
.venv\Scripts\python -m pytest engine\tests
```

## How the inversion works

1. Linearise the scan (sRGB decode for JPEG; linear half-size demosaic for RAW).
2. Divide each channel by the **film base** colour (clear unexposed film) → removes the orange mask.
3. Convert to density `D = -log10(v / base)`.
4. Per-channel black/white points in density from the **whole roll** (0.1 / 99.9 percentiles, film-base
   pixels ignored) → neutral greys and consistent colour across frames.
5. Print-style tone curve (contrast, gamma, temp/tint) → output.

For 8-bit camera JPEGs this whole chain is three 256-entry lookup tables, which is why it is cheap
enough for the camera's Cortex-A5 CPUs.

## Authorship

All code, scripts and documentation in this repository were written by **Claude** (Anthropic's AI
model), working in [Claude Code](https://claude.com/claude-code) and directed by the repository owner,
who set the goals, owns the camera and runs the on-camera tests. Commits carry a
`Co-Authored-By: Claude` trailer. The parts adapted from other projects are listed under Credits.

## Credits

`SonyActivity.java` and `ExitCompletedReceiver.java` are adapted from
[PMCADemo](https://github.com/ma1co/PMCADemo) (MIT, © 2015 ma1co).
[Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE) and
[OpenMemories](https://github.com/ma1co/OpenMemories-Framework) by ma1co and contributors;
[Recipe Lab](https://github.com/voxivoid/recipe-lab-sony-pmca) for showing what settings writes look like on these bodies.

## License

MIT (see `LICENSE`). No Sony code is included in this repository; decompiled output stays in the
git-ignored `toolchain/` folder.
