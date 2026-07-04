# ChArUco Calibration Audit (2026-07-04)

Device: Samsung Galaxy S23 Ultra, Camera2 `camera_id 0`, 4000×3000 landscape.  
Data: `accepted_frames/` — 57 JPG+JSON pairs (matches phone), 3 capture sessions.

## Phase 1 — Inventory

| Metric | Value |
|--------|-------|
| JPG/JSON pairs | 57 |
| Stored `charuco_ids` + `charuco_corners_xy` | 57/57 (100%) |
| Corner count range | 12–54 (median 41) |
| Sessions (>10 min gap) | C1: 15 @ 17:25, **C2: 40 @ 17:50–18:02**, C3: 2 @ 23:39 UTC |
| Phone `charuco_calibration_result.json` | 10 used frames, RMS **1.477 px**, `flags_zero` |

Android `MAX_ACCEPTED_FRAMES = 30` caps in-memory capture; folder accumulates across sessions.

## Phase 2 — Calibration comparison

| Run | Frames | Mode | Flags | RMS | median | p90 |
|-----|--------|------|-------|-----|--------|-----|
| Android (phone export) | 10 | stored | 0 | **1.477** | 1.285 | 1.984 |
| Python `android10` | 10 | stored | 0 | **1.477** | 1.285 | 1.984 |
| Python cluster2 | 40 | stored | 0 | 2.135 | 1.913 | 2.769 |
| Python cluster2 | 40 | redetect | FIX_K3 | 2.174 | 1.935 | 2.676 |
| Python all | 57 | stored | 0 | 3.286 | 2.251 | 4.634 |
| Python all | 55 | redetect | FIX_K3 | 2.833 | 2.280 | 3.674 |
| Python top30 sharp | 30 | stored | 0 | 3.342 | 2.540 | 4.565 |

**Android 10-frame subset** = last 10 frames of session C2 (`accepted_1783101678133` … `accepted_1783101758424`). Per-view errors match Android JSON to 3 decimal places.

## Phase 3 — Stored vs re-detect corner diff (10 sample frames)

| Session | Mean px diff (stored vs re-detect) | Notes |
|---------|-----------------------------------|-------|
| C1 (early) | 0.9–2.7 px | One frame max 34.7 px outlier |
| C2 (main) | **~0.71 px** | Same ID sets; subpixel JPEG vs live YUV |
| C2 late | 0.71 px | 2 ID swaps on one frame (47/53 vs 32/33) |

Overall mean of frame means: **0.95 px**. JPEG re-detect audit on mixed sessions is not equivalent to Android stored-corner path.

## Phase 4 — ID mapping

3 debug overlays in `audit_debug/` — IDs follow monotonic grid, no obvious swaps. **Pass.**

## Fish summary (ranked)

1. **Apples vs oranges (highest evidence):** Android calibrated **10 frames** from one session; Python redetect ran **55 frames across 3 sessions** with `matchImagePoints` + `CALIB_FIX_K3`. Stored+flags=0 on the same 10 frames reproduces Android exactly.

2. **Mixed sessions inflate error:** Pooling all 57 frames (RMS 3.29) or top-30-by-sharpness across sessions (3.34) is worse than single-session cluster2 (2.13). Sub-1 px is blocked by **session heterogeneity + frame count**, not a solver bug.

3. **Re-detect ≠ stored corners:** ~0.7 px mean shift on C2 JPEGs; larger on C1. Live YUV subpixel corners at capture beat JPEG round-trip for audit fidelity.

## Answers

- **Is Android ~1.9 px trustworthy?** Yes. Actual export is **1.48 px RMS on 10 frames**; ~1.91 px is the **median per-view on 40-frame session C2** — consistent, not fabricated.
- **What blocks sub-1 px?** Corner noise (~0.7+ px JPEG vs live), multi-session pooling, board coverage/angle diversity, 4000×3000 JPEG compression, and phone-lens distortion — not ID-map or solver bugs.
- **Next code change:** Clear or tag `accepted_frames` per calibration session (or calibrate only the current in-memory batch) so exports cannot mix sessions and inflate RMS.

## Reproduce

```bash
conda activate myenv-colmap  # ~/.conda/envs/myenv-colmap
python scripts/audit_charuco_from_stored_json.py --input-dir ./accepted_frames --mode stored --flags 0 --frame-filter android10 --output-json ./audit_stored_flags0_android10.json
python scripts/audit_charuco_from_stored_json.py --input-dir ./accepted_frames --mode corner-diff --corner-diff-output ./corner_diff_report.json
```
