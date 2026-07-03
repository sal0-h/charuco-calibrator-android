package com.example.charucocalibrator

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.time.Instant

data class AcceptedFrameRecord(
    val imageFile: File,
    val metadataFile: File,
    val imageWidth: Int,
    val imageHeight: Int,
    val charucoCorners: Mat,
    val charucoIds: Mat,
    val markerCorners: List<Mat>,
    val markerIds: Mat,
    val markerCount: Int,
    val charucoCornerCount: Int,
    val sharpness: Double,
    val bbox: DetectionBoundingBox
) {
    fun release() {
        charucoCorners.release()
        charucoIds.release()
        markerCorners.forEach(Mat::release)
        markerIds.release()
    }
}

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
        captureMetadata: FrameMetadata? = null,
        reason: String
    ): AcceptedFrameRecord? {
        if (count >= AcceptanceConfig.MAX_ACCEPTED_FRAMES) {
            Log.w(TAG, "Accepted frame limit reached")
            return null
        }

        val bbox = detection.bbox ?: return null
        val corners = detection.charucoCorners ?: return null
        val ids = detection.charucoIds ?: return null
        val markers = detection.markerCorners ?: return null
        val markerIds = detection.markerIds ?: return null

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
            check(Imgcodecs.imwrite(imageFile.absolutePath, gray)) {
                "OpenCV JPEG write failed for ${imageFile.name}"
            }

            val metadata = JSONObject().apply {
                put("camera_id", cameraId)
                put("image_width", width)
                put("image_height", height)
                put("timestamp", Instant.now().toString())
                put("sensor_timestamp_ns", sensorTimestampNs ?: JSONObject.NULL)
                captureMetadata?.appendToJson(this)
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
            }
            CharucoCornerPersistence.appendToMetadata(metadata, corners, ids)
            metadataFile.writeText(metadata.toString(JSON_INDENT_SPACES))

            val record = AcceptedFrameRecord(
                imageFile = imageFile,
                metadataFile = metadataFile,
                imageWidth = width,
                imageHeight = height,
                charucoCorners = corners.clone(),
                charucoIds = ids.clone(),
                markerCorners = markers.map { it.clone() },
                markerIds = markerIds.clone(),
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
            markers.forEach(Mat::release)
            markerIds.release()
            record
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to save accepted frame", exception)
            null
        }
    }

    fun framesForCalibration(): List<AcceptedFrameRecord> {
        val inMemory = frames
        if (inMemory.isNotEmpty()) return inMemory
        return loadPersistedFrames()
    }

    fun loadPersistedFrames(): List<AcceptedFrameRecord> {
        val directory = File(
            context.getExternalFilesDir(null) ?: return emptyList(),
            ACCEPTED_FRAMES_DIR
        )
        if (!directory.isDirectory) return emptyList()

        return directory.listFiles { file -> file.extension == "json" }
            ?.sortedBy { it.name }
            ?.mapNotNull(::loadPersistedFrame)
            ?: emptyList()
    }

    private fun loadPersistedFrame(metadataFile: File): AcceptedFrameRecord? {
        val imageFile = File(metadataFile.parentFile, metadataFile.nameWithoutExtension + ".jpg")
        if (!imageFile.isFile) return null

        return runCatching {
            val metadata = JSONObject(metadataFile.readText())
            val persisted = CharucoCornerPersistence.readFromMetadata(metadata)
                ?: return null
            val bbox = DetectionBoundingBox(
                left = metadata.getInt("bbox_left"),
                top = metadata.getInt("bbox_top"),
                right = metadata.getInt("bbox_right"),
                bottom = metadata.getInt("bbox_bottom"),
                areaRatio = metadata.getDouble("bbox_area_ratio")
            )
            val cornersMat = OpenCvMatAccess.toImagePointsMat(persisted.imagePoints.toList()) ?: return null
            val idsMat = Mat(persisted.ids.size, 1, org.opencv.core.CvType.CV_32SC1)
            for (index in persisted.ids.indices) {
                idsMat.put(index, 0, intArrayOf(persisted.ids[index]))
            }
            AcceptedFrameRecord(
                imageFile = imageFile,
                metadataFile = metadataFile,
                imageWidth = metadata.getInt("image_width"),
                imageHeight = metadata.getInt("image_height"),
                charucoCorners = cornersMat,
                charucoIds = idsMat,
                markerCorners = emptyList(),
                markerIds = Mat(),
                markerCount = metadata.optInt("marker_count"),
                charucoCornerCount = metadata.optInt("charuco_corner_count", persisted.ids.size),
                sharpness = metadata.optDouble("sharpness"),
                bbox = bbox
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to load persisted frame ${metadataFile.name}", error)
        }.getOrNull()
    }

    fun clear() {
        synchronized(lock) {
            records.forEach(AcceptedFrameRecord::release)
            records.clear()
        }
    }

    companion object {
        const val ACCEPTED_FRAMES_DIR = "accepted_frames"
        private const val JSON_INDENT_SPACES = 2
        private const val TAG = "AcceptedFrameStore"
    }
}
