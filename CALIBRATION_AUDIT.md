# ChArUco Calibration Audit

Device: Samsung Galaxy S23 Ultra, Camera2 `camera_id 0`, 4000×3000 landscape YUV.  
Board: 7×10 ChArUco, `DICT_5X5_100`, 25 mm squares, 18 mm markers.

## Verdict

**Android calibration pipeline is correct.** Stored corners + manual ID map + `flags_zero`
reproduce on-device results exactly when scoped to the same session and frame set.
Sub-1 px is blocked by capture/board quality and session mixing — not solver or correspondence bugs.

## Audit run A — pre session isolation (2026-07-04)

57 JPG+JSON pairs on disk, 3 timestamp clusters (no `capture_session_id` tagging).

| Run | Frames | Mode | RMS | median | p90 |
|-----|--------|------|-----|--------|-----|
| Android (phone) | 10 | stored | **1.477** | 1.285 | 1.984 |
| Python `android10` | 10 | stored | **1.477** | 1.285 | 1.984 |
| Python cluster2 | 40 | stored | 2.135 | 1.913 | 2.769 |
| Python all (mixed) | 57 | stored | 3.286 | 2.251 | 4.634 |
| Python redetect (mixed) | 55 | redetect | 2.833 | 2.280 | 3.674 |

Per-view errors matched Android to 3 decimal places on the 10-frame subset.
JPEG re-detect on mixed sessions is **not** equivalent to the Android stored-corner path (~0.7 px mean shift on session C2).

## Audit run B — post session isolation (2026-07-04)

Session `session_1783163714265`: 16 frames, corner refine on, min 18 corners.

| Check | Result |
|-------|--------|
| Frames in session / used | 16 / 16 (0 dropped) |
| Android RMS / median / p90 | 1.8832 / 1.8050 / 2.3588 px |
| Python stored RMS / median / p90 | 1.8832 / 1.7990 / 2.3588 px |
| Per-view match | yes — max delta 1.23×10⁻⁶ px (16/16, `capture_frame_index` order) |
| Debug overlay ID mapping | pass — no swaps |
| Corner count range | 41–54 (all ≥18) |
| Stored vs re-detect (session) | ~0.04 px mean |

**Note:** 72 legacy frames without `capture_session_id` remained on disk. Calibrate only the
session tagged in `charuco_calibration_result.json`; pooling all frames inflates RMS.

## Reproduce

```bash
# Session-scoped audit (preferred after PR #5)
python scripts/audit_charuco_from_stored_json.py \
  --input-dir ./accepted_frames \
  --session-id session_1783163714265 \
  --mode stored --flags 0 --frame-filter all \
  --output-json ./audit_stored_session.json

# ID overlay check
python scripts/audit_charuco_from_stored_json.py \
  --input-dir ./accepted_frames \
  --session-id session_1783163714265 \
  --mode debug-ids --debug-dir ./debug_overlays --debug-count 10

# Corner stability (stored vs JPEG re-detect)
python scripts/audit_charuco_from_stored_json.py \
  --input-dir ./accepted_frames \
  --session-id session_1783163714265 \
  --mode corner-diff --corner-diff-output ./corner_diff_report.json
```

Use `scripts/audit_charuco_from_stored_json.py` only. The old JPEG re-detect-only script was removed.

## What blocks sub-1 px

1. Corner noise and board print/mount quality (~1.8+ px floor on current A4 board).
2. Uneven lighting / shadow reducing corner coverage (see debug overlays).
3. Pooling frames across sessions or legacy untagged frames.
4. Using `--mode redetect` instead of stored corners for parity checks.
