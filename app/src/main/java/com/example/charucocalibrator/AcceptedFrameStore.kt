package com.example.charucocalibrator

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import org.json.JSONObject
import org.opencv.core.Mat
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

data class AcceptedFrameRecord(
    val imageFile: File,
    val metadataFile: File,
    val imageWidth: Int,
    val imageHeight: Int,
    val charucoCorners: Mat,
    val charucoIds: Mat,
    val markerCount: Int,
    val charucoCornerCount: Int,
    val sharpness: Double,
    val bbox: DetectionBoundingBox
)

class AcceptedFrameStore(
    private val context: Context
) {
    private val lock = Any()
    private val records = mutableListOf<AcceptedFrameRecord>()

    val frames: List<AcceptedFrameRecord>
        get() = synchronized(lock) { records.toList() }

    val count: Int
        get() = synchronized(lock) { records.size }

    fun saveFrame(
        gray: Mat,
        cameraId: String,
        sharpness: Double,
        detection: DetectionResult,
        sensorTimestampNs: Long?,
        reason: String
    ): AcceptedFrameRecord? {
        if (count >= AcceptanceConfig.MAX_ACCEPTED_FRAMES) {
            Log.w(TAG, "Accepted frame limit reached")
            return null
        }

        val bbox = detection.bbox ?: return null
        val corners = detection.charucoCorners ?: return null
        val ids = detection.charucoIds ?: return null

        val directory = File(
            checkNotNull(context.getExternalFilesDir(null)) {
                "App-specific external files directory is unavailable"
            },
            ACCEPTED_FRAMES_DIR
        ).apply { mkdirs() }

        val baseName = "accepted_${System.currentTimeMillis()}"
        val imageFile = File(directory, "$baseName.jpg")
        val metadataFile = File(directory, "$baseName.json")

        return try {
            val width = gray.cols()
            val height = gray.rows()
            val nv21 = grayMatToNv21(gray)
            FileOutputStream(imageFile).use { output ->
                val compressed = YuvImage(
                    nv21,
                    ImageFormat.NV21,
                    width,
                    height,
                    null
                ).compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, output)
                check(compressed) { "JPEG compression failed" }
            }

            metadataFile.writeText(
                JSONObject().apply {
                    put("camera_id", cameraId)
                    put("image_width", width)
                    put("image_height", height)
                    put("timestamp", Instant.now().toString())
                    put("sensor_timestamp_ns", sensorTimestampNs ?: JSONObject.NULL)
                    put("marker_count", detection.markerCount)
                    put("charuco_corner_count", detection.charucoCornerCount)
                    put("sharpness", sharpness)
                    put("bbox_left", bbox.left)
                    put("bbox_top", bbox.top)
                    put("bbox_right", bbox.right)
                    put("bbox_bottom", bbox.bottom)
                    put("bbox_area_ratio", bbox.areaRatio)
                    put("acceptance_reason", reason)
                    put("orientation_note", ORIENTATION_NOTE)
                }.toString(JSON_INDENT_SPACES)
            )

            val record = AcceptedFrameRecord(
                imageFile = imageFile,
                metadataFile = metadataFile,
                imageWidth = width,
                imageHeight = height,
                charucoCorners = corners.clone(),
                charucoIds = ids.clone(),
                markerCount = detection.markerCount,
                charucoCornerCount = detection.charucoCornerCount,
                sharpness = sharpness,
                bbox = bbox
            )
            synchronized(lock) {
                records += record
            }
            corners.release()
            ids.release()
            record
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to save accepted frame", exception)
            null
        }
    }

    fun clear() {
        synchronized(lock) {
            records.forEach { record ->
                record.charucoCorners.release()
                record.charucoIds.release()
            }
            records.clear()
        }
    }

    private fun grayMatToNv21(gray: Mat): ByteArray {
        val width = gray.cols()
        val height = gray.rows()
        val ySize = width * height
        val nv21 = ByteArray(ySize + ySize / 2)
        val row = ByteArray(width)
        for (y in 0 until height) {
            gray.get(y, 0, row)
            System.arraycopy(row, 0, nv21, y * width, width)
        }
        val neutralChrominance = 128.toByte()
        for (index in ySize until nv21.size) {
            nv21[index] = neutralChrominance
        }
        return nv21
    }

    companion object {
        const val ACCEPTED_FRAMES_DIR = "accepted_frames"
        private const val JPEG_QUALITY = 95
        private const val JSON_INDENT_SPACES = 2
        private const val TAG = "AcceptedFrameStore"
    }
}
