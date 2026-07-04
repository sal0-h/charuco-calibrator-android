# Agent progress

## Stereo probe polish — iteration 1 (stream/probe lifecycle)

- Probe attempts now use one controller, wait for actual `STREAMING`, close camera resources before the next attempt, expose pair/resolution progress, and support cancellation.
- Left/right frames are paired by nearest sensor timestamp before probe evaluation or export; stale previous-frame deltas are no longer mixed into probe medians.
- Manual pair selection no longer requires a probe PASS. Safe shared resolutions are tried in the order `1920x1440`, `1280x960`, `640x480` before larger outputs.
- Duplicate wide cameras are each prioritized with ultrawide/tele candidates, and pair labels include physical IDs.
- Cloud verification: `testDebugUnitTest` passes. Physical S23 Ultra retest pending: run probe, then manually start IDs `2+5` and `2+6` if no PASS appears.

## Stereo probe polish — iteration 2 (guided workflow and capture safety)

- Replaced the undifferentiated control list with six cards: Setup, Select pair, Stream, Capture, Calibrate, and Disparity. Probe progress/cancel, a pinned status banner, readiness explanations, and the live timestamp hero metric are visible in context.
- Probe runtime is capped at 30 seconds and stops at the first working pair; every untested or failed pair remains manually selectable.
- Calibration board captures now record physical camera IDs and only solve/clear the selected pair. ChArUco detection reads the NV21 luma plane directly instead of JPEG-encoding and decoding it first.
- Cloud verification: `./gradlew assembleDebug lintDebug testDebugUnitTest --no-daemon` passes (existing project warnings only).
- S23 Ultra retest pending: (1) run probe and confirm progress, (2) start the selected or manual `2+5` / `2+6` pair and confirm live delta, (3) save stereo + board pairs and calibrate after 10 captures.

## Calibration contract

- Device target: Samsung Galaxy S23 Ultra.
- Camera path: Camera2, `camera_id "0"`.
- Analysis grid: sensor-native `4000x3000` landscape YUV, with documented `1920x1440` fallback.
- Orientation note: `Camera2 sensor-native landscape grid; not display-rotated`.
- Do not combine these frames with old `3000x4000` portrait Pro-mode images.

## Milestone status

| Milestone | Result |
| --- | --- |
| v0.1–v0.3 | Camera2 preview, diagnostics, test-frame save |
| B–I | Sharpness, ChArUco detection, auto-acceptance, calibration JSON, lifecycle cleanup |
| Phase 1+2 | Capture metadata, stability gates, rich calibration report |
| Sub-1 px (#5) | Corner refine, min 18 corners, session isolation, debug overlays, `flags_zero` only |
| ARCore Explorer (#3) | Diagnostic tool on home screen — not a calibration path |
| Multi-Camera Stereo Probe | Dual physical streaming, board-pair stereo calib, SGBM disparity export |

Pipeline verified on device: stored-corner calibration matches expected behavior; no further offline audit tooling in repo.

## Sub-1 px capture

- `CharucoCornerRefiner` after `detectBoard` in `CharucoFrameDetector.kt`.
- `MIN_CHARUCO_CORNERS = 18`; UI shows corner count vs minimum.
- `CaptureSessionManager` tags frames with `capture_session_id` and `capture_frame_index`.
- Calibrate / clear / export overlays operate on current session only.
- Debug overlays: `debug_overlays/debug_<session>_frame<N>_ids.jpg`.
- Solver: `flags_zero` only.

## Build / lint

- `assembleDebug` and `lintDebug` pass on `main`.
- Unit tests: `ArCoreDepthColorizerTest`, `ArCoreOverlayOrientationTest`, `CharucoIntrinsicsAlignerTest`, `stereo/*` (metadata, timestamp delta, probe report, resolution selector).

## Output paths

Base: `/storage/emulated/0/Android/data/com.example.charucocalibrator/files/`

| Artifact | Path |
| --- | --- |
| Accepted frames | `accepted_frames/accepted_<epoch>.jpg` + `.json` |
| Calibration JSON | `charuco_calibration_result.json` |
| Debug ID overlays | `debug_overlays/debug_<session>_frame<N>_ids.jpg` |
| Camera report | `camera_report.json` |
| ARCore snapshots | `arcore_snapshots/` |
| Stereo pairs | `stereo_pairs/stereo_pair_<epoch>/` (`left.jpg`, `right.jpg`, `metadata.json`) |
| Stereo probe report | `stereo_probe_report.json` |
| Stereo calibration pairs | `stereo_calibration_pairs/pair_<n>/` |
| Stereo calibration | `stereo_calibration.json` |
| Disparity debug | `disparity_<epoch>.png` + `disparity_<epoch>.json` |

## S23 Ultra device checklist (Multi-Camera Stereo Probe)

1. Pull branch, `installDebug`, open **Multi-Camera Stereo Probe** from home.
2. Tap **Run pair probe** — expect wide+ultrawide or wide+tele PASS if HAL allows dual stream.
3. **Start streams** on first working pair; confirm live `timestamp_delta_ns` (target &lt; 3 ms on device).
4. **Save stereo pair** — verify `stereo_pairs/stereo_pair_<epoch>/metadata.json` fields.
5. Optional: toggle **Show camera previews** (off by default); confirm streams still save.
6. Save **10+ board pairs** with printed ChArUco board visible in both eyes.
7. **Calibrate stereo** — verify `stereo_calibration.json` (K1/K2, R, T, baseline, RMS).
8. **Compute disparity** on latest saved pair — verify PNG colormap + JSON sidecar.
9. Confirm ChArUco Calibrator and ARCore Explorer unchanged.

## Known limitations

- ChArUco detection uses analysis-frame coordinates; no preview overlay drawn.
- Saved JPEGs are sensor-native landscape (no display-rotation EXIF).
- ARCore image stream (640×480) ≠ ChArUco Camera2 stream (4000×3000).
- Sub-1 px requires printed matte board, even lighting, and curated session frames.
- `accepted_frames/` accumulates across sessions until cleared on device.

## Lifecycle

- `Image` closed via `image.use {}` and `acquireLatestImage()`.
- Camera session/device/surfaces closed in `closeCameraResources()`.
- OpenCV analysis off UI thread; detection `Mat` objects released each frame.
