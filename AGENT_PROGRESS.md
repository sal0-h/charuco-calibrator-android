# Agent progress

Internal milestone log for cloud agents. User-facing overview: [README.md](README.md).

## Calibration contract

- Device: Samsung Galaxy S23 Ultra, Camera2 `camera_id "0"`
- Capture: sensor-native **4000×3000** landscape YUV (`1920×1440` fallback)
- Export: **3000×4000** portrait K (`orientation_convention: pipeline_portrait_ccw90`)
- Board: 7×10 ChArUco, 25 mm / 18 mm, `DICT_5X5_100`
- Solver: `flags_zero` only; manual ID map correspondence (do not change)

## Milestones

| Area | Status |
|------|--------|
| Camera2 + ChArUco live calib | Shipped — sub-1 px achieved on device (~0.97 px RMS) |
| Session isolation + portrait JSON | Shipped |
| Pose similarity (all-session dedup) | Shipped |
| ARCore Explorer | Shipped — diagnostics only |
| Multi-Camera Stereo Probe | Shipped — dual stream, board stereo calib, disparity, support bundle export |

## Acceptance gates (auto-capture)

| Gate | Value |
|------|-------|
| Min corners | 18 |
| Sharpness (Laplacian) | > 100 |
| Min bbox area ratio | 0.10 |
| ISO / exposure | ≤ 1000 / ≤ 33 ms |
| Pose dedup | `PoseSimilarity` vs all session accepts; 3×3 pose grid |
| Min frames to calibrate | 10 |

## Output paths

Base: `/storage/emulated/0/Android/data/com.example.charucocalibrator/files/`

- `charuco_calibration_result.json` — portrait intrinsics for pipeline
- `accepted_frames/` — JPEG + JSON per accepted frame
- `debug_overlays/` — ID overlay JPEGs
- `stereo_pairs/`, `stereo_calibration.json`, `stereo_probe_report.json`
- `stereo_diagnostics/stereo_session_latest.jsonl`, `stereo_support_bundle_latest.zip`
- `arcore_snapshots/` — ARCore export artifacts

## Build

```bash
./gradlew assembleDebug lintDebug testDebugUnitTest --no-daemon
```
