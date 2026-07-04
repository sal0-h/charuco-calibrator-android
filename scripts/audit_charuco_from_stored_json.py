#!/usr/bin/env python3
"""ChArUco calibration audit: stored corners vs JPEG re-detect (matches Android pipeline)."""

from __future__ import annotations

import argparse
import json
import math
import statistics
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

import cv2
import numpy as np

BOARD_SQUARES_X = 7
BOARD_SQUARES_Y = 10
BOARD_SQUARE_LENGTH_M = 0.025
BOARD_MARKER_LENGTH_M = 0.018
BOARD_DICTIONARY = cv2.aruco.DICT_5X5_100
ORIENTATION_NOTE = "Camera2 sensor-native landscape grid; not display-rotated"
MIN_CHARUCO_CORNERS_ANDROID = 12
MIN_CHARUCO_CORNERS_REDETECT = 8
MAX_PER_VIEW_REPROJECTION_ERROR_PX = 3.0


@dataclass
class FrameCorrespondence:
    name: str
    epoch_ms: int
    object_points: np.ndarray
    image_points: np.ndarray
    method: str
    corner_count: int
    sharpness: float | None


@dataclass
class CalibrationRun:
    mode: str
    flags_label: str
    flags: int
    frame_filter: str
    used_frames: int
    dropped_frames: int
    rms_px: float
    median_per_view_px: float
    p90_per_view_px: float
    per_view_errors_px: list[float]
    camera_matrix: np.ndarray
    distortion: np.ndarray
    frame_names: list[str]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", required=True, type=Path)
    parser.add_argument("--output-json", type=Path, default=None)
    parser.add_argument(
        "--mode",
        choices=("stored", "redetect", "corner-diff", "debug-ids"),
        default="stored",
    )
    parser.add_argument("--flags", default="0", help="0 or fix_k3")
    parser.add_argument(
        "--frame-filter",
        choices=("all", "cluster2", "ge18", "top30_sharp", "android10"),
        default="all",
    )
    parser.add_argument("--outlier-filter", action="store_true")
    parser.add_argument("--corner-diff-output", type=Path, default=None)
    parser.add_argument("--debug-dir", type=Path, default=None)
    parser.add_argument("--debug-count", type=int, default=3)
    parser.add_argument("--camera-id", default="0")
    return parser.parse_args()


def create_board() -> cv2.aruco.CharucoBoard:
    dictionary = cv2.aruco.getPredefinedDictionary(BOARD_DICTIONARY)
    return cv2.aruco.CharucoBoard(
        (BOARD_SQUARES_X, BOARD_SQUARES_Y),
        BOARD_SQUARE_LENGTH_M,
        BOARD_MARKER_LENGTH_M,
        dictionary,
    )


def parse_flags(flags_label: str) -> tuple[int, str]:
    normalized = flags_label.strip().lower()
    if normalized in ("0", "flags_zero", "zero"):
        return 0, "0"
    if normalized in ("fix_k3", "calib_fix_k3"):
        return cv2.CALIB_FIX_K3, "CALIB_FIX_K3"
    raise ValueError(f"Unsupported flags: {flags_label}")


def load_metadata(image_path: Path) -> dict:
    metadata_path = image_path.with_suffix(".json")
    if not metadata_path.is_file():
        return {}
    return json.loads(metadata_path.read_text(encoding="utf-8"))


def epoch_ms_from_name(path: Path) -> int:
    return int(path.stem.split("_", 1)[1])


def cluster_frames(image_paths: list[Path], gap_minutes: float = 10.0) -> list[list[Path]]:
    if not image_paths:
        return []
    sorted_paths = sorted(image_paths, key=epoch_ms_from_name)
    clusters: list[list[Path]] = [[sorted_paths[0]]]
    for path in sorted_paths[1:]:
        prev_ms = epoch_ms_from_name(clusters[-1][-1])
        cur_ms = epoch_ms_from_name(path)
        if (cur_ms - prev_ms) / 60000.0 > gap_minutes:
            clusters.append([path])
        else:
            clusters[-1].append(path)
    return clusters


def filter_image_paths(image_paths: list[Path], frame_filter: str) -> list[Path]:
    if frame_filter == "all":
        return sorted(image_paths, key=epoch_ms_from_name)
    if frame_filter == "cluster2":
        clusters = cluster_frames(image_paths)
        if len(clusters) < 2:
            raise RuntimeError("Expected at least 2 timestamp clusters for cluster2 filter")
        return clusters[1]
    if frame_filter == "ge18":
        selected = []
        for path in image_paths:
            meta = load_metadata(path)
            ids = meta.get("charuco_ids") or []
            if len(ids) >= 18:
                selected.append(path)
        return sorted(selected, key=epoch_ms_from_name)
    if frame_filter == "top30_sharp":
        scored: list[tuple[float, Path]] = []
        for path in image_paths:
            meta = load_metadata(path)
            sharpness = float(meta.get("sharpness", 0.0))
            scored.append((sharpness, path))
        scored.sort(reverse=True)
        return [path for _, path in scored[:30]]
    if frame_filter == "android10":
        clusters = cluster_frames(image_paths)
        if len(clusters) < 2:
            raise RuntimeError("Expected cluster2 for android10 filter")
        return clusters[1][-10:]
    raise ValueError(frame_filter)


def board_object_points(board: cv2.aruco.CharucoBoard, corner_ids: Iterable[int]) -> tuple[np.ndarray, np.ndarray]:
    """Manual ID map — mirrors CharucoCorrespondenceBuilder.buildManual."""
    chessboard_corners = board.getChessboardCorners()
    object_pts: list[list[float]] = []
    image_ids: list[int] = []
    for corner_id in corner_ids:
        if corner_id < 0 or corner_id >= len(chessboard_corners):
            continue
        pt = chessboard_corners[corner_id]
        object_pts.append([float(pt[0]), float(pt[1]), float(pt[2])])
        image_ids.append(int(corner_id))
    if not object_pts:
        return np.empty((0, 3), dtype=np.float64), np.empty((0, 1, 2), dtype=np.float64)
    return (
        np.asarray(object_pts, dtype=np.float64),
        np.asarray(image_ids, dtype=np.int32),
    )


def build_stored_correspondence(image_path: Path, board: cv2.aruco.CharucoBoard) -> FrameCorrespondence | None:
    meta = load_metadata(image_path)
    ids = meta.get("charuco_ids")
    corners_xy = meta.get("charuco_corners_xy")
    if not ids or not corners_xy:
        return None

    count = min(len(ids), len(corners_xy))
    chessboard_corners = board.getChessboardCorners()
    object_pts: list[list[float]] = []
    image_pts: list[list[float]] = []
    for index in range(count):
        corner_id = int(ids[index])
        if corner_id < 0 or corner_id >= len(chessboard_corners):
            continue
        pt = chessboard_corners[corner_id]
        object_pts.append([float(pt[0]), float(pt[1]), float(pt[2])])
        image_pts.append([float(corners_xy[index][0]), float(corners_xy[index][1])])

    if len(object_pts) < MIN_CHARUCO_CORNERS_ANDROID:
        return None

    return FrameCorrespondence(
        name=image_path.name,
        epoch_ms=epoch_ms_from_name(image_path),
        object_points=np.asarray(object_pts, dtype=np.float32),
        image_points=np.asarray(image_pts, dtype=np.float32),
        method="stored_manual_id_map",
        corner_count=len(object_pts),
        sharpness=meta.get("sharpness"),
    )


def build_redetect_correspondence(image_path: Path, board: cv2.aruco.CharucoBoard) -> FrameCorrespondence | None:
    gray = cv2.imread(str(image_path), cv2.IMREAD_GRAYSCALE)
    if gray is None:
        return None
    meta = load_metadata(image_path)
    detector = cv2.aruco.CharucoDetector(board)
    charuco_corners, charuco_ids, _, _ = detector.detectBoard(gray)
    if charuco_ids is None or len(charuco_ids) < MIN_CHARUCO_CORNERS_REDETECT:
        return None
    obj_pts, img_pts = board.matchImagePoints(charuco_corners, charuco_ids)
    if obj_pts is None or img_pts is None or len(obj_pts) < MIN_CHARUCO_CORNERS_REDETECT:
        return None
    return FrameCorrespondence(
        name=image_path.name,
        epoch_ms=epoch_ms_from_name(image_path),
        object_points=np.asarray(obj_pts, dtype=np.float32),
        image_points=np.asarray(img_pts, dtype=np.float32),
        method="jpeg_redetect_match_image_points",
        corner_count=len(obj_pts),
        sharpness=meta.get("sharpness"),
    )


def percentile90(values: list[float]) -> float:
    if not values:
        return 0.0
    sorted_vals = sorted(values)
    index = max(0, min(len(sorted_vals) - 1, math.ceil(0.9 * len(sorted_vals)) - 1))
    return sorted_vals[index]


def compute_outlier_threshold(per_view_errors: list[float]) -> float:
    if not per_view_errors:
        return MAX_PER_VIEW_REPROJECTION_ERROR_PX
    median = statistics.median(per_view_errors)
    return max(MAX_PER_VIEW_REPROJECTION_ERROR_PX, median * 2.0)


def solve_calibration(
    correspondences: list[FrameCorrespondence],
    image_size: tuple[int, int],
    flags: int,
    flags_label: str,
    mode: str,
    frame_filter: str,
    outlier_filter: bool,
) -> CalibrationRun:
    if len(correspondences) < 3:
        raise RuntimeError(f"Need at least 3 frames, got {len(correspondences)}")

    object_points = [c.object_points for c in correspondences]
    image_points = [c.image_points for c in correspondences]
    camera_matrix = np.eye(3, dtype=np.float64)
    distortion = np.zeros((1, 5), dtype=np.float64)

    result = cv2.calibrateCameraExtended(
        object_points,
        image_points,
        image_size,
        camera_matrix,
        distortion,
        flags=flags,
    )
    rms = float(result[0])
    per_view = result[-1]
    per_view_list = [float(per_view[i, 0]) for i in range(per_view.shape[0])]
    dropped = 0
    final_names = [c.name for c in correspondences]

    if outlier_filter:
        threshold = compute_outlier_threshold(per_view_list)
        keep = [i for i, err in enumerate(per_view_list) if err <= threshold]
        dropped = len(per_view_list) - len(keep)
        if dropped > 0 and len(keep) >= 3:
            kept_corr = [correspondences[i] for i in keep]
            object_points = [c.object_points for c in kept_corr]
            image_points = [c.image_points for c in kept_corr]
            camera_matrix = np.eye(3, dtype=np.float64)
            distortion = np.zeros((1, 5), dtype=np.float64)
            result = cv2.calibrateCameraExtended(
                object_points,
                image_points,
                image_size,
                camera_matrix,
                distortion,
                flags=flags,
            )
            rms = float(result[0])
            per_view = result[8]
            per_view_list = [float(per_view[i, 0]) for i in range(per_view.shape[0])]
            final_names = [c.name for c in kept_corr]

    return CalibrationRun(
        mode=mode,
        flags_label=flags_label,
        flags=flags,
        frame_filter=frame_filter,
        used_frames=len(per_view_list),
        dropped_frames=dropped,
        rms_px=float(rms),
        median_per_view_px=float(statistics.median(per_view_list)),
        p90_per_view_px=percentile90(per_view_list),
        per_view_errors_px=per_view_list,
        camera_matrix=camera_matrix,
        distortion=distortion,
        frame_names=final_names,
    )


def export_calibration_result(
    output_path: Path,
    run: CalibrationRun,
    image_size: tuple[int, int],
    camera_id: str,
    input_frame_count: int,
) -> None:
    payload = {
        "source": "charuco_calibration_audit",
        "mode": run.mode,
        "flags": run.flags_label,
        "frame_filter": run.frame_filter,
        "outlier_filter": run.dropped_frames > 0,
        "camera_id": camera_id,
        "image_width": image_size[0],
        "image_height": image_size[1],
        "orientation_note": ORIENTATION_NOTE,
        "board": {
            "type": "charuco",
            "squares_x": BOARD_SQUARES_X,
            "squares_y": BOARD_SQUARES_Y,
            "square_length_m": BOARD_SQUARE_LENGTH_M,
            "marker_length_m": BOARD_MARKER_LENGTH_M,
            "dictionary": "DICT_5X5_100",
        },
        "camera_matrix": run.camera_matrix.tolist(),
        "fx": float(run.camera_matrix[0, 0]),
        "fy": float(run.camera_matrix[1, 1]),
        "cx": float(run.camera_matrix[0, 2]),
        "cy": float(run.camera_matrix[1, 2]),
        "distortion_coefficients": run.distortion.reshape(-1).tolist()[:5],
        "reprojection_error_px": run.rms_px,
        "median_per_view_error_px": run.median_per_view_px,
        "p90_per_view_error_px": run.p90_per_view_px,
        "per_view_errors_px": run.per_view_errors_px,
        "used_frames": run.used_frames,
        "dropped_frames": run.dropped_frames,
        "input_frames": input_frame_count,
        "frame_names": run.frame_names,
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "opencv_version": cv2.__version__,
    }
    output_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def run_corner_diff(image_paths: list[Path], board: cv2.aruco.CharucoBoard, output_path: Path) -> None:
    detector = cv2.aruco.CharucoDetector(board)
    report = []
    sample_paths = pick_representative_frames(image_paths, count=10)

    for image_path in sample_paths:
        meta = load_metadata(image_path)
        stored_ids = [int(x) for x in meta.get("charuco_ids", [])]
        stored_xy = meta.get("charuco_corners_xy", [])
        stored_map = {
            stored_ids[i]: (float(stored_xy[i][0]), float(stored_xy[i][1]))
            for i in range(min(len(stored_ids), len(stored_xy)))
        }

        gray = cv2.imread(str(image_path), cv2.IMREAD_GRAYSCALE)
        charuco_corners, charuco_ids, _, _ = detector.detectBoard(gray)
        redetect_map: dict[int, tuple[float, float]] = {}
        if charuco_ids is not None and charuco_corners is not None:
            flat_corners = np.asarray(charuco_corners).reshape(-1, 2)
            flat_ids = np.asarray(charuco_ids).reshape(-1)
            for i, corner_id in enumerate(flat_ids):
                pt = flat_corners[i]
                redetect_map[int(corner_id)] = (float(pt[0]), float(pt[1]))

        common_ids = sorted(set(stored_map) & set(redetect_map))
        distances = [
            math.hypot(stored_map[cid][0] - redetect_map[cid][0], stored_map[cid][1] - redetect_map[cid][1])
            for cid in common_ids
        ]
        report.append(
            {
                "frame": image_path.name,
                "stored_id_count": len(stored_map),
                "redetect_id_count": len(redetect_map),
                "common_id_count": len(common_ids),
                "only_stored_ids": sorted(set(stored_map) - set(redetect_map)),
                "only_redetect_ids": sorted(set(redetect_map) - set(stored_map)),
                "mean_px_diff": statistics.mean(distances) if distances else None,
                "max_px_diff": max(distances) if distances else None,
                "median_px_diff": statistics.median(distances) if distances else None,
            }
        )

    summary = {
        "frames_compared": len(report),
        "mean_of_frame_means_px": statistics.mean(
            [entry["mean_px_diff"] for entry in report if entry["mean_px_diff"] is not None]
        )
        if any(entry["mean_px_diff"] is not None for entry in report)
        else None,
        "max_frame_max_px": max(
            (entry["max_px_diff"] for entry in report if entry["max_px_diff"] is not None),
            default=None,
        ),
        "frames": report,
    }
    output_path.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"Wrote corner diff report to {output_path}")


def pick_representative_frames(image_paths: list[Path], count: int) -> list[Path]:
    sorted_paths = sorted(image_paths, key=epoch_ms_from_name)
    if len(sorted_paths) <= count:
        return sorted_paths
    step = max(1, len(sorted_paths) // count)
    return [sorted_paths[i] for i in range(0, len(sorted_paths), step)][:count]


def run_debug_ids(image_paths: list[Path], debug_dir: Path, count: int) -> None:
    debug_dir.mkdir(parents=True, exist_ok=True)
    for image_path in pick_representative_frames(image_paths, count):
        meta = load_metadata(image_path)
        image = cv2.imread(str(image_path), cv2.IMREAD_COLOR)
        if image is None:
            continue
        ids = meta.get("charuco_ids", [])
        corners = meta.get("charuco_corners_xy", [])
        for i in range(min(len(ids), len(corners))):
            x, y = int(corners[i][0]), int(corners[i][1])
            cv2.circle(image, (x, y), 8, (0, 255, 0), 2)
            cv2.putText(
                image,
                str(ids[i]),
                (x + 10, y - 10),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.7,
                (0, 255, 255),
                2,
                cv2.LINE_AA,
            )
        out = debug_dir / f"frame_{image_path.stem}_ids.jpg"
        cv2.imwrite(str(out), image)
        print(f"Wrote {out}")


def main() -> int:
    args = parse_args()
    input_dir = args.input_dir
    image_paths = sorted(input_dir.glob("accepted_*.jpg"))
    if not image_paths:
        raise SystemExit(f"No accepted_*.jpg in {input_dir}")

    board = create_board()

    if args.mode == "corner-diff":
        output = args.corner_diff_output or Path("corner_diff_report.json")
        run_corner_diff(image_paths, board, output)
        return 0

    if args.mode == "debug-ids":
        debug_dir = args.debug_dir or Path("audit_debug")
        run_debug_ids(image_paths, debug_dir, args.debug_count)
        return 0

    flags, flags_label = parse_flags(args.flags)
    selected_paths = filter_image_paths(image_paths, args.frame_filter)
    builder = build_stored_correspondence if args.mode == "stored" else build_redetect_correspondence
    correspondences = [c for path in selected_paths if (c := builder(path, board)) is not None]
    if len(correspondences) < 3:
        raise SystemExit(
            f"Only {len(correspondences)} valid correspondences for mode={args.mode}, filter={args.frame_filter}"
        )

    meta0 = load_metadata(selected_paths[0])
    image_size = (
        int(meta0.get("image_width", 4000)),
        int(meta0.get("image_height", 3000)),
    )
    run = solve_calibration(
        correspondences=correspondences,
        image_size=image_size,
        flags=flags,
        flags_label=flags_label,
        mode=args.mode,
        frame_filter=args.frame_filter,
        outlier_filter=args.outlier_filter,
    )

    print(
        f"mode={args.mode} flags={flags_label} filter={args.frame_filter} "
        f"frames={run.used_frames} dropped={run.dropped_frames} "
        f"RMS={run.rms_px:.4f} median={run.median_per_view_px:.4f} p90={run.p90_per_view_px:.4f}"
    )

    if args.output_json:
        export_calibration_result(
            output_path=args.output_json,
            run=run,
            image_size=image_size,
            camera_id=args.camera_id,
            input_frame_count=len(selected_paths),
        )
        print(f"Wrote {args.output_json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
