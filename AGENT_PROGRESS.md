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
- Phase 1 + 2 (capture metadata, stability gates, solver A/B, rich report):
  `assembleDebug` and `lintDebug` passed.

## Phase 1 + 2 changes (sub-1 px calibration prep)

### Capture metadata on accepted frames

- Extended `FrameMetadata` with exposure, ISO, focal length, focus distance, and AF/AE/AWB states.
- `CaptureMetadataStore` associates metadata with image timestamps (exact or nearest prior).
- Accepted frame JSON and live UI surface capture metadata.

### Capture stability

- OIS and video stabilization disabled during auto capture (after warmup).
- AWB lock after 2.5 s warmup; AE lock remains off (known regression at ~7.5 px).
- `CaptureStabilityController` with default `af_trigger_then_reject_drift` focus policy.
- UI shows `warming_up` / `stable` / `focus_unstable` / `metadata_missing`.

### Quality gates

- `CaptureQualityGates`: `MAX_ISO=1000`, `MAX_EXPOSURE_TIME_NS=15ms`, focus drift threshold.
- Reject reasons: `too_dark_high_iso`, `exposure_too_long`, `focus_unstable`, `af_not_stable`,
  `capture_metadata_missing`.

### Solver A/B and rich report

- Compares `CALIB_FIX_K3`, `flags=0`, and optional `CALIB_RATIONAL_MODEL` (≥25 views).
- Winner by lowest RMS → median per-view → p90 per-view.
- `charuco_calibration_result.json` includes per-view errors, median, p90, solver variant,
  used/dropped counts, outlier threshold, and capture summary.

### Limitations

- Printed matte board required for meaningful sub-1 px validation; laptop screen ~3–4.5 px ceiling.
- Capture guidance bins, debug overlays, detector refinement A/B, and Python audit extension deferred.

## Commits and pushes

- `Add OpenCV frame processing pipeline` — pushed to `origin/main`.
- `Document ChArUco Android API feasibility` — pushed to `origin/main`.
- `Add live ChArUco detection prototype` — pushed to `origin/main`.
- `Add automatic ChArUco frame acceptance` — pushed to `origin/main`.
- `Add ChArUco calibration JSON export` — pushed to `origin/main`.
- `Add offline ChArUco calibration audit script` — pushed to `origin/main`.
- `Clean up calibration app lifecycle and docs` — pushed to `origin/main`.
- `Add capture stability gates and solver comparison` — pushed to `origin/main`.

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
- **ARCore Explorer v1 (Gate 2):** live preview, depth raw/smoothed toggle, snapshot export to `arcore_snapshots/`, ChArUco intrinsics diff panel.

## ARCore Explorer v1 audit (2026-07-04)

**Branch:** `feat/arcore-explorer-a863` (based on `main` @ `1f59cee`)  
**Git:** ARCore work is **uncommitted** — modified tracked files + untracked `arcore/` and `ui/tools/arcore/` trees.  
**No PR opened. Not pushed.**

### Commands run (audit)

```bash
git status
git log --oneline --max-count=10
./gradlew assembleDebug --no-daemon    # BUILD SUCCESSFUL
./gradlew lintDebug --no-daemon        # BUILD SUCCESSFUL (27 warnings, 0 errors)
./gradlew testDebugUnitTest --no-daemon # BUILD SUCCESSFUL (includes CharucoIntrinsicsAlignerTest)
```

### ChArUco path integrity

- `CharucoCalibratorScreen.kt` — **no diff vs `main`**
- `Camera2Controller.kt` — **no diff vs `main`**
- Still uses Camera2 `camera_id "0"`, preferred `4000×3000` analysis grid
- No CameraX dependency reintroduced
- ChArUco **not re-tested on device during this audit** (compile-only confirmation)

### What was implemented (code present, uncommitted)

| Area | Status |
| --- | --- |
| Home / tool picker with ChArUco + ARCore Explorer | Yes |
| ARCore session + GLSurfaceView preview (GLES2 external-OES via Compose `AndroidView`) | Yes (code) |
| Tracking state banner | Yes (code) |
| Image + texture intrinsics display | Yes (code) |
| Raw + smoothed depth read paths (`AUTOMATIC` depth mode) | Yes (code) |
| Confidence image read | Yes (code) |
| 4 overlay modes + raw/smoothed source toggle | Yes (code) |
| Snapshot JSON + bin + PNG export | Yes (code) |
| ChArUco intrinsics diff (scaled to ARCore image size) | Yes (code) |
| Share export via FileProvider | Yes (code) |
| ARCore availability / install check | Yes (code) |

### Device evidence (human tester — S23 Ultra)

User pulled `~/Downloads/arcore_snapshots/` with multiple exports. Latest good snapshot (`1783121174764`):

- `TRACKING`, ARCore image **640×480**, depth **160×90**
- Raw depth **92.3%** valid; smoothed **100%**; bin files **28800 B** (correct)
- Early snapshot (`1783113279541`) had **0%** valid depth (exported before depth ready)

**Not verified in audit:** ChArUco calibrator regression on device after ARCore install; live overlay quality; ARCore physical camera identity vs Camera2 id 0.

### Known limitations (honest)

- ARCore image stream ≠ ChArUco Camera2 stream (resolution, likely camera path).
- Depth overlay is **letterboxed approximate alignment**, not pixel-registered to preview.
- Depth resolution **160×90** — blocky; not useful for ChArUco-scale metrology.
- ARCore is **not** a ChArUco calibration replacement.
- First export after session start can capture **empty depth** if user taps too early.
- Portrait lock on `MainActivity` affects entire app.
- Physical S23 Ultra test required for any “works on device” claim beyond export file evidence above.
- CI/sandbox: compile + lint + unit tests only; no ARCore hardware in build environment.

### Incomplete / not done

- No git commit for ARCore work on feature branch
- No PR to `main`
- No automated instrumented ARCore tests
- Homography-correct depth overlay (deferred)
- Shared Camera ARCore + Camera2 (out of scope)
- Pose in export JSON (out of scope)

## ARCore Explorer v1 (feat/arcore-explorer-a863)

Milestones M0–M7 implemented on branch `feat/arcore-explorer-a863`:

| Milestone | Scope |
| --- | --- |
| M0 | `AppDestination.ArCoreExplorer`, Home card, AppRoot wiring |
| M1 | `com.google.ar:core:1.46.0`, manifest AR metadata, `ArCoreCapabilityChecker` |
| M2 | `ArCoreSessionController` + GLES2 external-OES `GLSurfaceView` preview, portrait lock |
| M3 | Image/texture intrinsics, tracking banner, Camera2 mismatch warning |
| M4 | Raw/smoothed depth read paths, confidence, heatmap overlay modes (no session reconfigure on toggle) |
| M5 | Snapshot export to `getExternalFilesDir(null)/arcore_snapshots/` |
| M6 | ChArUco `charuco_calibration_result.json` diff vs ARCore image intrinsics |
| M7 | Docs + `assembleDebug` / `lintDebug` |

### Build / lint (ARCore Explorer)

- `assembleDebug` and `lintDebug` passed (CI sandbox, no physical device).

### Output paths (ARCore)

Base directory:
`/storage/emulated/0/Android/data/com.example.charucocalibrator/files/arcore_snapshots/`

- Snapshot JSON: `arcore_snapshot_<epochMs>.json`
- Raw depth: `arcore_raw_depth_<epochMs>.bin` (uint16 LE mm) + `.png`
- Smoothed depth: `arcore_smoothed_depth_<epochMs>.bin` + `.png` (when available)
- Confidence: `arcore_confidence_<epochMs>.png`

## Output paths

Base directory:
`/storage/emulated/0/Android/data/com.example.charucocalibrator/files/`

- Test frames: `test_frame_<epoch>.jpg` + `.json`
- Accepted frames: `accepted_frames/accepted_<epoch>.jpg` + `.json`
- Camera report: `camera_report.json`
- Calibration JSON: `charuco_calibration_result.json`
- Offline script: `scripts/calibrate_charuco_from_android_frames.py`
