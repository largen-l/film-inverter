# CLAUDE.md: handoff for Claude Code

You are continuing development of **Film Inverter**: an in-camera colour-negative inverter for the
owner's **Sony a6500**, built as a PMCA Android app. Read `docs/plan.md` first, then this file.

## Where things stand (2026-11-19)

Done (written and tested in a cloud sandbox, NOT yet run on Windows or the camera):
- `engine/negengine.py` + `invert_roll.py`: Python reference engine. 6 synthetic tests pass
  (`engine/tests/test_negengine.py`): grey stays neutral, tones monotonic, auto-base, roll-level
  consistency, profile round-trip. Fixed: film rebate in frame used to drag the black point; level
  estimation now ignores near-base pixels.
- `app/FilmInverter/.../engine/NegEngine.java`: LUT port for 8-bit JPEGs. Verified against the Python
  engine on a synthetic roll: mean abs diff ≈0.65/255 (JPEG rounding), identical levels.
  Desktop check: `test/NegEngineDesktopTest.java` (see header for commands).
- `app/FilmInverter/src/com/filminverter/MainActivity.java`: **M0 probe app** (never compiled against
  android.jar yet; the sandbox had no Android SDK). Writes `FIPROBE.TXT` to the card root.
- `tools/*.ps1`: setup / build (aapt → javac → d8 → zipalign → apksigner v1, no Gradle) / install /
  decompile / publish-github. setup/build/first-run/publish **work on Windows** (2026-11-19): PMCADemo
  and FilmInverter both build; FilmInverter.apk verifies v1-signed for API 10. install/decompile untested.
  Build notes: OpenMemories stubs are Jasmin `.j` (assembled with jasmin 2.4); the framework compiles
  against android-16; d8/apksigner are run as jars (their .bat wrappers need the legacy SDK `tools/`).
  git/gh are portable copies in `toolchain\` (on PATH via env.ps1).
- Repo: https://github.com/largen-l/film-inverter, **private** until the first on-camera test works.
- PMCADemo comparison done (step 3): `SonyActivity` (AppInfoReceive broadcast, colour depth, Sony scan
  codes) + `ExitCompletedReceiver`, adapted from PMCADemo (MIT). No uses-library entries needed.

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
