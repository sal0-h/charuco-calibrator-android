package com.example.charucocalibrator

import android.util.Log
import org.json.JSONObject
import org.opencv.core.Mat
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
    fun build(
        frame: AcceptedFrameRecord,
        board: CharucoBoard,
        detector: CharucoDetector
    ): CorrespondenceSet? {
        return runCatching {
            buildInternal(frame, board, detector)
        }.getOrElse { error ->
            Log.w(TAG, "${frame.imageFile.name}: correspondence build crashed", error)
            null
        }
    }

    private fun buildInternal(
        frame: AcceptedFrameRecord,
        board: CharucoBoard,
        detector: CharucoDetector
    ): CorrespondenceSet? {
        if (OpenCvMatAccess.isAlive(frame.charucoCorners) && OpenCvMatAccess.isAlive(frame.charucoIds)) {
            buildManual(
                board = board,
                charucoCorners = frame.charucoCorners,
                charucoIds = frame.charucoIds,
                frameName = frame.imageFile.name,
                method = "stored_manual_id_map"
            )?.let { return it }
        }

        loadPersistedCorners(frame)?.let { persisted ->
            buildManual(
                board = board,
                imageCorners = persisted.imagePoints.toList(),
                cornerIds = persisted.ids.toList(),
                frameName = frame.imageFile.name,
                method = "metadata_manual_id_map"
            )?.let { return it }
        }

        return buildFromSavedJpeg(frame, board, detector)
    }

    private fun loadPersistedCorners(frame: AcceptedFrameRecord): PersistedCharucoCorners? {
        if (!frame.metadataFile.isFile) return null
        return runCatching {
            CharucoCornerPersistence.readFromMetadata(
                JSONObject(frame.metadataFile.readText())
            )
        }.getOrNull()
    }

    private fun buildFromSavedJpeg(
        frame: AcceptedFrameRecord,
        board: CharucoBoard,
        detector: CharucoDetector
    ): CorrespondenceSet? {
        val gray = Imgcodecs.imread(frame.imageFile.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
        if (!OpenCvMatAccess.isAlive(gray)) {
            Log.w(TAG, "${frame.imageFile.name}: could not read saved JPEG")
            return null
        }

        val bgr = YuvConversions.toBgr(gray)
        gray.release()

        val charucoCorners = Mat()
        val charucoIds = Mat()
        val markerCorners = ArrayList<Mat>()
        val markerIds = Mat()
        return try {
            detector.detectBoard(bgr, charucoCorners, charucoIds, markerCorners, markerIds)
            buildManual(
                board = board,
                charucoCorners = charucoCorners,
                charucoIds = charucoIds,
                frameName = frame.imageFile.name,
                method = "jpeg_redetect_manual_id_map"
            )
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

    private fun buildManual(
        board: CharucoBoard,
        charucoCorners: Mat,
        charucoIds: Mat,
        frameName: String,
        method: String
    ): CorrespondenceSet? {
        val imageCorners = OpenCvMatAccess.readPoint2fRows(charucoCorners) ?: return null
        val cornerIds = OpenCvMatAccess.readIntRows(charucoIds) ?: return null
        return buildManual(
            board = board,
            imageCorners = imageCorners,
            cornerIds = cornerIds,
            frameName = frameName,
            method = method
        )
    }

    private fun buildManual(
        board: CharucoBoard,
        imageCorners: List<Point>,
        cornerIds: List<Int>,
        frameName: String,
        method: String
    ): CorrespondenceSet? {
        val boardCorners = OpenCvMatAccess.readBoardCorners(board) ?: run {
            Log.w(TAG, "$frameName: board has no chessboard corners")
            return null
        }

        val pairCount = minOf(imageCorners.size, cornerIds.size)
        if (pairCount == 0) return null

        val objectPoints = ArrayList<Point3>()
        val matchedImagePoints = ArrayList<Point>()
        for (index in 0 until pairCount) {
            val cornerId = cornerIds[index]
            if (cornerId < 0 || cornerId >= boardCorners.size) continue
            objectPoints.add(boardCorners[cornerId])
            matchedImagePoints.add(imageCorners[index])
        }

        if (objectPoints.size < AcceptanceConfig.MIN_CHARUCO_CORNERS) {
            Log.w(
                TAG,
                "$frameName: $method produced ${objectPoints.size} points " +
                    "(board_corners=${boardCorners.size}, ids=${cornerIds.size}, " +
                    "image_corners=${imageCorners.size})"
            )
            return null
        }

        val objectMat = OpenCvMatAccess.toObjectPointsMat(objectPoints) ?: return null
        val imageMat = OpenCvMatAccess.toImagePointsMat(matchedImagePoints) ?: run {
            objectMat.release()
            return null
        }

        Log.i(TAG, "$frameName: $method produced ${objectPoints.size} points")
        return CorrespondenceSet(
            objectPoints = objectMat,
            imagePoints = imageMat,
            method = method,
            pointCount = objectPoints.size
        )
    }

    private const val TAG = "CharucoCorrespondenceBuilder"
}
