# CharucoCalibrator progress

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
| B | Implementation complete; debug APK verified | `Add saved frame validation metadata` (this change) |

## Verification status

- v0.3: `assembleDebug` and `lintDebug` passed before commit.
- Milestone B: both changed Kotlin files pass direct Kotlin 2.2.10/Compose
  compiler checks. A new debug APK was produced containing the Milestone B UI,
  validation, and orientation-metadata strings, so `assembleDebug` passed.
  `lintDebug` was not rerun because this sandbox blocks Gradle's local daemon
  sockets; the previous v0.3 lint run passed.
- Physical validation already completed for a camera `0`, `4000x3000`,
  6.3 mm saved JPEG; it was non-black, correctly converted, and not ultrawide.

## Known limitations

- ChArUco and OpenCV are intentionally not present before Milestones C-E.
- Saved JPEGs remain in the sensor-native landscape orientation and do not
  contain display-rotation EXIF transforms.
- On-device calibration is not implemented yet.

## Manual S23 Ultra tests still needed

- Confirm the Milestone B UI shows save time, `4000x3000`, and both output paths.
- Inspect the new metadata fields and exact orientation note.
- Confirm the JPEG and metadata files are non-empty after repeated saves.
