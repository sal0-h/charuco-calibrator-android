# CharucoCalibrator

Native Android (Kotlin + Jetpack Compose) app that captures ChArUco calibration
frames via the Camera2 API. Single Gradle module `:app`. No backend/services.

## Cursor Cloud specific instructions

Environment already provisioned by the startup update script (JDK 21 is
preinstalled; the Android SDK lives at `/opt/android-sdk` and `local.properties`
points at it). Standard build/lint/test/run commands are the normal Gradle
wrapper tasks; the notes below only cover non-obvious caveats.

### Build / lint / test

- Always pass `--no-daemon` to `./gradlew`. This project historically runs in a
  sandbox that blocks the Gradle daemon's local sockets (see `TESTING_NOTES.md`);
  `--no-daemon` avoids intermittent daemon failures.
- Build: `./gradlew assembleDebug --no-daemon` (the first run auto-installs the
  minor platform `android-36.1` via the SDK manager — allow a few minutes).
- Lint: `./gradlew lintDebug --no-daemon`.
- Unit tests: `./gradlew testDebugUnitTest --no-daemon` (only the default
  example test exists today).

### Running the app on the emulator (important caveats)

There is **no KVM / hardware virtualization** in this VM, so the Android emulator
runs under pure software emulation (QEMU TCG, one host core pinned at 100%). It
is usable but slow. Practical tips:

- Launch headless-friendly: `emulator -avd <name> -no-accel -gpu
  swiftshader_indirect -no-snapshot -no-boot-anim -camera-back virtualscene`.
  Cold boot takes ~10 min; the guest's internal load average will look huge
  (that's the guest, not the host — check host load with `top`).
- Expect transient `Process system isn't responding` (ANR) dialogs during/after
  boot. Suppress them with `adb shell settings put global hide_error_dialogs 1`.
- The display frequently fails to composite (screencap returns an all-black
  frame). Keep it on with `adb shell svc power stayon true` and
  `adb shell input keyevent KEYCODE_WAKEUP`; retry `adb exec-out screencap` a
  few times. When screencap is unreliable, read UI state with
  `adb shell uiautomator dump` instead.
- Under heavy emulator load the exec/shell environment can briefly become
  unreachable; wait ~1-2 min and it recovers (the emulator keeps running).

### Camera behavior on the emulator vs. the target device

The app hardcodes `camera_id "0"` (the rear camera on the target Samsung Galaxy
S23 Ultra). **The emulator enumerates its rear camera as id `"10"`, not `"0"`,**
so on the emulator the preview shows `Camera 0 is not exposed by Camera2`, the
YUV stream never configures, and the `Save test frame` button stays disabled.
This is an emulator limitation, not a bug — do not change the hardcoded id.

- Works on the emulator: the `Export camera report` diagnostics feature, which
  writes `camera_report.json` to
  `/storage/emulated/0/Android/data/com.example.charucocalibrator/files/`.
- Requires a physical device (where the rear camera is id `0`): the live preview
  and `Save test frame` capture path.
