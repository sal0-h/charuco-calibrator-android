package com.example.charucocalibrator.stereo

import android.content.Context
import com.example.charucocalibrator.AcceptanceConfig
import com.example.charucocalibrator.CharucoFrameDetector
import com.example.charucocalibrator.DetectionResult
import com.example.charucocalibrator.Dimensions
import com.example.charucocalibrator.OpenCvMatAccess
import com.example.charucocalibrator.YuvConversions
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import java.io.File

data class StereoBoardPairRecord(
    val directory: File,
    val index: Int,
    val leftImageFile: File,
    val rightImageFile: File,
    val cornersFile: File,
    val leftPhysicalCameraId: String,
    val rightPhysicalCameraId: String,
    val leftResolution: Dimensions?,
    val rightResolution: Dimensions?,
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

    fun listPairs(sessionKey: StereoCalibrationSessionKey): List<StereoBoardPairRecord> =
        listPairs().filter { record ->
            sessionKey.matches(
                leftPhysicalCameraId = record.leftPhysicalCameraId,
                rightPhysicalCameraId = record.rightPhysicalCameraId,
                leftResolution = record.leftResolution,
                rightResolution = record.rightResolution
            )
        }

    fun count(sessionKey: StereoCalibrationSessionKey): Int = listPairs(sessionKey).size

    fun clear(sessionKey: StereoCalibrationSessionKey) {
        listPairs(sessionKey).forEach { record ->
            record.directory.deleteRecursively()
        }
    }

    fun clear() {
        baseDirectory.listFiles()?.forEach { file ->
            file.deleteRecursively()
        }
    }

    fun savePair(
        leftFrame: StereoFrameSnapshot,
        rightFrame: StereoFrameSnapshot,
        sessionKey: StereoCalibrationSessionKey
    ): Result<StereoBoardPairRecord> = runCatching {
        require(
            sessionKey.leftPhysicalCameraId.isNotBlank() &&
                sessionKey.rightPhysicalCameraId.isNotBlank()
        ) {
            "Physical camera IDs are required for a calibration pair"
        }
        val leftResolution = Dimensions(leftFrame.width, leftFrame.height)
        val rightResolution = Dimensions(rightFrame.width, rightFrame.height)
        require(
            sessionKey.matches(
                leftPhysicalCameraId = sessionKey.leftPhysicalCameraId,
                rightPhysicalCameraId = sessionKey.rightPhysicalCameraId,
                leftResolution = leftResolution,
                rightResolution = rightResolution
            )
        ) {
            "Captured frame sizes $leftResolution and $rightResolution do not match " +
                "the ${sessionKey.resolution} calibration session"
        }
        val leftDetection = detectFromFrame(leftFrame)
        val rightDetection = try {
            detectFromFrame(rightFrame)
        } catch (error: Exception) {
            leftDetection.releaseCorrespondences()
            throw error
        }
        try {
            val leftCount = leftDetection.charucoCornerCount
            val rightCount = rightDetection.charucoCornerCount
            if (leftCount < AcceptanceConfig.MIN_CHARUCO_CORNERS ||
                rightCount < AcceptanceConfig.MIN_CHARUCO_CORNERS
            ) {
                error(
                    "Board rejected: left $leftCount/${AcceptanceConfig.MIN_CHARUCO_CORNERS}, " +
                        "right $rightCount/${AcceptanceConfig.MIN_CHARUCO_CORNERS} corners"
                )
            }

            val index = (listPairs().maxOfOrNull { it.index } ?: 0) + 1
            val directory = File(baseDirectory, "pair_$index")
            check(directory.mkdirs() || directory.isDirectory) {
                "Failed to create calibration pair directory"
            }

            val leftFile = File(directory, "left.jpg")
            val rightFile = File(directory, "right.jpg")
            val cornersFile = File(directory, "corners.json")
            StereoFrameJpeg.write(leftFile, leftFrame)
            StereoFrameJpeg.write(rightFile, rightFrame)

            val timestampDeltaNs = StereoTimestampUtils.deltaNs(
                leftFrame.sensorTimestampNs,
                rightFrame.sensorTimestampNs
            )
            cornersFile.writeText(
                buildCornersJson(
                    leftDetection = leftDetection,
                    rightDetection = rightDetection,
                    sessionKey = sessionKey,
                    leftResolution = leftResolution,
                    rightResolution = rightResolution,
                    timestampDeltaNs = timestampDeltaNs
                ).toString(2)
            )

            StereoBoardPairRecord(
                directory = directory,
                index = index,
                leftImageFile = leftFile,
                rightImageFile = rightFile,
                cornersFile = cornersFile,
                leftPhysicalCameraId = sessionKey.leftPhysicalCameraId,
                rightPhysicalCameraId = sessionKey.rightPhysicalCameraId,
                leftResolution = leftResolution,
                rightResolution = rightResolution,
                leftCornerCount = leftCount,
                rightCornerCount = rightCount,
                timestampDeltaNs = timestampDeltaNs
            )
        } finally {
            leftDetection.releaseCorrespondences()
            rightDetection.releaseCorrespondences()
        }
    }

    private fun detectFromFrame(frame: StereoFrameSnapshot): DetectionResult {
        val gray = YuvConversions.nv21ToGray(frame.nv21, frame.width, frame.height)
        return try {
            frameDetector.detect(gray)
        } finally {
            gray.release()
        }
    }

    private fun buildCornersJson(
        leftDetection: DetectionResult,
        rightDetection: DetectionResult,
        sessionKey: StereoCalibrationSessionKey,
        leftResolution: Dimensions,
        rightResolution: Dimensions,
        timestampDeltaNs: Long
    ): JSONObject = JSONObject().apply {
        put("timestamp_delta_ns", timestampDeltaNs)
        put("left_physical_camera_id", sessionKey.leftPhysicalCameraId)
        put("right_physical_camera_id", sessionKey.rightPhysicalCameraId)
        put("left_resolution", leftResolution.toJsonArray())
        put("right_resolution", rightResolution.toJsonArray())
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
            leftPhysicalCameraId = corners.optString("left_physical_camera_id"),
            rightPhysicalCameraId = corners.optString("right_physical_camera_id"),
            leftResolution = corners.optJSONArray("left_resolution")?.toDimensions(),
            rightResolution = corners.optJSONArray("right_resolution")?.toDimensions(),
            leftCornerCount = corners.getJSONObject("left").getInt("corner_count"),
            rightCornerCount = corners.getJSONObject("right").getInt("corner_count"),
            timestampDeltaNs = corners.optLong("timestamp_delta_ns")
        )
    }.getOrNull()

    private fun Dimensions.toJsonArray(): JSONArray = JSONArray().put(width).put(height)

    private fun JSONArray.toDimensions(): Dimensions =
        Dimensions(width = getInt(0), height = getInt(1))
}
