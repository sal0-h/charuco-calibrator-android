package com.example.charucocalibrator

import android.util.Log
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.objdetect.CharucoBoard
import org.opencv.objdetect.CharucoDetector

data class CorrespondenceSet(
    val objectPoints: Mat,
    val imagePoints: Mat,
    val method: String,
    val pointCount: Int
)

object CharucoCorrespondenceBuilder {
    fun build(
        frame: AcceptedFrameRecord,
        board: CharucoBoard,
        detector: CharucoDetector
    ): CorrespondenceSet? {
        buildFromStoredCharuco(frame, board)?.let { return it }
        buildFromSavedJpeg(frame, board, detector)?.let { return it }
        return null
    }

    private fun buildFromStoredCharuco(
        frame: AcceptedFrameRecord,
        board: CharucoBoard
    ): CorrespondenceSet? {
        if (frame.charucoCorners.empty() || frame.charucoIds.empty()) return null
        return matchCharucoPoints(
            board = board,
            charucoCorners = frame.charucoCorners,
            charucoIds = frame.charucoIds,
            method = "stored_charuco_matchImagePoints",
            frameName = frame.imageFile.name
        )
    }

    private fun buildFromSavedJpeg(
        frame: AcceptedFrameRecord,
        board: CharucoBoard,
        detector: CharucoDetector
    ): CorrespondenceSet? {
        val gray = Imgcodecs.imread(frame.imageFile.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
        if (gray.empty()) {
            Log.w(TAG, "${frame.imageFile.name}: could not read saved JPEG")
            return null
        }

        val bgr = YuvToGrayMat.toBgr(gray)
        gray.release()

        val charucoCorners = Mat()
        val charucoIds = Mat()
        val markerCorners = ArrayList<Mat>()
        val markerIds = Mat()
        return try {
            detector.detectBoard(bgr, charucoCorners, charucoIds, markerCorners, markerIds)
            if (charucoIds.rows() < AcceptanceConfig.MIN_CHARUCO_CORNERS) {
                Log.w(
                    TAG,
                    "${frame.imageFile.name}: JPEG re-detect found ${charucoIds.rows()} charuco corners"
                )
                null
            } else {
                matchCharucoPoints(
                    board = board,
                    charucoCorners = charucoCorners,
                    charucoIds = charucoIds,
                    method = "jpeg_redetect_matchImagePoints",
                    frameName = frame.imageFile.name
                )
            }
        } catch (exception: Exception) {
            Log.w(TAG, "${frame.imageFile.name}: JPEG re-detect failed", exception)
            null
        } finally {
            bgr.release()
            charucoCorners.release()
            charucoIds.release()
            markerIds.release()
            markerCorners.forEach(Mat::release)
        }
    }

    private fun matchCharucoPoints(
        board: CharucoBoard,
        charucoCorners: Mat,
        charucoIds: Mat,
        method: String,
        frameName: String
    ): CorrespondenceSet? {
        val objectPoints = Mat()
        val imagePoints = Mat()
        return try {
            board.matchImagePoints(listOf(charucoCorners), charucoIds, objectPoints, imagePoints)
            val pointCount = objectPoints.rows()
            if (pointCount < AcceptanceConfig.MIN_CHARUCO_CORNERS) {
                Log.w(TAG, "$frameName: $method produced only $pointCount points")
                objectPoints.release()
                imagePoints.release()
                null
            } else {
                Log.i(TAG, "$frameName: $method produced $pointCount points")
                CorrespondenceSet(
                    objectPoints = objectPoints,
                    imagePoints = imagePoints,
                    method = method,
                    pointCount = pointCount
                )
            }
        } catch (exception: Exception) {
            Log.w(TAG, "$frameName: $method failed", exception)
            objectPoints.release()
            imagePoints.release()
            null
        }
    }

    private const val TAG = "CharucoCorrespondenceBuilder"
}
