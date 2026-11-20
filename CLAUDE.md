# CLAUDE.md: handoff for Claude Code

You are continuing development of **Film Inverter**: an in-camera colour-negative inverter for the
owner's **Sony a6500**, built as a PMCA Android app. Read `docs/plan.md` first, then this file.

## Where things stand (2026-11-19)

Done:
- `engine/negengine.py` + `invert_roll.py`: Python reference engine. 6 synthetic tests pass
  (`engine/tests/test_negengine.py`): grey stays neutral, tones monotonic, auto-base, roll-level
  consistency, profile round-trip. Fixed: film rebate in frame used to drag the black point; level
  estimation now ignores near-base pixels.
- `app/FilmInverter/.../engine/NegEngine.java`: LUT port for 8-bit JPEGs. Verified against the Python
  engine on a synthetic roll: mean abs diff ≈0.65/255 (JPEG rounding), identical levels.
  Desktop check: `test/NegEngineDesktopTest.java` (see header for commands).
- `app/FilmInverter/src/com/filminverter/MainActivity.java`: **M0 probe app**. Writes `FIPROBE.TXT`
  to the card root. **Probe run 1 done on the camera (2026-11-19)**, findings in
  `docs/api-usage/probe1.md`: a6500 = Android 4.1.2 / API 16, 24 MB heap; CameraEx has gamma-table and
  RGB-matrix setters (inverted live view lead is real at the API level); the preview probe NPE'd; no
  positive was written (RAW-only frame, then exited mid-inversion). Probe 2 fixes are listed there.
- `tools/*.ps1`: setup / build (aapt → javac → d8 → zipalign → apksigner v1, no Gradle) / install /
  decompile / publish-github. setup/build/first-run/publish/install **work on Windows** (2026-11-19):
  PMCADemo and FilmInverter both build; FilmInverter.apk verifies v1-signed for API 10. decompile untested.
  Build notes: OpenMemories stubs are Jasmin `.j` (assembled with jasmin 2.4); the framework compiles
  against android-16; d8/apksigner are run as jars (their .bat wrappers need the legacy SDK `tools/`).
  git/gh are portable copies in `toolchain\` (on PATH via env.ps1).
- Repo: https://github.com/largen-l/film-inverter, **private** until the first on-camera test works.
- PMCADemo comparison done (step 3): `SonyActivity` (AppInfoReceive broadcast, colour depth, Sony scan
  codes) + `ExitCompletedReceiver`, adapted from PMCADemo (MIT). No uses-library entries needed.

- **Probe 2** (0.2-probe, `com.filminverter`) installed on the camera 2026-11-19: busy/ready status,
  RAW+JPEG pair only, Sony time, CameraEx live view tests (preview frames, gamma table read → saved to
  `FIGAMMA.BIN`, R/B matrix swap, two inverted gamma variants, all restored), touch test (on-screen
  buttons + every touch logged). Results in `docs/api-usage/probe2.md`: the tone curve applies to live
  view instantly; table format unknown (2048 bytes, uninitialised by default); exit froze the camera.
- **Probe 3** (0.3-probe) installed 2026-11-19: interactive curve-format explorer (13 layouts,
  plain/inverted), tables released, positives to `/FILMINV/`, exit steps logged. Results in
  `docs/api-usage/probe3.md`: **gamma table = 1×1024 u16 LE, 10-bit output; inverted live view works**
  (blue cast: orange mask not yet compensated). Touch works. Exit clean. Long filenames fail (8.3 only?).
- **Probe 4** (`docs/api-usage/probe4.md`): 8.3 filenames confirmed; yellow speckles were the owner's focus
  peaking; **captured JPEGs get the curve too (full-res in-camera positives)**; first in-camera positive.
- **Engine (M1 start, 2026-11-19):** `analyse_frame` / `NegEngine.analyseFrame` find the picture area and the
  film base from the rebate (sprocket holes excluded); levels come from the picture area only. RAW loads
  with camera WB before the matrix. Fully automatic results on the cat frame match the owner's Lightroom
  edit. Java == Python bit-identical on `scans/parity01` (`NegEngineDesktopTest <dir> auto <py out>`).
  Installed as 0.5-probe.
- **Probes 0.6–0.11** (`docs/api-usage/probe5-8.md`): capture works repeatedly (cancelTakePicture), RAW+JPEG forced
  in live view, levels/gamma rows in the curve (owner: full range, gamma 0.85), WB 2700 K + B+2 looked right
  live but is not yet captured, matrix is live and strong (patterns 1–8), **two reused GammaTables per session**
  (many tables hung the camera after exit), Flip on/off via reverse-landscape. 0.11 (unflip on exit: the flip
  leaked into Sony menus) is built but **not yet installed**.
- Repo is **public** since 2026-11-20 (history scanned: no personal data or secrets). Keep it that way.
- UI: the owner is choosing between 8 concept mockups (refined minimal, camera-native menus, Material, glass,
  enlarger head, light table, scope HUD, safelight) on a private design canvas. All must stay plain Android 4.1
  views over the hardware live view: no live blur, no per-frame redraw, histograms from captures only.
- **Next:** automatic orange-mask removal (measure the base, set WB/matrix), then the chosen UI.
- **Linux**: `tools/*.sh` twins of the PowerShell scripts (toolchain-linux/, .venv-linux/, PMCA-RE run
  from source). **Not yet run on Linux.** WSL2 can't start on this PC (Proxmox VM without nested
  virtualisation); the owner can provide SSH to a Linux VM/LXC for testing.
- Owner wants the app **touch-friendly wherever possible** (at least sliders etc.). Probe 2 tells us
  whether the a6500 passes touches to apps; design UI for both buttons/dials and touch.

## Environment

- Windows PC, project at `A:\auto-film-scan` (local folder keeps the old name; the project is now
  **Film Inverter**, repo `film-inverter`, camera menu "Film Inverter", package `com.filminverter`). Run `. .\toolchain\env.ps1` in each new shell.
- The first probe was installed as the earlier name "Neg to Pos" (`com.negtopos`, log `NTPPROBE.TXT`,
  `DCIM/NTPTEST`). After installing Film Inverter, the owner can delete it via the camera's
  Application Management menu.
- Proxmox VM: the camera must be passed through **by USB port**; it changes product ID when
  pmca-console switches it to app-install mode (054C:0AC4 mass storage → 054C:06A8 MTP/install).
- Camera connects over USB in Mass Storage mode for `pmca-console`. Once the owner enables adb via
  OpenMemories: Tweak, `tools\install.ps1 -Adb <apk>` and `adb logcat` are faster.
- The owner installed many apps from https://archive.org/details/sony-playmemories-camera-apps.
  Sony's **Multiple Exposure** APK (and others) can be decompiled locally with `tools\decompile.ps1`.

## Next steps (in order)

Steps 1–4 are done; continue at step 5.

1. `tools\setup.ps1`, fix anything that breaks (URLs, paths, archive layouts).
2. `tools\build.ps1 -App PMCADemo` and fix build.ps1 until it produces an APK. This validates the
   toolchain against a known-good PMCA app.
3. **Compare PMCADemo's manifest and base Activity with ours.** Sony apps may need a
   broadcast on start/exit (app-info / "DAConnectionManager"-style intents) or `uses-library` entries.
   Copy what PMCADemo/OpenMemories-Tweak do into `app/FilmInverter` (credit them; check licences).
4. `tools\build.ps1`, then fix compile errors in MainActivity (it was written blind against API 10).
5. **CHECKPOINT (owner):** install the probe, shoot one RAW+JPEG frame of a negative with base visible,
   launch the app, press the centre button for the preview probe, then send back `FIPROBE.TXT` and
   `DCIM/FITEST/*_POS.JPG`, and say whether the positive appears in the camera's playback.
6. **CHECKPOINT (owner):** point to the Multiple Exposure APK (and any "live view grading" style app)
   from the archive; run `tools\decompile.ps1`. Summaries land in `docs/api-usage/`. Look for how
   live view is blended or graded (gamma tables / matrix / blend modes). The lead to verify:
   up209d/open-memories-app-ai claims "live-view-grading: gamma tables and an RGB matrix" and
   "double-exposure: seven blend modes with live-view overlay". That repo is AI-generated and has at
   least one factual error (it claims a6500 "firmware 2.17+"; Sony's last a6500 firmware is 1.06).
   **Verify everything from it against decompiled Sony code and on-camera probes.**
7. **CHECKPOINT (owner):** real test roll(s) in `scans/rollNN-<stock>/` (RAW+JPEG, base visible) →
   tune `negengine.py` on real data (M1).
8. Then M2 (Mode A Develop) per `docs/plan.md`.

## Rules

- **Never modify or delete ARW/JPG originals on the card.** Outputs are new files only.
- Never commit Sony code or decompiled output. It stays in `toolchain/` (git-ignored). Only
  summaries/notes in `docs/api-usage/`.
- Java source level 8 (d8 desugars for API 10); no AndroidX, no lambdas needed for clarity; keep
  `NegEngine.java` free of Android imports so it stays desktop-testable.
- Keep `negengine.py` and `NegEngine.java` behaviourally identical; re-run the desktop comparison
  after changing either.
- Settings writes to the camera: read back and verify; IDs can differ between bodies (Recipe Lab).
- Ask the owner before anything that touches the updater shell, service mode, or camera settings
  backup.

## Owner checkpoints (summary)

| When | Owner does |
|---|---|
| First run | `tools\publish-github.ps1` (GitHub login in browser) |
| After build works | Install probe APK, run it, return FIPROBE.TXT + POS JPEG + playback answer |
| Live view research | Provide Multiple Exposure APK path |
| Engine tuning | Shoot 1–3 test rolls RAW+JPEG with base visible |
| Every new APK | Install + quick test on camera |
