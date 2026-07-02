package com.example.charucocalibrator

import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
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
    fun build(frame: AcceptedFrameRecord, board: CharucoBoard): CorrespondenceSet? {
        buildFromStoredCharuco(frame, board)?.let { return it }
        buildFromRedetectedImage(frame, board)?.let { return it }
        return null
    }

    private fun buildFromStoredCharuco(
        frame: AcceptedFrameRecord,
        board: CharucoBoard
    ): CorrespondenceSet? {
        buildManual(
            frame = frame,
            board = board,
            charucoCorners = frame.charucoCorners,
            charucoIds = frame.charucoIds,
            method = "manual_charuco_ids"
        )?.let { return it }

        tryMatchImagePoints(
            board = board,
            corners = listOf(frame.charucoCorners),
            ids = frame.charucoIds,
            method = "charuco_matchImagePoints"
        )?.let { return it }

        tryMatchImagePoints(
            board = board,
            corners = frame.markerCorners,
            ids = frame.markerIds,
            method = "marker_matchImagePoints"
        )?.let { return it }

        return null
    }

    private fun buildFromRedetectedImage(
        frame: AcceptedFrameRecord,
        board: CharucoBoard
    ): CorrespondenceSet? {
        val gray = Imgcodecs.imread(frame.imageFile.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
        if (gray.empty()) {
            Log.w(TAG, "${frame.imageFile.name}: could not load JPEG for re-detect")
            return null
        }

        val charucoCorners = Mat()
        val charucoIds = Mat()
        val markerCorners = ArrayList<Mat>()
        val markerIds = Mat()
        return try {
            CharucoDetector(board).detectBoard(
                gray,
                charucoCorners,
                charucoIds,
                markerCorners,
                markerIds
            )
            if (charucoIds.rows() < AcceptanceConfig.MIN_CHARUCO_CORNERS) {
                Log.w(
                    TAG,
                    "${frame.imageFile.name}: re-detect found ${charucoIds.rows()} charuco corners"
                )
                null
            } else {
                buildManual(
                    frame = frame,
                    board = board,
                    charucoCorners = charucoCorners,
                    charucoIds = charucoIds,
                    method = "redetect_manual_charuco_ids"
                ) ?: tryMatchImagePoints(
                    board = board,
                    corners = listOf(charucoCorners),
                    ids = charucoIds,
                    method = "redetect_charuco_matchImagePoints"
                )
            }
        } catch (exception: Exception) {
            Log.w(TAG, "${frame.imageFile.name}: re-detect failed", exception)
            null
        } finally {
            gray.release()
            charucoCorners.release()
            charucoIds.release()
            markerIds.release()
            markerCorners.forEach(Mat::release)
        }
    }

    private fun buildManual(
        frame: AcceptedFrameRecord,
        board: CharucoBoard,
        charucoCorners: Mat,
        charucoIds: Mat,
        method: String
    ): CorrespondenceSet? {
        val chessboardCorners: MatOfPoint3f = board.chessboardCorners
        val boardPoints = chessboardCorners.toArray()
        if (boardPoints.isEmpty()) return null
        if (charucoIds.empty() || charucoCorners.empty()) return null

        val imageCornerPoints = readImagePoints(charucoCorners) ?: return null
        if (imageCornerPoints.size != charucoIds.rows()) {
            Log.w(
                TAG,
                "${frame.imageFile.name}: corner/id count mismatch " +
                    "(${imageCornerPoints.size} vs ${charucoIds.rows()})"
            )
        }

        val objectPoints = ArrayList<Point3>()
        val imagePoints = ArrayList<Point>()
        val pairCount = minOf(imageCornerPoints.size, charucoIds.rows())
        for (index in 0 until pairCount) {
            val cornerId = charucoIds.get(index, 0)[0].toInt()
            if (cornerId < 0 || cornerId >= boardPoints.size) continue

            objectPoints.add(boardPoints[cornerId])
            imagePoints.add(imageCornerPoints[index])
        }

        if (objectPoints.size < AcceptanceConfig.MIN_CHARUCO_CORNERS) {
            Log.w(TAG, "${frame.imageFile.name}: $method produced ${objectPoints.size} points")
            return null
        }

        val objectMat = MatOfPoint3f()
        objectMat.fromList(objectPoints)
        val imageMat = MatOfPoint2f()
        imageMat.fromList(imagePoints)

        Log.i(TAG, "${frame.imageFile.name}: $method produced ${objectPoints.size} points")
        return CorrespondenceSet(
            objectPoints = objectMat,
            imagePoints = imageMat,
            method = method,
            pointCount = objectPoints.size
        )
    }

    private fun tryMatchImagePoints(
        board: CharucoBoard,
        corners: List<Mat>,
        ids: Mat,
        method: String
    ): CorrespondenceSet? {
        if (corners.isEmpty() || ids.empty()) return null

        val objectPoints = Mat()
        val imagePoints = Mat()
        return try {
            board.matchImagePoints(corners, ids, objectPoints, imagePoints)
            val pointCount = objectPoints.rows()
            if (pointCount < AcceptanceConfig.MIN_CHARUCO_CORNERS) {
                objectPoints.release()
                imagePoints.release()
                null
            } else {
                Log.i(TAG, "$method produced $pointCount points")
                CorrespondenceSet(
                    objectPoints = objectPoints,
                    imagePoints = imagePoints,
                    method = method,
                    pointCount = pointCount
                )
            }
        } catch (exception: Exception) {
            Log.w(TAG, "$method failed", exception)
            objectPoints.release()
            imagePoints.release()
            null
        }
    }

    private fun readImagePoints(corners: Mat): List<Point>? {
        if (corners.empty()) return null
        return try {
            MatOfPoint2f(corners).toList()
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to read charuco corner points", exception)
            null
        }
    }

    private const val TAG = "CharucoCorrespondenceBuilder"
}
