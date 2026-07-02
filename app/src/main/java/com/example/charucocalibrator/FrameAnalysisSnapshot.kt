package com.example.charucocalibrator

data class FrameAnalysisSnapshot(
    val rawFrameCount: Long,
    val processedFrameCount: Long,
    val sharpness: Double?,
    val processingFps: Double,
    val markerCount: Int = 0,
    val charucoCornerCount: Int = 0,
    val detectionStatus: String = "not_run",
    val rejectionReason: String? = null,
    val bboxAreaRatio: Double? = null,
    val acceptedFrameCount: Int = 0,
    val maxAcceptedFrames: Int = AcceptanceConfig.MAX_ACCEPTED_FRAMES,
    val lastAcceptanceReason: String? = null,
    val autoCaptureActive: Boolean = false,
    val calibrationStatus: String? = null,
    val calibrationReprojectionError: Double? = null,
    val calibrationFx: Double? = null,
    val calibrationFy: Double? = null,
    val calibrationCx: Double? = null,
    val calibrationCy: Double? = null,
    val calibrationOutputPath: String? = null
)
