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
| v0.1 | CameraX rear preview | `0f9d059` |
| v0.2 | Camera2 diagnostics and `camera_report.json` export | `379738d` |
| v0.3 | Camera2 camera `0` preview, YUV stream, and test-frame save | `f1f944d` |
| B | Saved frame validation metadata | `95c81f1` |
| C | OpenCV dependency and sharpness processing | `Add OpenCV frame processing pipeline` |
| D | ChArUco API feasibility check | `Document ChArUco Android API feasibility` |
| E | Live ChArUco detection prototype | `Add live ChArUco detection prototype` |
| F | Automatic frame acceptance | `Add automatic ChArUco frame acceptance` |
| G | Calibration JSON export | pending |
| H | Offline Python fallback script | pending |
| I | Review and cleanup | pending |

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
- The standard Maven AAR includes `org.opencv.objdetect.*` ArUco/ChArUco classes;
  contrib packaging is not needed for this board.

### Offline fallback plan if on-device calibration fails physically

- Accepted frames and metadata remain exportable under `accepted_frames/`.
- `scripts/calibrate_charuco_from_android_frames.py` can re-run calibration on a PC.

## Build / lint status

- Milestone F: `assembleDebug` and `lintDebug` passed.
- OpenCV version: `org.opencv:opencv:4.13.0` from Maven Central.

## Commits and pushes

- `Add OpenCV frame processing pipeline` — pushed to `origin/main`.
- `Add live ChArUco detection prototype` — pushed to `origin/main`.

## Known limitations

- ChArUco detection, auto-acceptance, and calibration are not yet wired in
  Milestone C.
- Saved JPEGs remain in the sensor-native landscape orientation and do not
  contain display-rotation EXIF transforms.

- ChArUco live detection uses analysis-frame coordinates; no preview overlay is drawn.
- Preview scaling/cropping is display-only and does not rotate analysis frames.

## Manual S23 Ultra tests still needed

- Point the printed ChArUco board at camera `0` and confirm live marker/corner counts.
- Confirm rejection reasons when the board is absent or out of focus.
