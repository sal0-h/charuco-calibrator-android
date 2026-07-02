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
| D | ChArUco API feasibility check | pending |
| E | Live ChArUco detection prototype | pending |
| F | Automatic frame acceptance | pending |
| G | Calibration JSON export | pending |
| H | Offline Python fallback script | pending |
| I | Review and cleanup | pending |

## Build / lint status

- Milestone C: `assembleDebug` and `lintDebug` passed.
- OpenCV version: `org.opencv:opencv:4.13.0` from Maven Central.

## Commits and pushes

- `Add OpenCV frame processing pipeline` — pushed to `origin/main`.

## Known limitations

- ChArUco detection, auto-acceptance, and calibration are not yet wired in
  Milestone C.
- Saved JPEGs remain in the sensor-native landscape orientation and do not
  contain display-rotation EXIF transforms.

## Manual S23 Ultra tests still needed

- Confirm sharpness values change when focusing on the ChArUco board vs a blank wall.
- Confirm processed-frame counter advances at roughly 3–5 FPS while preview runs.
- Confirm existing test-frame save, diagnostics export, and camera report still work.
