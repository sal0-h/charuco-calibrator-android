# Charuco Calibrator

Android research app for **live ChArUco camera calibration** on Samsung Galaxy S23 Ultra (Camera2 `camera_id "0"`). Built with Kotlin, Jetpack Compose, and OpenCV 4.13.0.

## Tools

| Tool | Purpose |
|------|---------|
| **ChArUco Calibrator** | Auto-capture board frames, sub-pixel corner refine, session-scoped calibration, portrait JSON export for downstream pipelines |
| **Multi-Camera Stereo Probe** | Dual physical rear-camera streaming, timestamp sync, stereo calibration, SGBM disparity (research) |
| **ARCore Explorer** | ARCore diagnostics, depth/intrinsics snapshots — not a calibration replacement |

## Board spec

- 7×10 ChArUco, `DICT_5X5_100`
- 25 mm squares, 18 mm markers
- Printed matte board recommended for sub-1 px results

## Build

```bash
./gradlew assembleDebug --no-daemon
./gradlew installDebug --no-daemon   # device connected
```

Requires JDK 21 and Android SDK (API 36). See [AGENTS.md](AGENTS.md) for cloud/emulator caveats.

## ChArUco workflow (device)

1. Open **ChArUco Calibrator** → **New session**
2. **Start auto** — move the board; gates enforce sharpness, pose diversity, and ≥18 corners
3. **Calibrate session** (≥10 accepted frames)
4. Export: `charuco_calibration_result.json` on the **3000×4000 pipeline portrait grid**

Output base path:

`/storage/emulated/0/Android/data/com.example.charucocalibrator/files/`

| File | Description |
|------|-------------|
| `charuco_calibration_result.json` | Calibrated intrinsics (`orientation_convention: pipeline_portrait_ccw90`) |
| `accepted_frames/` | Per-frame JPEG + JSON with stored `charuco_corners_xy` |
| `debug_overlays/` | ID-labeled corner debug images (first 3 frames) |

Promote intrinsics in [qsiurp-metric-depth](https://github.com/sal0-h/qsiurp-metric-depth) with `calib/extract_intrinsics.py`.

## Development

- Package: `com.example.charucocalibrator`
- Board generator (optional): `scripts/generate_charuco_board_for_screen.py`

## License

MIT — see [LICENSE](LICENSE).

## Target device

Samsung Galaxy S23 Ultra — rear logical camera `0`, sensor-native **4000×3000** landscape YUV. Emulator cannot open camera `0`; use a physical device for capture.
