# Agent progress

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
| Phase 1+2 | Capture metadata, stability gates, solver A/B, rich calibration report |
| Sub-1 px (#5) | Corner refine, min 18 corners, session isolation, debug overlays, `flags_zero` only |
| ARCore Explorer (#3) | Diagnostic tool on home screen — not a calibration path |
| Calibration audit (#4) | `scripts/audit_charuco_from_stored_json.py` + `CALIBRATION_AUDIT.md` |

## Sub-1 px capture (merged #5)

- `CharucoCornerRefiner` after `detectBoard` in `CharucoFrameDetector.kt`.
- `MIN_CHARUCO_CORNERS = 18`; UI shows corner count vs minimum.
- `CaptureSessionManager` tags frames with `capture_session_id` and `capture_frame_index`.
- Calibrate / clear / export overlays operate on current session only.
- Debug overlays: `debug_overlays/debug_<session>_frame<N>_ids.jpg`.
- Solver: `flags_zero` only (rational/fix_k3 A/B removed from calibration path).

**Device audit (session `session_1783163714265`):** 16 frames, RMS **1.8832 px**, Python stored-corner parity exact. See `CALIBRATION_AUDIT.md`.

## Build / lint

- `assembleDebug` and `lintDebug` pass on `main`.
- Unit tests: `ArCoreDepthColorizerTest`, `ArCoreOverlayOrientationTest`, `CharucoIntrinsicsAlignerTest`.

## Output paths

Base: `/storage/emulated/0/Android/data/com.example.charucocalibrator/files/`

| Artifact | Path |
| --- | --- |
| Accepted frames | `accepted_frames/accepted_<epoch>.jpg` + `.json` |
| Calibration JSON | `charuco_calibration_result.json` |
| Debug ID overlays | `debug_overlays/debug_<session>_frame<N>_ids.jpg` |
| Camera report | `camera_report.json` |
| ARCore snapshots | `arcore_snapshots/` |

## Offline audit

```bash
python scripts/audit_charuco_from_stored_json.py \
  --input-dir ./accepted_frames \
  --session-id <from charuco_calibration_result.json> \
  --mode stored --flags 0 --frame-filter all \
  --output-json ./audit.json
```

Always pass `--session-id` when legacy untagged frames remain on disk.

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
