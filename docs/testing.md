# Test reports

What was tested, on what, and what came out. The summary table is in the README ("What has been tested");
this file has the details. Newest first. Every release is published before its hardware testing finishes, and
its section here is updated as results come in.

## v0.28.2: Linux ARM64 (2026-11-24)

**Machine:** Debian 13 (trixie) arm64 VM, 16 cores, 8 GB RAM, Python 3.13.5. The VM's CPU is emulated by
QEMU on an x86 host (no KVM), so it checks correctness, not speed: everything runs several times slower
than on real ARM hardware.

| Test | Result |
|---|---|
| `pip install numpy pillow rawpy pytest` (arm64 wheels exist for all) | ✅ |
| Engine tests (`pytest engine/tests`, 11 tests) | ✅ |
| `doctor` | ✅ names `python3-tk` and the udev rule when missing |
| Smoke test from source (CLI, themes, develop a synthetic negative) | ✅ |
| GUI from source (virtual display) | ✅ |
| `setup` | ✅ after the fix below |
| `build PMCADemo`, `build` (camera app) | ✅ the APK's contents are identical, file for file, to the Windows build (only the signature differs) |
| Executable (`tool/package.py`): smoke test | ✅ |
| Executable GUI | ✅ after the fix below |
| Camera USB install | ❓ not tried (the VM has no camera passed through) |

**Fixed in this release:**
- Google's `aapt`, `zipalign` and `adb` exist only for x86_64 Linux, so on ARM `setup` now uses the
  distribution's native builds (Debian/Ubuntu: `sudo apt install aapt zipalign adb`) and stops with that
  command if they are missing. Everything else in the Android SDK is Java and works as downloaded.
- An executable built on a machine without Tk had no GUI. `tool/package.py` now refuses to build without Tk.

**CI added:** a Linux ARM64 executable and APK build (GitHub's arm64 runners), a Windows ARM64 executable
(GitHub's Windows 11 ARM runners), and every executable now opens its GUI in CI, not only the smoke test.

**Not possible here:** a Windows or macOS VM inside this ARM VM. It has no hardware virtualization (it is
itself emulated), and macOS for Apple chips only runs in VMs on Apple hardware. Windows ARM is covered by the
CI runners above, and macOS on Apple chips by GitHub's macOS runners.
