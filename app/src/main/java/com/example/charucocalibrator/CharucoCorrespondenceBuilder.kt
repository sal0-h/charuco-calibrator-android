package com.example.charucocalibrator

import android.util.Log
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
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
    /**
     * OpenCV Java `Board.matchImagePoints(List<Mat>, ...)` treats each list entry as one
     * detected marker (4 corners). ChArUco needs one 2D point per list entry, so the Python
     * binding works but the Java binding does not when given a single Mat of N charuco corners.
     * Map charuco IDs to [CharucoBoard.getChessboardCorners] directly instead.
     */
    fun build(
        frame: AcceptedFrameRecord,
        board: CharucoBoard,
        detector: CharucoDetector
    ): CorrespondenceSet? {
        if (!frame.charucoCorners.empty() && !frame.charucoIds.empty()) {
            buildManual(
                board = board,
                charucoCorners = frame.charucoCorners,
                charucoIds = frame.charucoIds,
                frameName = frame.imageFile.name,
                method = "stored_manual_id_map"
            )?.let { return it }
        }

        loadPersistedCorners(frame)?.let { persisted ->
            val (cornersMat, idsMat) = persisted.toMats()
            return try {
                buildManual(
                    board = board,
                    charucoCorners = cornersMat,
                    charucoIds = idsMat,
                    frameName = frame.imageFile.name,
                    method = "metadata_manual_id_map"
                )
            } finally {
                cornersMat.release()
                idsMat.release()
            }
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
                buildManual(
                    board = board,
                    charucoCorners = charucoCorners,
                    charucoIds = charucoIds,
                    frameName = frame.imageFile.name,
                    method = "jpeg_redetect_manual_id_map"
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

    private fun buildManual(
        board: CharucoBoard,
        charucoCorners: Mat,
        charucoIds: Mat,
        frameName: String,
        method: String
    ): CorrespondenceSet? {
        val boardCorners = board.chessboardCorners.toArray()
        if (boardCorners.isEmpty()) {
            Log.w(TAG, "$frameName: board has no chessboard corners")
            return null
        }

        val imageCorners = runCatching { MatOfPoint2f(charucoCorners).toArray() }.getOrElse { error ->
            Log.w(TAG, "$frameName: failed to read charuco image corners", error)
            return null
        }
        val cornerIds = runCatching { MatOfInt(charucoIds).toArray() }.getOrElse { error ->
            Log.w(TAG, "$frameName: failed to read charuco corner ids", error)
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

        val objectMat = MatOfPoint3f()
        objectMat.fromList(objectPoints)
        val imageMat = MatOfPoint2f()
        imageMat.fromList(matchedImagePoints)

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
