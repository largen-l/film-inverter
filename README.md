# Film Inverter

In-camera **color negative inversion** for the **Sony a6500**, built as a PlayMemories Camera App (PMCA),
installed with [Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE). Plus a desktop reference engine.

> Status: **early (0.12)**: inverted live view and in-camera positives work; calibration features are new
> and still being tested on the camera. Not ready for everyday use.
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
| `app/FilmInverter/` | The camera app (Java, Android API 10; the a6500 runs Android 4.1) |
| `app/FilmInverter/src/.../engine/NegEngine.java` | LUT-based port of the engine for 8-bit camera JPEGs |
| `tool/` | **Desktop tool** (Windows, macOS, Linux): CLI + GUI for setup, build, install, develop, themes |
| `docs/` | Plan, checkpoints, API findings |
| `toolchain*/`, `scans/`, `build/`, `dist/` | Local only (git-ignored) |

## On the camera (0.12)

The app opens straight into **inverted live view**: the camera's own image processor applies an inverted
tone curve, so live view and captured JPEGs are positives (the RAW stays the untouched negative).

- **Chips / up-down**: Black, White, Gamma, Temp, A–B, G–M. **Slider / left-right / dials**: adjust. Double-tap
  the slider to reset. **Center**: positive ↔ negative. **Shutter / round button**: capture.
- **Menu**: *Calibrate film base* (makes the clear film base neutral with the camera's white balance: the
  orange mask stops turning blue), *Auto levels* (black/white points from the frame, film border excluded),
  *Check alignment* (measures the film edge angle and says which way to rotate), alignment guides,
  2 s capture delay, silent shutter, themes, display flip, *Develop newest frame* (in-camera positive of the
  newest RAW+JPEG negative, auto-cropped), log, reset roll, exit.
- Calibration and levels are kept per roll in `FILMINV/ROLL.TXT`.
- **Themes**: 8 built in; make your own in the desktop tool's **Themes** tab (or `filminverter theme ...`),
  which writes the `.JSN` file to `FILMINV/THEMES/` on the card.
- Every camera setting the app changes is restored when it exits.

## Desktop tool (Windows, macOS, Linux)

One program, `filminverter`, as a window (run it with no arguments) or a command line:

| Command | What it does |
|---|---|
| `filminverter` / `gui` | Window with Camera, Develop, Themes and Developer tabs |
| `doctor` | Checks this system and prints the exact install command for anything missing |
| `install [apk] [--latest]` | Installs the app on the camera (USB Mass Storage mode), retrying through the mode switch |
| `develop <folder> [--prefer raw] [--crop]` | Inverts a folder of scans on the computer (originals untouched) |
| `theme list / export / install` | Camera UI themes (8.3 file names, as the camera requires) |
| `card find / logs` | Finds the camera card; copies the app's log and outputs to the computer |
| `setup`, `build` | Developer: download the toolchain (~1 GB) and build the camera app (needs a source checkout) |
| `decompile <apk>`, `udev` | Developer: inspect a camera app; Linux USB permission rule |

**Get it:** single-file executables are attached to each [GitHub release](../../releases) (built and tested
by CI). Or from source: `pip install -r tool/requirements.txt`, then `python tool/run.py`.

**Per system:**
- **Windows:** run `filminverter-windows-x64.exe`. Nothing else needed.
- **macOS:** `filminverter-macos-arm64` (Apple Silicon). The file isn't signed: the first time, right-click ›
  Open, or run `xattr -d com.apple.quarantine filminverter-macos-arm64`.
- **Linux (Debian/Ubuntu, Fedora, Arch):** `chmod +x filminverter-linux-x64`. For camera installs you need
  libusb and a one-time USB permission rule (`filminverter udev` prints it). `filminverter doctor` names the
  right packages for your distro, e.g. `sudo apt install libusb-1.0-0`, `sudo dnf install libusb1`,
  `sudo pacman -S libusb`.

### What has been tested

| | Windows 10/11 x64 | macOS (Apple Silicon) | macOS (Intel) | Debian/Ubuntu | Fedora | Arch |
|---|---|---|---|---|---|---|
| Engine tests, develop (JPEG + RAW) | ✅ on the owner's PC | ⏳ CI | ⏳ CI (optional) | ⏳ CI | ⏳ CI | ⏳ CI |
| Executable runs (smoke test) | ✅ | ⏳ CI | ⏳ CI (optional) | ⏳ CI | ⏳ CI (container) | ⏳ CI (container) |
| GUI opens | ✅ (screenshots checked) | ⏳ CI | – | ⏳ CI (virtual display) | ❓ | ❓ |
| Toolchain setup + app build | ✅ | ❓ | ❓ | ⏳ CI | ❓ | ❓ |
| Install on the camera | ✅ (pmca-console, Proxmox VM USB passthrough) | ❓ | ❓ | ❓ | ❓ | ❓ |
| Card detection / logs | ✅ code path, ❓ with the new tool | ❓ | ❓ | ❓ | ❓ | ❓ |

✅ tested · ⏳ covered by the GitHub Actions workflow (`.github/workflows/tool.yml`), results below once it
has run · ❓ needs testing on real hardware (camera USB on macOS/Linux is the biggest unknown: Sony-PMCA-RE
runs from source with libusb on Linux, and its macOS binary is Intel-only, so Apple Silicon uses Rosetta).

Desktop engine directly (from source): `python engine/invert_roll.py scans/roll01 --crop` and
`python -m pytest engine/tests`.

## How the inversion works

1. Linearize the scan (sRGB decode for JPEG; linear half-size demosaic for RAW).
2. Divide each channel by the **film base** color (clear unexposed film) → removes the orange mask.
3. Convert to density `D = -log10(v / base)`.
4. Per-channel black/white points in density from the **whole roll** (0.1 / 99.9 percentiles), measured on
   each frame's **picture area** only (film border, sprocket holes and unlit edges found and excluded
   automatically; the film base is taken from the clear rebate) → neutral grays and consistent color.
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
