package com.example.charucocalibrator

data class AcceptanceDecision(
    val accepted: Boolean,
    val message: String
)

class FrameAcceptanceController {
    private val acceptedPoses = mutableListOf<BoardPoseFingerprint>()
    private val poseCoverage = PoseCoverageTracker()
    private var lastAcceptedAtMs = 0L

    @Volatile
    var lastDecisionMessage: String? = null
        private set

    fun evaluate(
        detection: DetectionResult,
        sharpness: Double,
        frameWidth: Int,
        frameHeight: Int,
        acceptedCount: Int,
        captureMetadata: FrameMetadata? = null,
        captureStability: CaptureStabilityState? = null,
        autoCaptureActive: Boolean = false
    ): AcceptanceDecision {
        if (acceptedCount >= AcceptanceConfig.MAX_ACCEPTED_FRAMES) {
            lastDecisionMessage = "rejected: max_accepted_frames_reached"
            return AcceptanceDecision(false, lastDecisionMessage!!)
        }

        if (detection.status != "detected" || detection.bbox == null) {
            lastDecisionMessage = detection.rejectionReason ?: "board_not_detected"
            return AcceptanceDecision(false, lastDecisionMessage!!)
        }

        if (autoCaptureActive) {
            val stability = captureStability
                ?: CaptureStabilityState(CaptureStabilityStatus.METADATA_MISSING)
            CaptureQualityGates.evaluate(captureMetadata, stability)?.let { gateReason ->
                lastDecisionMessage = "rejected: $gateReason"
                return AcceptanceDecision(false, lastDecisionMessage!!)
            }
        }

        if (detection.charucoCornerCount < AcceptanceConfig.MIN_CHARUCO_CORNERS) {
            lastDecisionMessage =
                "rejected: corners=${detection.charucoCornerCount} < ${AcceptanceConfig.MIN_CHARUCO_CORNERS}"
            return AcceptanceDecision(false, lastDecisionMessage!!)
        }

        if (sharpness <= AcceptanceConfig.SHARPNESS_THRESHOLD) {
            lastDecisionMessage =
                "rejected: sharpness=${"%.1f".format(sharpness)} <= ${AcceptanceConfig.SHARPNESS_THRESHOLD}"
            return AcceptanceDecision(false, lastDecisionMessage!!)
        }

        val bbox = detection.bbox
        if (bbox.areaRatio < AcceptanceConfig.MIN_BBOX_AREA_RATIO) {
            lastDecisionMessage =
                "rejected: bbox_area_ratio=${"%.3f".format(bbox.areaRatio)} < ${AcceptanceConfig.MIN_BBOX_AREA_RATIO}"
            return AcceptanceDecision(false, lastDecisionMessage!!)
        }

        val now = System.currentTimeMillis()
        if (now - lastAcceptedAtMs < AcceptanceConfig.MIN_TIME_BETWEEN_ACCEPTED_MS) {
            lastDecisionMessage = "rejected: too_soon_after_previous_accept"
            return AcceptanceDecision(false, lastDecisionMessage!!)
        }

        val fingerprint = BoardPoseFingerprint(
            centerXRatio = bbox.centerX / frameWidth,
            centerYRatio = bbox.centerY / frameHeight,
            areaRatio = bbox.areaRatio,
            aspectRatio = bbox.aspectRatio,
            cornerCount = detection.charucoCornerCount
        )

        if (!poseCoverage.shouldAccept(fingerprint.centerXRatio, fingerprint.centerYRatio)) {
            lastDecisionMessage = "rejected: pose_grid_already_covered"
            return AcceptanceDecision(false, lastDecisionMessage!!)
        }

        acceptedPoses.forEach { previous ->
            if (PoseSimilarity.isTooSimilar(fingerprint, previous)) {
                lastDecisionMessage = "rejected: too_similar_to_accepted_pose"
                return AcceptanceDecision(false, lastDecisionMessage!!)
            }
        }

        lastAcceptedAtMs = now
        acceptedPoses += fingerprint
        poseCoverage.recordAccept(fingerprint.centerXRatio, fingerprint.centerYRatio)
        lastDecisionMessage =
            "accepted: corners=${detection.charucoCornerCount}, sharpness=${"%.1f".format(sharpness)}, coverage=${"%.3f".format(bbox.areaRatio)}"
        return AcceptanceDecision(true, lastDecisionMessage!!)
    }

    fun clearHistory() {
        acceptedPoses.clear()
        poseCoverage.clear()
        lastAcceptedAtMs = 0L
        lastDecisionMessage = null
    }

    fun markAutoCaptureStarted() {
        lastDecisionMessage = "auto_capture_started"
    }
}

private val DetectionBoundingBox.centerX: Double
    get() = (left + right) / 2.0

private val DetectionBoundingBox.centerY: Double
    get() = (top + bottom) / 2.0

private val DetectionBoundingBox.aspectRatio: Double
    get() {
        val height = bottom - top
        return if (height == 0) 0.0 else (right - left).toDouble() / height
    }
