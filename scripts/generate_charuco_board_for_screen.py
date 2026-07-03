#!/usr/bin/env python3
"""Generate a ChArUco board image sized for on-screen calibration.

Matches the Android app board in BoardConfig.kt:
  7x10, 25 mm squares, 18 mm markers, DICT_5X5_100

Outputs:
  - PNG at a pixel size that matches --dpi when viewed at 100% zoom
  - HTML page that sizes the board with CSS millimetres (175 x 250 mm)
"""

from __future__ import annotations

import argparse
from pathlib import Path

import cv2

BOARD_SQUARES_X = 7
BOARD_SQUARES_Y = 10
BOARD_SQUARE_LENGTH_M = 0.025
BOARD_MARKER_LENGTH_M = 0.018
BOARD_DICTIONARY = cv2.aruco.DICT_5X5_100
MM_PER_INCH = 25.4


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate a ChArUco board for laptop-screen calibration."
    )
    parser.add_argument(
        "--output-dir",
        default="screen_board",
        help="Directory for charuco_board.png and charuco_board.html (default: screen_board).",
    )
    parser.add_argument(
        "--square-mm",
        type=float,
        default=BOARD_SQUARE_LENGTH_M * 1000.0,
        help="Displayed square size in millimetres (default: 25).",
    )
    parser.add_argument(
        "--dpi",
        type=float,
        default=96.0,
        help=(
            "Assumed display DPI when opening the PNG at 100%% zoom in an image viewer. "
            "Typical laptop values: 96 (Windows default), 110-130 (many MacBook logical displays)."
        ),
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


def mm_to_pixels(mm: float, dpi: float) -> int:
    return max(1, int(round(mm / MM_PER_INCH * dpi)))


def write_html(output_dir: Path, square_mm: float) -> Path:
    board_width_mm = BOARD_SQUARES_X * square_mm
    board_height_mm = BOARD_SQUARES_Y * square_mm
    html_path = output_dir / "charuco_board.html"
    html_path.write_text(
        f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>ChArUco board {board_width_mm:.0f} x {board_height_mm:.0f} mm</title>
  <style>
    html, body {{
      margin: 0;
      background: #111;
      height: 100%;
    }}
  body {{
      display: flex;
      align-items: center;
      justify-content: center;
      flex-direction: column;
      color: #ddd;
      font: 14px/1.4 system-ui, sans-serif;
    }}
    .hint {{
      margin: 12px;
      max-width: 40rem;
      text-align: center;
    }}
    img {{
      width: {board_width_mm:.3f}mm;
      height: {board_height_mm:.3f}mm;
      image-rendering: pixelated;
      background: white;
    }}
  </style>
</head>
<body>
  <div class="hint">
    Browser zoom must be 100%. OS display scaling should be 100% while you verify size.
    Measure one square with a ruler: it should be {square_mm:.1f} mm.
    Press F11 for fullscreen, then capture with the phone in landscape.
  </div>
  <img src="charuco_board.png" alt="ChArUco board" />
</body>
</html>
""",
        encoding="utf-8",
    )
    return html_path


def main() -> int:
    args = parse_args()
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    square_px = mm_to_pixels(args.square_mm, args.dpi)
    image_width_px = square_px * BOARD_SQUARES_X
    image_height_px = square_px * BOARD_SQUARES_Y

    board = create_board()
    image = board.generateImage((image_width_px, image_height_px))

    png_path = output_dir / "charuco_board.png"
    if not cv2.imwrite(str(png_path), image):
        raise SystemExit(f"Failed to write {png_path}")

    html_path = write_html(output_dir, args.square_mm)

    board_width_mm = BOARD_SQUARES_X * args.square_mm
    board_height_mm = BOARD_SQUARES_Y * args.square_mm
    print(f"Wrote {png_path}")
    print(f"Wrote {html_path}")
    print()
    print("Board spec (matches Android app):")
    print(f"  grid: {BOARD_SQUARES_X} x {BOARD_SQUARES_Y}")
    print(f"  square: {args.square_mm:.1f} mm")
    print(f"  marker: {BOARD_MARKER_LENGTH_M * 1000:.1f} mm")
    print(f"  overall: {board_width_mm:.1f} x {board_height_mm:.1f} mm")
    print(f"  PNG size: {image_width_px} x {image_height_px} px at {args.dpi:.0f} DPI")
    print()
    print("How to use on a laptop screen:")
    print("  1. Open charuco_board.html in a browser (or the PNG at 100% zoom).")
    print("  2. Set browser zoom to 100% and OS display scaling to 100% if possible.")
    print("  3. Measure one black/white square with a ruler.")
    print(f"     It must read {args.square_mm:.1f} mm. If not, rerun with a different --dpi.")
    print("  4. Fullscreen (F11), max brightness, dim room lights, avoid glare.")
    print("  5. Capture with the phone app. Expect more moire than a printed board.")
    print()
    print("If measured square size differs, update Android BoardConfig only when you")
    print("intentionally use a non-25 mm board. For a true 25 mm screen board, keep")
    print("BoardConfig as-is once the ruler check passes.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
