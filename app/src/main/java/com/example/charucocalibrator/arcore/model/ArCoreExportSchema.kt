package com.example.charucocalibrator.arcore.model

import com.example.charucocalibrator.DeviceInfo
import org.json.JSONObject
import org.json.JSONArray

data class ExportIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("fx", fx.toDouble())
        put("fy", fy.toDouble())
        put("cx", cx.toDouble())
        put("cy", cy.toDouble())
        put("width", width)
        put("height", height)
    }
}

data class ExportDepthSection(
    val available: Boolean,
    val width: Int = 0,
    val height: Int = 0,
    val imageTimestampNs: Long = 0L,
    val matchesFrameTimestamp: Boolean = false,
    val validPixelFraction: Float = 0f,
    val minDepthM: Float = 0f,
    val medianDepthM: Float = 0f,
    val maxDepthM: Float = 0f,
    val scaleLowM: Float = 0f,
    val scaleHighM: Float = 0f,
    val binPath: String = "",
    val pngPath: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("available", available)
        put("width", width)
        put("height", height)
        put("image_timestamp_ns", imageTimestampNs)
        put("matches_frame_timestamp", matchesFrameTimestamp)
        if (available) {
            put("valid_pixel_fraction", validPixelFraction.toDouble())
            put("min_depth_m", minDepthM.toDouble())
            put("median_depth_m", medianDepthM.toDouble())
            put("max_depth_m", maxDepthM.toDouble())
            put("scale_low_m", scaleLowM.toDouble())
            put("scale_high_m", scaleHighM.toDouble())
        } else {
            put("valid_pixel_fraction", 0)
            put("min_depth_m", 0)
            put("median_depth_m", 0)
            put("max_depth_m", 0)
            put("scale_low_m", 0)
            put("scale_high_m", 0)
        }
        put("bin_path", binPath)
        put("png_path", pngPath)
    }
}

data class ExportSmoothedDepthSection(
    val available: Boolean,
    val width: Int = 0,
    val height: Int = 0,
    val imageTimestampNs: Long = 0L,
    val matchesFrameTimestamp: Boolean = false,
    val validPixelFraction: Float = 0f,
    val minDepthM: Float = 0f,
    val medianDepthM: Float = 0f,
    val maxDepthM: Float = 0f,
    val scaleLowM: Float = 0f,
    val scaleHighM: Float = 0f,
    val binPath: String = "",
    val pngPath: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("available", available)
        put("width", width)
        put("height", height)
        put("image_timestamp_ns", imageTimestampNs)
        put("matches_frame_timestamp", matchesFrameTimestamp)
        put("valid_pixel_fraction", validPixelFraction.toDouble())
        put("min_depth_m", minDepthM.toDouble())
        put("median_depth_m", medianDepthM.toDouble())
        put("max_depth_m", maxDepthM.toDouble())
        put("scale_low_m", scaleLowM.toDouble())
        put("scale_high_m", scaleHighM.toDouble())
        put("bin_path", binPath)
        put("png_path", pngPath)
    }
}

data class ExportConfidenceSection(
    val available: Boolean,
    val width: Int = 0,
    val height: Int = 0,
    val imageTimestampNs: Long = 0L,
    val matchesFrameTimestamp: Boolean = false,
    val meanConfidence: Float = 0f,
    val highConfidenceFraction: Float = 0f,
    val pngPath: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("available", available)
        put("width", width)
        put("height", height)
        put("image_timestamp_ns", imageTimestampNs)
        put("matches_frame_timestamp", matchesFrameTimestamp)
        put("mean_confidence", meanConfidence.toDouble())
        put("high_confidence_fraction", highConfidenceFraction.toDouble())
        put("png_path", pngPath)
    }
}

data class ExportOverlayEvidenceSection(
    val available: Boolean,
    val mode: String = "Off",
    val source: String = "Smoothed",
    val opacity: Float = 0f,
    val confidenceThreshold: Int = 0,
    val viewportWidth: Int = 0,
    val viewportHeight: Int = 0,
    val displayRotation: Int = 0,
    val openglNdcToDepthTextureUv: List<Float> = emptyList(),
    val previewPngPath: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("available", available)
        put("mode", mode)
        put("source", source)
        put("opacity", opacity.toDouble())
        put("confidence_threshold", confidenceThreshold)
        put("viewport_width", viewportWidth)
        put("viewport_height", viewportHeight)
        put("display_rotation", displayRotation)
        put("opengl_ndc_to_depth_texture_uv", JSONArray().apply {
            openglNdcToDepthTextureUv.forEach { put(it.toDouble()) }
        })
        put("preview_png_path", previewPngPath)
    }
}

data class ExportCharucoIntrinsicsDiff(
    val available: Boolean,
    val charucoFx: Double? = null,
    val charucoFy: Double? = null,
    val charucoCx: Double? = null,
    val charucoCy: Double? = null,
    val charucoImageWidth: Int? = null,
    val charucoImageHeight: Int? = null,
    val comparisonWidth: Int? = null,
    val comparisonHeight: Int? = null,
    val charucoScaledFx: Double? = null,
    val charucoScaledFy: Double? = null,
    val charucoScaledCx: Double? = null,
    val charucoScaledCy: Double? = null,
    val charucoTransposedToLandscape: Boolean = false,
    val deltaFx: Double? = null,
    val deltaFy: Double? = null,
    val deltaCx: Double? = null,
    val deltaCy: Double? = null,
    val deltaFxPercent: Double? = null,
    val deltaFyPercent: Double? = null,
    val dimensionMismatchWarning: Boolean = false,
    val comparisonNote: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("available", available)
        put("charuco_fx", charucoFx ?: JSONObject.NULL)
        put("charuco_fy", charucoFy ?: JSONObject.NULL)
        put("charuco_cx", charucoCx ?: JSONObject.NULL)
        put("charuco_cy", charucoCy ?: JSONObject.NULL)
        put("charuco_image_width", charucoImageWidth ?: JSONObject.NULL)
        put("charuco_image_height", charucoImageHeight ?: JSONObject.NULL)
        put("comparison_width", comparisonWidth ?: JSONObject.NULL)
        put("comparison_height", comparisonHeight ?: JSONObject.NULL)
        put("charuco_scaled_fx", charucoScaledFx ?: JSONObject.NULL)
        put("charuco_scaled_fy", charucoScaledFy ?: JSONObject.NULL)
        put("charuco_scaled_cx", charucoScaledCx ?: JSONObject.NULL)
        put("charuco_scaled_cy", charucoScaledCy ?: JSONObject.NULL)
        put("charuco_transposed_to_landscape", charucoTransposedToLandscape)
        put("delta_fx", deltaFx ?: JSONObject.NULL)
        put("delta_fy", deltaFy ?: JSONObject.NULL)
        put("delta_cx", deltaCx ?: JSONObject.NULL)
        put("delta_cy", deltaCy ?: JSONObject.NULL)
        put("delta_fx_percent", deltaFxPercent ?: JSONObject.NULL)
        put("delta_fy_percent", deltaFyPercent ?: JSONObject.NULL)
        put("dimension_mismatch_warning", dimensionMismatchWarning)
        put("comparison_note", comparisonNote)
    }
}

data class ArCoreSnapshotExport(
    val source: String = "arcore_explorer",
    val deviceHint: String = DeviceInfo.describe(),
    val streamEquivalenceWarning: String =
        "ARCore stream is not assumed equivalent to Camera2 camera_id 0 4000x3000 ChArUco stream.",
    val camera2TargetCameraIdNote: String =
        "ChArUco tool uses Camera2 camera_id 0 at 4000x3000; ARCore session is separate and not proven to use the same physical camera.",
    val overlayAlignmentNote: String =
        "Live depth overlay is rendered in the GLES preview using ARCore OpenGL-NDC to TEXTURE_NORMALIZED depth coordinate transforms. Exported depth PNGs remain raw depth-image artifacts; overlay_evidence preview PNG captures the rendered result.",
    val timestampNs: Long,
    val androidCameraTimestampNs: Long,
    val trackingState: String,
    val trackingFailureReason: String,
    val imageIntrinsics: ExportIntrinsics,
    val textureIntrinsics: ExportIntrinsics,
    val rawDepth: ExportDepthSection,
    val smoothedDepth: ExportSmoothedDepthSection,
    val confidence: ExportConfidenceSection,
    val overlayEvidence: ExportOverlayEvidenceSection,
    val charucoIntrinsicsDiff: ExportCharucoIntrinsicsDiff,
    val jsonFileName: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("source", source)
        put("device_hint", deviceHint)
        put("stream_equivalence_warning", streamEquivalenceWarning)
        put("camera2_target_camera_id_note", camera2TargetCameraIdNote)
        put("overlay_alignment_note", overlayAlignmentNote)
        put("timestamp_ns", timestampNs)
        put("android_camera_timestamp_ns", androidCameraTimestampNs)
        put("tracking_state", trackingState)
        put("tracking_failure_reason", trackingFailureReason)
        put("image_intrinsics", imageIntrinsics.toJson())
        put("texture_intrinsics", textureIntrinsics.toJson())
        put("raw_depth", rawDepth.toJson())
        put("smoothed_depth", smoothedDepth.toJson())
        put("confidence", confidence.toJson())
        put("overlay_evidence", overlayEvidence.toJson())
        put("charuco_intrinsics_diff", charucoIntrinsicsDiff.toJson())
    }
}
