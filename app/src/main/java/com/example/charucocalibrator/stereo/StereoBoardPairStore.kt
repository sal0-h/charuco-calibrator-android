package com.example.charucocalibrator.stereo

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import com.example.charucocalibrator.AcceptanceConfig
import com.example.charucocalibrator.CharucoFrameDetector
import com.example.charucocalibrator.DetectionResult
import com.example.charucocalibrator.OpenCvMatAccess
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.FileOutputStream

data class StereoBoardPairRecord(
    val directory: File,
    val index: Int,
    val leftImageFile: File,
    val rightImageFile: File,
    val cornersFile: File,
    val leftCornerCount: Int,
    val rightCornerCount: Int,
    val timestampDeltaNs: Long?
)

class StereoBoardPairStore(
    context: Context
) {
    private val applicationContext = context.applicationContext
    private val frameDetector by lazy { CharucoFrameDetector() }

    private val baseDirectory: File
        get() {
            val root = checkNotNull(applicationContext.getExternalFilesDir(null)) {
                "App-specific external files directory is unavailable"
            }
            return File(root, "stereo_calibration_pairs").apply { mkdirs() }
        }

    fun listPairs(): List<StereoBoardPairRecord> =
        baseDirectory.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("pair_") }
            ?.sortedBy { it.name }
            ?.mapNotNull(::readRecord)
            .orEmpty()

    fun count(): Int = listPairs().size

    fun clear() {
        baseDirectory.listFiles()?.forEach { file ->
            file.deleteRecursively()
        }
    }

    fun savePair(
        leftFrame: StereoFrameSnapshot,
        rightFrame: StereoFrameSnapshot
    ): Result<StereoBoardPairRecord> = runCatching {
        val leftDetection = detectFromFrame(leftFrame)
        val rightDetection = detectFromFrame(rightFrame)

        val leftCount = leftDetection.charucoCornerCount
        val rightCount = rightDetection.charucoCornerCount
        if (leftCount < AcceptanceConfig.MIN_CHARUCO_CORNERS) {
            leftDetection.releaseCorrespondences()
            rightDetection.releaseCorrespondences()
            error("Left view has $leftCount corners (min ${AcceptanceConfig.MIN_CHARUCO_CORNERS})")
        }
        if (rightCount < AcceptanceConfig.MIN_CHARUCO_CORNERS) {
            leftDetection.releaseCorrespondences()
            rightDetection.releaseCorrespondences()
            error("Right view has $rightCount corners (min ${AcceptanceConfig.MIN_CHARUCO_CORNERS})")
        }

        val index = count() + 1
        val directory = File(baseDirectory, "pair_$index")
        check(directory.mkdirs() || directory.isDirectory) {
            "Failed to create calibration pair directory"
        }

        val leftFile = File(directory, "left.jpg")
        val rightFile = File(directory, "right.jpg")
        val cornersFile = File(directory, "corners.json")
        writeJpeg(leftFile, leftFrame)
        writeJpeg(rightFile, rightFrame)

        val timestampDeltaNs = StereoTimestampUtils.deltaNs(
            leftFrame.sensorTimestampNs,
            rightFrame.sensorTimestampNs
        )
        cornersFile.writeText(
            buildCornersJson(
                leftDetection = leftDetection,
                rightDetection = rightDetection,
                timestampDeltaNs = timestampDeltaNs
            ).toString(2)
        )

        leftDetection.releaseCorrespondences()
        rightDetection.releaseCorrespondences()

        StereoBoardPairRecord(
            directory = directory,
            index = index,
            leftImageFile = leftFile,
            rightImageFile = rightFile,
            cornersFile = cornersFile,
            leftCornerCount = leftCount,
            rightCornerCount = rightCount,
            timestampDeltaNs = timestampDeltaNs
        )
    }

    private fun detectFromFrame(frame: StereoFrameSnapshot): DetectionResult {
        val gray = nv21ToGray(frame)
        return try {
            frameDetector.detect(gray)
        } finally {
            gray.release()
        }
    }

    private fun nv21ToGray(frame: StereoFrameSnapshot): Mat {
        val tempFile = File.createTempFile("stereo_gray_", ".jpg", applicationContext.cacheDir)
        try {
            FileOutputStream(tempFile).use { output ->
                YuvImage(
                    frame.nv21,
                    ImageFormat.NV21,
                    frame.width,
                    frame.height,
                    null
                ).compressToJpeg(Rect(0, 0, frame.width, frame.height), 95, output)
            }
            return Imgcodecs.imread(tempFile.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
        } finally {
            tempFile.delete()
        }
    }

    private fun writeJpeg(file: File, frame: StereoFrameSnapshot) {
        FileOutputStream(file).use { output ->
            val compressed = YuvImage(
                frame.nv21,
                ImageFormat.NV21,
                frame.width,
                frame.height,
                null
            ).compressToJpeg(Rect(0, 0, frame.width, frame.height), 95, output)
            check(compressed) { "JPEG compression failed" }
        }
    }

    private fun buildCornersJson(
        leftDetection: DetectionResult,
        rightDetection: DetectionResult,
        timestampDeltaNs: Long
    ): JSONObject = JSONObject().apply {
        put("timestamp_delta_ns", timestampDeltaNs)
        put("left", detectionToJson(leftDetection))
        put("right", detectionToJson(rightDetection))
    }

    private fun detectionToJson(detection: DetectionResult): JSONObject = JSONObject().apply {
        put("corner_count", detection.charucoCornerCount)
        put("status", detection.status)
        put("ids", JSONArray().apply {
            OpenCvMatAccess.readIntRows(detection.charucoIds ?: Mat())?.forEach { put(it) }
        })
        put("corners", JSONArray().apply {
            OpenCvMatAccess.readPoint2fRows(detection.charucoCorners ?: Mat())?.forEach { point ->
                put(JSONArray().put(point.x).put(point.y))
            }
        })
    }

    private fun readRecord(directory: File): StereoBoardPairRecord? = runCatching {
        val index = directory.name.removePrefix("pair_").toInt()
        val corners = JSONObject(directory.resolve("corners.json").readText())
        StereoBoardPairRecord(
            directory = directory,
            index = index,
            leftImageFile = File(directory, "left.jpg"),
            rightImageFile = File(directory, "right.jpg"),
            cornersFile = File(directory, "corners.json"),
            leftCornerCount = corners.getJSONObject("left").getInt("corner_count"),
            rightCornerCount = corners.getJSONObject("right").getInt("corner_count"),
            timestampDeltaNs = corners.optLong("timestamp_delta_ns")
        )
    }.getOrNull()
}
