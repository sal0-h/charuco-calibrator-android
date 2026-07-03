# Physical testing notes

## Output locations

All Android outputs use the app-specific external files directory, normally:

`/storage/emulated/0/Android/data/com.example.charucocalibrator/files/`

- Camera report: `camera_report.json`
- Test frames: `test_frame_<epoch_millis>.jpg` and `.json`
- Accepted frames: `accepted_frames/accepted_<epoch_millis>.jpg` and `.json`
- Camera report: `camera_report.json`
- Calibration JSON: `charuco_calibration_result.json`

No broad storage permission is required.

## Milestone B: saved-frame audit on the S23 Ultra

1. Install and launch the debug app.
2. Grant camera permission.
3. Confirm the UI reports `camera_id: 0`.
4. Confirm the selected analysis stream is `4000x3000`, or record the visible
   fallback resolution.
5. Tap **Save test frame** once.
6. Confirm the saved-frame area shows a UTC save time, image dimensions, image
   path, and metadata path.
7. Open the JPEG and verify that it is non-empty, normally exposed, not black,
   not color-corrupted, and uses the main 6.3 mm camera view.
8. Open the adjacent JSON and verify these keys:
   `camera_id`, `image_width`, `image_height`, `timestamp`,
   `sensor_timestamp_ns`, `focal_length_mm`, `sensor_exposure_time_ns`,
   `iso_sensitivity`, `sensor_orientation`, `display_rotation`, and
   `orientation_note`.
9. Confirm `orientation_note` is exactly
   `Camera2 sensor-native landscape grid; not display-rotated`.
10. Repeat several saves and confirm the preview, frame counter, diagnostics
    export, and camera report continue to work.

## Milestone C: OpenCV sharpness processing on the S23 Ultra

1. Install and launch the debug app.
2. Grant camera permission and confirm `camera_id: 0`.
3. Confirm analysis resolution is `4000x3000` or note the fallback.
4. Point the camera at a textured surface and watch `sharpness` update.
5. Confirm `raw frames` increases continuously and `processed frames` increases
   at roughly 3–5 FPS.
6. Confirm `processing FPS` is near 3–5 while the preview is running.
7. Tap **Save test frame** and confirm JPEG + metadata export still works.
8. Export the camera report and confirm diagnostics still work.

## Milestone E: live ChArUco detection on the S23 Ultra

1. Print the 7x10 ChArUco board (`DICT_5X5_100`, 25 mm squares, 18 mm markers).
2. Launch the app and point camera `0` at the board under good lighting.
3. Confirm `markers` and `charuco corners` increase when the board is visible.
4. Confirm `detection` becomes `detected` with at least 8 ChArUco corners.
5. When the board is absent, confirm a rejection reason appears.
6. Confirm sharpness, test-frame save, and diagnostics export still work.

## Milestone F: automatic frame acceptance on the S23 Ultra

1. Print and mount the ChArUco board where camera `0` can see it.
2. Tap **Start auto capture**.
3. Move the phone to varied poses with the board in frame and in focus.
4. Confirm `accepted` count increases and stops at 30.
5. Confirm `last decision` alternates between accepted and rejected reasons.
6. Inspect `.../files/accepted_frames/` for JPEG + JSON pairs.
7. Verify metadata includes bbox fields, sharpness, counts, `orientation_note`, and
   capture metadata: `sensor_exposure_time_ns`, `iso_sensitivity`, `focal_length_mm`,
   `lens_focus_distance`, `af_state`, `ae_state`, `awb_state`, and state name fields.
8. Tap **Stop auto capture**, then **Clear accepted frames**, and confirm the counter resets.

## Phase 1 + 2: capture stability, quality gates, and rich calibration report

### Live capture metadata (UI)

1. Launch the app with the camera pointed at the board.
2. Confirm the live stats panel shows ISO, exposure (ms), focus distance, and
   AF/AE/AWB state names.
3. During auto capture, confirm `capture stability` transitions from
   `WARMING_UP` to `STABLE` after ~2.5 s.
4. If focus drifts or ISO/exposure exceed limits, confirm rejection reasons such as
   `focus_unstable`, `too_dark_high_iso`, `exposure_too_long`, or `af_not_stable`.

### Capture stability controls

1. Tap **Start auto capture** and wait for the status message indicating AWB lock
   and OIS/video stabilization off.
2. Confirm auto capture still uses the `4000x3000` preview YUV stream (no still-capture template).
3. Confirm accepted frames are not rejected solely for AE searching (AE lock is not enabled).

### Quality gates on accepted JSON

1. After accepting frames, open `accepted_frames/accepted_*.json`.
2. Verify capture metadata keys are present on accepted frames.
3. Confirm frames with very high ISO or long exposure are rejected during auto capture.

### Rich calibration JSON and UI

1. Collect at least 10 accepted frames with varied board poses.
2. Tap **Calibrate**.
3. Confirm the UI shows RMS, median per-view, p90 per-view, solver variant, and
   used/dropped view counts.
4. Open `charuco_calibration_result.json` and verify:
   `per_view_errors_px`, `median_per_view_error_px`, `p90_per_view_error_px`,
   `solver_variant`, `solver_flags`, `used_frames`, `dropped_frames`,
   `outlier_threshold_px`, and `capture_summary` (median ISO, median exposure, focus range).
5. Compare solver variants: with fewer than 25 used views only `fix_k3` and `flags_zero`
   are compared; with 25+ views `rational_model` is also tried.

### Printed-board validation protocol

Use a **printed matte** 7×10 ChArUco board (25 mm squares, 18 mm markers). A laptop
screen is only useful for pipeline checks; sub-1 px RMS requires the printed board.

Success metrics to record per run:

- Global RMS (`reprojection_error_px`)
- Median per-view error (`median_per_view_error_px`)
- P90 per-view error (`p90_per_view_error_px`)
- Used vs dropped views
- Winning `solver_variant`
- Capture summary medians (ISO, exposure)

## Milestone I: full physical validation checklist

1. Run Milestones B–G in order on the S23 Ultra.
2. Confirm no CameraX dependency is required at runtime.
3. Confirm outputs use app-specific storage only (no storage permission prompt).
4. Rotate the phone in the UI while keeping analysis frames sensor-native landscape.
5. Compare on-device `charuco_calibration_result.json` against the Python audit script.

## Offline Python calibration audit

Requirements: Python 3, `opencv-contrib-python` (or full OpenCV build with `cv2.aruco`).

```bash
pip install opencv-contrib-python
python scripts/calibrate_charuco_from_android_frames.py \
  --input-dir /path/to/accepted_frames \
  --output-json /path/to/charuco_calibration_result.json \
  --image-width 4000 \
  --image-height 3000
```

- Reads `accepted_*.jpg` images from the Android `accepted_frames/` directory.
- Uses adjacent `accepted_*.json` metadata when present for dimensions.
- Exports the same JSON schema as the Android app.
- Useful for auditing or recovering calibration if on-device export needs verification.

## Milestone G: on-device calibration on the S23 Ultra

1. Collect at least 10–20 accepted frames with varied board poses.
2. Tap **Run calibration**.
3. Confirm calibration status shows success, RMS, median/p90 per-view errors, and solver variant.
4. Confirm `fx/fy/cx/cy` values appear in the UI.
5. Open `charuco_calibration_result.json` in the app files directory.
6. Verify JSON fields: `camera_matrix`, distortion coefficients, board config,
   `orientation_note`, `accepted_frames`, `used_frames`, `dropped_frames`,
   `solver_variant`, `per_view_errors_px`, `median_per_view_error_px`,
   `p90_per_view_error_px`, `outlier_threshold_px`, and `capture_summary`.

## Future end-to-end board test

Use the printed ChArUco board with 7x10 squares, 0.025 m square length,
0.018 m marker length, and `DICT_5X5_100`. Live detection, automatic acceptance,
and calibration steps will be added and expanded in later milestones.
