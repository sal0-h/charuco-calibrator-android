package com.example.charucocalibrator.stereo

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import com.example.charucocalibrator.Dimensions
import com.example.charucocalibrator.FrameMetadata
import com.example.charucocalibrator.ORIENTATION_NOTE
import com.example.charucocalibrator.stereo.model.StereoPairMetadata
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

object StereoPairExporter {
    private const val JPEG_QUALITY = 95
    private const val JSON_INDENT_SPACES = 2

    data class ExportResult(
        val directory: File,
        val metadata: StereoPairMetadata
    )

    fun export(
        context: Context,
        leftFrame: StereoFrameSnapshot,
        rightFrame: StereoFrameSnapshot,
        logicalCameraId: String,
        leftPhysicalCameraId: String,
        rightPhysicalCameraId: String,
        pairLabel: String,
        oisDisabled: Boolean,
        afPolicy: String?,
        notes: String? = null
    ): Result<ExportResult> = runCatching {
        val delta = StereoTimestampUtils.deltaNs(
            leftFrame.sensorTimestampNs,
            rightFrame.sensorTimestampNs
        )
        check(StereoTimestampUtils.isSaveable(delta)) {
            "timestamp_delta_ns ${delta}ns exceeds save limit"
        }

        val baseDir = checkNotNull(context.getExternalFilesDir(null)) {
            "App-specific external files directory is unavailable"
        }
        val directory = File(baseDir, "stereo_pairs/stereo_pair_${System.currentTimeMillis()}")
        check(directory.mkdirs() || directory.isDirectory) {
            "Failed to create stereo pair directory"
        }

        val leftFile = File(directory, "left.jpg")
        val rightFile = File(directory, "right.jpg")
        writeJpeg(leftFile, leftFrame)
        writeJpeg(rightFile, rightFrame)

        val metadata = StereoPairMetadata(
            logicalCameraId = logicalCameraId,
            leftPhysicalCameraId = leftPhysicalCameraId,
            rightPhysicalCameraId = rightPhysicalCameraId,
            pairLabel = pairLabel,
            leftTimestampNs = leftFrame.sensorTimestampNs,
            rightTimestampNs = rightFrame.sensorTimestampNs,
            timestampDeltaNs = delta,
            leftResolution = Dimensions(leftFrame.width, leftFrame.height),
            rightResolution = Dimensions(rightFrame.width, rightFrame.height),
            leftFocalLengthMm = leftFrame.metadata?.focalLengthMm?.toDouble(),
            rightFocalLengthMm = rightFrame.metadata?.focalLengthMm?.toDouble(),
            leftIso = leftFrame.metadata?.isoSensitivity,
            rightIso = rightFrame.metadata?.isoSensitivity,
            leftExposureTimeNs = leftFrame.metadata?.exposureTimeNs,
            rightExposureTimeNs = rightFrame.metadata?.exposureTimeNs,
            leftLensFocusDistance = leftFrame.metadata?.lensFocusDistance,
            rightLensFocusDistance = rightFrame.metadata?.lensFocusDistance,
            leftAfState = leftFrame.metadata?.afState,
            rightAfState = rightFrame.metadata?.afState,
            oisDisabled = oisDisabled,
            orientationNote = ORIENTATION_NOTE,
            notes = notes,
            afPolicy = afPolicy
        )

        File(directory, "metadata.json").writeText(
            metadata.toJson().toString(JSON_INDENT_SPACES)
        )

        ExportResult(directory = directory, metadata = metadata)
    }

    private fun writeJpeg(file: File, frame: StereoFrameSnapshot) {
        FileOutputStream(file).use { output ->
            val compressed = YuvImage(
                frame.nv21,
                ImageFormat.NV21,
                frame.width,
                frame.height,
                null
            ).compressToJpeg(Rect(0, 0, frame.width, frame.height), JPEG_QUALITY, output)
            check(compressed) { "JPEG compression failed" }
        }
    }
}
