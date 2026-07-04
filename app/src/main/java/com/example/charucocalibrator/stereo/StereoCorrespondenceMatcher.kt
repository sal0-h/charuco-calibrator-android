package com.example.charucocalibrator.stereo

import com.example.charucocalibrator.AcceptanceConfig
import com.example.charucocalibrator.BoardConfig
import com.example.charucocalibrator.CharucoDetectorFactory
import com.example.charucocalibrator.OpenCvMatAccess
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.objdetect.CharucoBoard
import org.opencv.objdetect.CharucoDetector
import java.io.File

data class StereoMatchedCorrespondence(
    val objectPoints: Mat,
    val leftImagePoints: Mat,
    val rightImagePoints: Mat,
    val pointCount: Int,
    val pairIndex: Int
)

object StereoCorrespondenceMatcher {
    private val board: CharucoBoard by lazy { BoardConfig.createBoard() }
    private val detector: CharucoDetector by lazy { CharucoDetectorFactory.create(board) }

    fun matchPair(record: StereoBoardPairRecord): StereoMatchedCorrespondence? {
        val persisted = runCatching {
            JSONObject(record.cornersFile.readText())
        }.getOrNull() ?: return null

        val leftIds = persisted.getJSONObject("left").getJSONArray("ids").toIntList()
        val rightIds = persisted.getJSONObject("right").getJSONArray("ids").toIntList()
        val leftCorners = persisted.getJSONObject("left").getJSONArray("corners").toPointList()
        val rightCorners = persisted.getJSONObject("right").getJSONArray("corners").toPointList()
        return buildFromCorners(
            leftIds = leftIds,
            leftCorners = leftCorners,
            rightIds = rightIds,
            rightCorners = rightCorners,
            pairIndex = record.index
        ) ?: redetectFromJpeg(record)
    }

    private fun redetectFromJpeg(record: StereoBoardPairRecord): StereoMatchedCorrespondence? {
        val leftGray = Imgcodecs.imread(record.leftImageFile.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
        val rightGray = Imgcodecs.imread(record.rightImageFile.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
        if (!OpenCvMatAccess.isAlive(leftGray) || !OpenCvMatAccess.isAlive(rightGray)) {
            leftGray.release()
            rightGray.release()
            return null
        }

        val leftDetected = detectCorners(leftGray)
        val rightDetected = detectCorners(rightGray)
        leftGray.release()
        rightGray.release()

        return buildFromCorners(
            leftIds = leftDetected.first,
            leftCorners = leftDetected.second,
            rightIds = rightDetected.first,
            rightCorners = rightDetected.second,
            pairIndex = record.index
        )
    }

    private fun detectCorners(gray: Mat): Pair<List<Int>, List<Point>> {
        val charucoCorners = Mat()
        val charucoIds = Mat()
        val markerCorners = ArrayList<Mat>()
        val markerIds = Mat()
        return try {
            val bgr = com.example.charucocalibrator.YuvToGrayMat.toBgr(gray)
            try {
                detector.detectBoard(bgr, charucoCorners, charucoIds, markerCorners, markerIds)
                com.example.charucocalibrator.CharucoCornerRefiner.refine(gray, charucoCorners)
            } finally {
                bgr.release()
            }
            val ids = OpenCvMatAccess.readIntRows(charucoIds).orEmpty()
            val corners = OpenCvMatAccess.readPoint2fRows(charucoCorners).orEmpty()
            ids to corners
        } finally {
            charucoCorners.release()
            charucoIds.release()
            markerIds.release()
            markerCorners.forEach(Mat::release)
        }
    }

    private fun buildFromCorners(
        leftIds: List<Int>,
        leftCorners: List<Point>,
        rightIds: List<Int>,
        rightCorners: List<Point>,
        pairIndex: Int
    ): StereoMatchedCorrespondence? {
        val boardCorners = OpenCvMatAccess.readBoardCorners(board) ?: return null
        val rightById = rightIds.zip(rightCorners).toMap()

        val objectPoints = ArrayList<Point3>()
        val leftImagePoints = ArrayList<Point>()
        val rightImagePoints = ArrayList<Point>()

        for (index in leftIds.indices) {
            val cornerId = leftIds[index]
            val rightPoint = rightById[cornerId] ?: continue
            if (cornerId < 0 || cornerId >= boardCorners.size) continue
            objectPoints.add(boardCorners[cornerId])
            leftImagePoints.add(leftCorners[index])
            rightImagePoints.add(rightPoint)
        }

        if (objectPoints.size < AcceptanceConfig.MIN_CHARUCO_CORNERS) {
            return null
        }

        val objectMat = OpenCvMatAccess.toObjectPointsMat(objectPoints) ?: return null
        val leftMat = OpenCvMatAccess.toImagePointsMat(leftImagePoints) ?: run {
            objectMat.release()
            return null
        }
        val rightMat = OpenCvMatAccess.toImagePointsMat(rightImagePoints) ?: run {
            objectMat.release()
            leftMat.release()
            return null
        }

        return StereoMatchedCorrespondence(
            objectPoints = objectMat,
            leftImagePoints = leftMat,
            rightImagePoints = rightMat,
            pointCount = objectPoints.size,
            pairIndex = pairIndex
        )
    }

    fun release(correspondence: StereoMatchedCorrespondence) {
        correspondence.objectPoints.release()
        correspondence.leftImagePoints.release()
        correspondence.rightImagePoints.release()
    }

    private fun JSONArray.toIntList(): List<Int> = buildList {
        for (index in 0 until length()) {
            add(getInt(index))
        }
    }

    private fun JSONArray.toPointList(): List<Point> = buildList {
        for (index in 0 until length()) {
            val point = getJSONArray(index)
            add(Point(point.getDouble(0), point.getDouble(1)))
        }
    }
}
