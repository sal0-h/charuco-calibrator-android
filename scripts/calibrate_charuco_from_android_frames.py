#!/usr/bin/env python3
"""Offline ChArUco calibration audit for Android-exported accepted frames."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

import cv2
import numpy as np

BOARD_SQUARES_X = 7
BOARD_SQUARES_Y = 10
BOARD_SQUARE_LENGTH_M = 0.025
BOARD_MARKER_LENGTH_M = 0.018
BOARD_DICTIONARY = cv2.aruco.DICT_5X5_100
ORIENTATION_NOTE = "Camera2 sensor-native landscape grid; not display-rotated"
MIN_CHARUCO_CORNERS = 8


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Calibrate camera intrinsics from Android accepted ChArUco frames."
    )
    parser.add_argument(
        "--input-dir",
        required=True,
        help="Directory containing accepted_*.jpg images (and optional metadata JSON files).",
    )
    parser.add_argument(
        "--output-json",
        required=True,
        help="Output path for charuco_calibration_result.json.",
    )
    parser.add_argument(
        "--image-width",
        type=int,
        default=None,
        help="Optional analysis image width override.",
    )
    parser.add_argument(
        "--image-height",
        type=int,
        default=None,
        help="Optional analysis image height override.",
    )
    parser.add_argument(
        "--camera-id",
        default="0",
        help="Camera id to record in the output JSON (default: 0).",
    )
    return parser.parse_args()


def create_board() -> cv2.aruco.CharucoBoard:
    dictionary = cv2.aruco.getPredefinedDictionary(BOARD_DICTIONARY)
    return cv2.aruco.CharucoBoard(
        (BOARD_SQUARES_X, BOARD_SQUARES_Y),
        BOARD_SQUARE_LENGTH_M,
        BOARD_MARKER_LENGTH_M,
        dictionary,
    )


def load_metadata(image_path: Path) -> dict:
    metadata_path = image_path.with_suffix(".json")
    if not metadata_path.is_file():
        return {}
    return json.loads(metadata_path.read_text(encoding="utf-8"))


def detect_charuco(gray: np.ndarray, board: cv2.aruco.CharucoBoard):
    detector = cv2.aruco.CharucoDetector(board)
    charuco_corners, charuco_ids, marker_corners, marker_ids = detector.detectBoard(gray)
    return charuco_corners, charuco_ids, marker_corners, marker_ids


def calibrate_from_directory(
    input_dir: Path,
    image_width: int | None,
    image_height: int | None,
) -> tuple[list[np.ndarray], list[np.ndarray], tuple[int, int], int]:
    board = create_board()
    object_points: list[np.ndarray] = []
    image_points: list[np.ndarray] = []
    resolved_size: tuple[int, int] | None = None
    used_frames = 0

    image_paths = sorted(input_dir.glob("accepted_*.jpg"))
    if not image_paths:
        image_paths = sorted(input_dir.glob("*.jpg"))

    for image_path in image_paths:
        image = cv2.imread(str(image_path), cv2.IMREAD_GRAYSCALE)
        if image is None:
            continue

        metadata = load_metadata(image_path)
        frame_width = image_width or metadata.get("image_width") or image.shape[1]
        frame_height = image_height or metadata.get("image_height") or image.shape[0]
        if resolved_size is None:
            resolved_size = (int(frame_width), int(frame_height))

        charuco_corners, charuco_ids, _, _ = detect_charuco(image, board)
        if charuco_ids is None or len(charuco_ids) < MIN_CHARUCO_CORNERS:
            continue

        obj_pts, img_pts = board.matchImagePoints(charuco_corners, charuco_ids)
        if obj_pts is None or img_pts is None or len(obj_pts) < MIN_CHARUCO_CORNERS:
            continue

        object_points.append(obj_pts)
        image_points.append(img_pts)
        used_frames += 1

    if resolved_size is None:
        raise RuntimeError(f"No readable images found in {input_dir}")

    return object_points, image_points, resolved_size, used_frames


def export_result(
    output_path: Path,
    camera_matrix: np.ndarray,
    distortion: np.ndarray,
    reprojection_error: float,
    accepted_frames: int,
    image_size: tuple[int, int],
    camera_id: str,
) -> None:
    payload = {
        "source": "android_camera2_charuco_live",
        "device_hint": "Samsung Galaxy S23 Ultra",
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
        "camera_matrix": camera_matrix.tolist(),
        "fx": float(camera_matrix[0, 0]),
        "fy": float(camera_matrix[1, 1]),
        "cx": float(camera_matrix[0, 2]),
        "cy": float(camera_matrix[1, 2]),
        "distortion_model": "opencv_pinhole_5",
        "distortion_coefficients": distortion.reshape(-1).tolist()[:5],
        "reprojection_error_px": float(reprojection_error),
        "accepted_frames": accepted_frames,
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "opencv_version": cv2.__version__,
    }
    output_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def main() -> int:
    args = parse_args()
    input_dir = Path(args.input_dir)
    output_json = Path(args.output_json)

    object_points, image_points, image_size, used_frames = calibrate_from_directory(
        input_dir=input_dir,
        image_width=args.image_width,
        image_height=args.image_height,
    )

    if used_frames < 3:
        raise SystemExit(f"Need at least 3 valid frames, found {used_frames}")

    camera_matrix = np.eye(3, dtype=np.float64)
    distortion = np.zeros((1, 5), dtype=np.float64)
    reprojection_error, _, _, _, _ = cv2.calibrateCamera(
        object_points,
        image_points,
        image_size,
        camera_matrix,
        distortion,
        flags=cv2.CALIB_FIX_K3,
    )

    export_result(
        output_path=output_json,
        camera_matrix=camera_matrix,
        distortion=distortion,
        reprojection_error=reprojection_error,
        accepted_frames=used_frames,
        image_size=image_size,
        camera_id=args.camera_id,
    )
    print(f"Wrote calibration JSON to {output_json}")
    print(f"Used {used_frames} frames, reprojection error = {reprojection_error:.4f} px")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
