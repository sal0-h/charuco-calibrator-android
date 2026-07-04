# Physical testing notes

## Output locations

All Android outputs use the app-specific external files directory, normally:

`/storage/emulated/0/Android/data/com.example.charucocalibrator/files/`

- Camera report: `camera_report.json`
- Test frames: `test_frame_<epoch_millis>.jpg` and `.json`
- Accepted frames: `accepted_frames/accepted_<epoch_millis>.jpg` and `.json`
- Debug ID overlays: `debug_overlays/debug_<session>_frame<N>_ids.jpg`
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
4. Confirm `detection` becomes `detected` with at least 18 ChArUco corners (post #5 minimum).
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
8. Tap **Stop auto capture**, then **Clear session**, and confirm the counter resets.

## Sub-1 px session protocol (post #5)

1. Tap **New session** before capture (note session ID in UI).
2. **Start auto capture**; collect varied poses with ≥18 corners per frame (41–54 typical).
3. Confirm live stats show `ChArUco corners: N (min 18)` and `session: session_<epoch>`.
4. Tap **Export ID overlays** — verify 3 JPEGs under `debug_overlays/`.
5. Tap **Calibrate session** (requires ≥10 accepted frames in current session).
6. Record RMS, median, p90, and `solver_variant` (`flags_zero`).
7. Pull device files and audit with Python (see below). Use `--session-id` from calibration JSON.

Legacy frames without `capture_session_id` may remain on disk — always scope audits to the
calibration session, not the full `accepted_frames/` folder.

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

1. Collect at least 10 accepted frames with varied board poses in one session.
2. Tap **Calibrate session**.
3. Confirm the UI shows RMS, median per-view, p90 per-view, solver variant, and
   used/dropped view counts.
4. Open `charuco_calibration_result.json` and verify:
   `capture_session_id`, `per_view_errors_px`, `median_per_view_error_px`,
   `p90_per_view_error_px`, `solver_variant`, `solver_flags`, `used_frames`,
   `dropped_frames`, `outlier_threshold_px`, and `capture_summary`.
5. Solver is `flags_zero` only (no rational/fix_k3 A/B on current `main`).

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

Requirements: Python 3, `opencv-contrib-python`.

```bash
pip install opencv-contrib-python

# Pull from device (note: adb may nest accepted_frames/ twice)
adb pull /storage/emulated/0/Android/data/com.example.charucocalibrator/files/accepted_frames/ ./audit_pull/accepted_frames/
adb pull /storage/emulated/0/Android/data/com.example.charucocalibrator/files/debug_overlays/ ./audit_pull/debug_overlays/
adb pull /storage/emulated/0/Android/data/com.example.charucocalibrator/files/charuco_calibration_result.json ./audit_pull/

# Session-scoped stored-corner audit (authoritative parity check)
python scripts/audit_charuco_from_stored_json.py \
  --input-dir ./audit_pull/accepted_frames \
  --session-id session_<epoch_from_calibration_json> \
  --mode stored --flags 0 --frame-filter all \
  --output-json ./audit_pull/audit_stored_session.json
```

- Use `--mode stored` with corners from accepted JSON (matches Android).
- Do **not** use `--mode redetect` for parity checks (JPEG round-trip adds error).
- See `CALIBRATION_AUDIT.md` for expected results and interpretation.

## Milestone G: on-device calibration on the S23 Ultra

1. Collect at least 10–20 accepted frames with varied board poses in one session.
2. Tap **Calibrate session**.
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

## ARCore Explorer v1 — manual test checklist (S23 Ultra)

Prerequisites: Google Play Services for AR installed; camera permission granted.  
Optional on Arch Linux host: `sudo pacman -S android-tools` then `adb pull ...`.

### A. ChArUco regression (do first)

1. Install debug APK (`./gradlew installDebug` or Android Studio Run).
2. Open **Tools** → **ChArUco Calibrator**.
3. Grant camera if prompted.
4. Confirm `camera_id: 0` and analysis stream `4000×3000` (or documented fallback).
5. Confirm live ChArUco detection, auto-capture, and calibration still work.
6. Back to home — confirm no crash.

### B. ARCore Explorer — session and UI

1. From **Tools**, open **ARCore Explorer**.
2. If ARCore missing: confirm install prompt; install Google Play Services for AR; return.
3. Confirm GLES camera preview renders (portrait).
4. Confirm tracking banner shows `TRACKING` / `PAUSED` / `STOPPED` + failure reason.
5. Confirm intrinsics warning: ARCore ≠ Camera2 `camera_id 0` @ `4000×3000`.
6. Confirm **image** intrinsics (~640×480) and **texture** intrinsics (~1920×1080) update live.

### C. Depth and overlays

1. Wait until depth stats show **valid % > 0** before exporting.
2. Toggle overlay: Off / Depth / Confidence / Masked.
3. Toggle depth source: **Smoothed** (default, denser) vs **Raw** (sparse patches normal).
4. Confirm native depth resolution label (expect ~160×90) and alignment disclaimer.
5. Overlay will look blocky/misaligned — expected for v1.

### D. Snapshot export

1. Tap **Export snapshot** (after depth valid % > 0).
2. Confirm panel shows depth flags: `raw=true smoothed=true confidence=true` when available.
3. Pull or **Share files**:
   - Path: `/storage/emulated/0/Android/data/com.example.charucocalibrator/files/arcore_snapshots/`
   - `adb pull /sdcard/Android/data/com.example.charucocalibrator/files/arcore_snapshots/ .`
4. Verify JSON + artifacts for one timestamp:
   - `arcore_snapshot_<ts>.json`
   - `arcore_raw_depth_<ts>.bin` (28800 bytes for 160×90) + `.png`
   - `arcore_smoothed_depth_<ts>.bin` + `.png`
   - `arcore_confidence_<ts>.png`
5. In JSON: `raw_depth.valid_pixel_fraction` should be > 0; `bin_path` empty when `available: false`.
6. If `charuco_calibration_result.json` exists: confirm scaled ChArUco vs ARCore Δfx/Δfy at **640×480** comparison grid.

### E. Pass / fail criteria

| Check | Pass |
| --- | --- |
| ChArUco unchanged | Camera2 id 0, calibration still runs |
| ARCore session | Preview + tracking banner |
| Intrinsics | Image + texture on screen |
| Depth | valid % > 0 in good conditions |
| Export | JSON + bin + PNGs with matching sizes |
| No false claims | Do not use ARCore fx for ChArUco 4000×3000 calibration |
