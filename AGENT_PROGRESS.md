# Agent progress

## Calibration contract

- Device target: Samsung Galaxy S23 Ultra.
- Camera path: Camera2, `camera_id "0"`.
- Analysis grid: sensor-native `4000x3000` landscape YUV, with documented
  `1920x1440` fallback.
- Orientation note used by exported calibration artifacts:
  `Camera2 sensor-native landscape grid; not display-rotated`.
- Do not combine these frames or future intrinsics with old `3000x4000`
  portrait Pro-mode images.

## Milestone status

| Milestone | Result | Commit |
| --- | --- | --- |
| v0.1 | CameraX rear preview (historical) | `0f9d059` |
| v0.2 | Camera2 diagnostics and `camera_report.json` export | `379738d` |
| v0.3 | Camera2 camera `0` preview, YUV stream, and test-frame save | `f1f944d` |
| B | Saved frame validation metadata | `95c81f1` |
| C | OpenCV dependency and sharpness processing | `Add OpenCV frame processing pipeline` |
| D | ChArUco API feasibility check | `Document ChArUco Android API feasibility` |
| E | Live ChArUco detection prototype | `Add live ChArUco detection prototype` |
| F | Automatic frame acceptance | `Add automatic ChArUco frame acceptance` |
| G | Calibration JSON export | `Add ChArUco calibration JSON export` |
| H | Offline Python fallback script | `Add offline ChArUco calibration audit script` |
| I | Review and cleanup | `Clean up calibration app lifecycle and docs` |

## ChArUco Android API feasibility (Milestone D)

Dependency: `org.opencv:opencv:4.13.0` from Maven Central.

Inspection method: downloaded the published AAR and inspected
`classes.jar`, then added `CharucoApiFeasibility.kt` as a compile-time probe.

### Available in Java/Kotlin bindings

| Capability | Android class / method | Status |
| --- | --- | --- |
| ArUco dictionary access | `Objdetect.getPredefinedDictionary(Objdetect.DICT_5X5_100)` | Available |
| Marker detection | `ArucoDetector.detectMarkers(...)` | Available |
| ChArUco board construction | `CharucoBoard(Size, squareLength, markerLength, dictionary)` | Available |
| ChArUco corner detection / interpolation | `CharucoDetector.detectBoard(...)` | Available |
| `matchImagePoints` equivalent | `Board.matchImagePoints(...)` on `CharucoBoard` | Available |
| Pinhole calibration | `Calib3d.calibrateCamera(...)` | Available |

### Decision

- Java/Kotlin APIs are sufficient for live detection and on-device calibration.
- No custom JNI/C++ bridge is required for the planned board config.
- Android-side ChArUco detection and calibration use the standard Maven AAR.

### Offline fallback plan

- Accepted frames and metadata export under `accepted_frames/`.
- `scripts/calibrate_charuco_from_android_frames.py` can audit or re-run calibration on a PC.

## Build / lint status

- Milestones C–I: `assembleDebug` and `lintDebug` passed.

## Commits and pushes

- `Add OpenCV frame processing pipeline` — pushed to `origin/main`.
- `Document ChArUco Android API feasibility` — pushed to `origin/main`.
- `Add live ChArUco detection prototype` — pushed to `origin/main`.
- `Add automatic ChArUco frame acceptance` — pushed to `origin/main`.
- `Add ChArUco calibration JSON export` — pushed to `origin/main`.
- `Add offline ChArUco calibration audit script` — pushed to `origin/main`.
- `Clean up calibration app lifecycle and docs` — pushed to `origin/main`.

## Known limitations

- ChArUco live detection uses analysis-frame coordinates; no preview overlay is drawn.
- Preview scaling/cropping is display-only and does not rotate analysis frames.
- Saved JPEGs remain in the sensor-native landscape orientation and do not
  contain display-rotation EXIF transforms.
- CameraX is no longer a runtime dependency; only Camera2 is used.
- No broad storage permissions are requested; outputs use app-specific external files.

## Lifecycle review (Milestone I)

- `Image` objects are always closed via `image.use {}` and `acquireLatestImage()`.
- `ImageReader` uses `acquireLatestImage()` to drop stale frames under load.
- Camera session/device/surfaces are closed in `closeCameraResources()`.
- Background `HandlerThread`s and executors are stopped in `release()`.
- OpenCV frame analysis runs off the UI thread.
- Detection correspondence `Mat` objects are released after each processed frame.

## Manual S23 Ultra tests still needed

- Full end-to-end: auto-capture 20+ frames, run calibration, inspect JSON.
- Verify intrinsics are plausible for `4000x3000` sensor-native frames.
- Confirm offline Python script reproduces similar results from exported frames.
- Confirm calibration JSON `orientation_note` matches saved frame metadata.

## Output paths

Base directory:
`/storage/emulated/0/Android/data/com.example.charucocalibrator/files/`

- Test frames: `test_frame_<epoch>.jpg` + `.json`
- Accepted frames: `accepted_frames/accepted_<epoch>.jpg` + `.json`
- Camera report: `camera_report.json`
- Calibration JSON: `charuco_calibration_result.json`
- Offline script: `scripts/calibrate_charuco_from_android_frames.py`
