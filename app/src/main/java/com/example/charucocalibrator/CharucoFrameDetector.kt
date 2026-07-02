package com.example.charucocalibrator

import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint2f
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.CharucoDetector
import kotlin.math.min

data class DetectionBoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val areaRatio: Double
)

data class DetectionResult(
    val markerCount: Int,
    val charucoCornerCount: Int,
    val status: String,
    val rejectionReason: String?,
    val bbox: DetectionBoundingBox?,
    val charucoCorners: Mat? = null,
    val charucoIds: Mat? = null
) {
    fun releaseCorrespondences() {
        charucoCorners?.release()
        charucoIds?.release()
    }

    companion object {
        fun idle() = DetectionResult(
            markerCount = 0,
            charucoCornerCount = 0,
            status = "idle",
            rejectionReason = null,
            bbox = null
        )

        fun failure(reason: String) = DetectionResult(
            markerCount = 0,
            charucoCornerCount = 0,
            status = "failed",
            rejectionReason = reason,
            bbox = null
        )
    }
}

class CharucoFrameDetector {
    private val board by lazy { BoardConfig.createBoard() }
    private val detector by lazy { CharucoDetector(board) }

    fun detect(gray: Mat): DetectionResult {
        val bgr = YuvToGrayMat.toBgr(gray)
        val charucoCorners = Mat()
        val charucoIds = Mat()
        val markerCorners = ArrayList<Mat>()
        val markerIds = MatOfInt()
        return try {
            detector.detectBoard(bgr, charucoCorners, charucoIds, markerCorners, markerIds)
            val markerCount = markerIds.rows()
            val cornerCount = charucoIds.rows()
            if (cornerCount == 0) {
                DetectionResult(
                    markerCount = markerCount,
                    charucoCornerCount = 0,
                    status = "no_charuco_corners",
                    rejectionReason = if (markerCount == 0) {
                        "no_aruco_markers_detected"
                    } else {
                        "markers_detected_but_charuco_corners_not_interpolated"
                    },
                    bbox = null
                )
            } else {
                val bbox = computeCornerBoundingBox(charucoCorners, gray.cols(), gray.rows())
                val status = if (cornerCount >= AcceptanceConfig.MIN_CHARUCO_CORNERS) {
                    "detected"
                } else {
                    "insufficient_corners"
                }
                val clonedCorners = if (status == "detected") charucoCorners.clone() else null
                val clonedIds = if (status == "detected") charucoIds.clone() else null
                DetectionResult(
                    markerCount = markerCount,
                    charucoCornerCount = cornerCount,
                    status = status,
                    rejectionReason = if (status == "detected") {
                        null
                    } else {
                        "charuco_corners=$cornerCount < ${AcceptanceConfig.MIN_CHARUCO_CORNERS}"
                    },
                    bbox = bbox,
                    charucoCorners = clonedCorners,
                    charucoIds = clonedIds
                )
            }
        } catch (exception: Exception) {
            DetectionResult.failure("detection_exception: ${exception.message}")
        } finally {
            bgr.release()
            charucoCorners.release()
            charucoIds.release()
            markerIds.release()
            markerCorners.forEach(Mat::release)
        }
    }

    private fun computeCornerBoundingBox(
        corners: Mat,
        frameWidth: Int,
        frameHeight: Int
    ): DetectionBoundingBox? {
        if (corners.empty() || corners.rows() == 0) return null
        val points = MatOfPoint2f(corners)
        return try {
            val rect = Imgproc.boundingRect(points)
            val left = rect.x.coerceAtLeast(0)
            val top = rect.y.coerceAtLeast(0)
            val right = min(frameWidth, rect.x + rect.width)
            val bottom = min(frameHeight, rect.y + rect.height)
            val width = (right - left).coerceAtLeast(0)
            val height = (bottom - top).coerceAtLeast(0)
            DetectionBoundingBox(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                areaRatio = width.toDouble() * height / (frameWidth.toDouble() * frameHeight)
            )
        } finally {
            points.release()
        }
    }
}
