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

## Future end-to-end board test

Use the printed ChArUco board with 7x10 squares, 0.025 m square length,
0.018 m marker length, and `DICT_5X5_100`. Live detection, automatic acceptance,
and calibration steps will be added and expanded in later milestones.
