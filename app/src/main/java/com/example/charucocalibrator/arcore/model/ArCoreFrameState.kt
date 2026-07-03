package com.example.charucocalibrator.arcore.model

data class CameraIntrinsicsSnapshot(
    val fx: Float = 0f,
    val fy: Float = 0f,
    val cx: Float = 0f,
    val cy: Float = 0f,
    val width: Int = 0,
    val height: Int = 0,
)

data class DepthStats(
    val width: Int = 0,
    val height: Int = 0,
    val validPixelFraction: Float = 0f,
    val minDepthM: Float = 0f,
    val medianDepthM: Float = 0f,
    val maxDepthM: Float = 0f,
)

data class ConfidenceStats(
    val width: Int = 0,
    val height: Int = 0,
    val meanConfidence: Float = 0f,
    val highConfidenceFraction: Float = 0f,
)

data class DepthImageData(
    val width: Int,
    val height: Int,
    val depthMm: ShortArray,
    val stats: DepthStats,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DepthImageData
        return width == other.width &&
            height == other.height &&
            depthMm.contentEquals(other.depthMm) &&
            stats == other.stats
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + depthMm.contentHashCode()
        result = 31 * result + stats.hashCode()
        return result
    }
}

data class ConfidenceImageData(
    val width: Int,
    val height: Int,
    val confidence: ByteArray,
    val stats: ConfidenceStats,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ConfidenceImageData
        return width == other.width &&
            height == other.height &&
            confidence.contentEquals(other.confidence) &&
            stats == other.stats
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + confidence.contentHashCode()
        result = 31 * result + stats.hashCode()
        return result
    }
}

data class ArCoreFrameState(
    val timestampNs: Long = 0L,
    val androidCameraTimestampNs: Long = 0L,
    val trackingState: String = "UNKNOWN",
    val trackingFailureReason: String = "NONE",
    val imageIntrinsics: CameraIntrinsicsSnapshot = CameraIntrinsicsSnapshot(),
    val textureIntrinsics: CameraIntrinsicsSnapshot = CameraIntrinsicsSnapshot(),
    val rawDepth: DepthImageData? = null,
    val smoothedDepth: DepthImageData? = null,
    val confidence: ConfidenceImageData? = null,
    val depthModeLabel: String = "DISABLED",
    val sessionRunning: Boolean = false,
) {
    fun deepCopyForExport(): ArCoreFrameState = copy(
        rawDepth = rawDepth?.copy(depthMm = rawDepth.depthMm.copyOf()),
        smoothedDepth = smoothedDepth?.copy(depthMm = smoothedDepth.depthMm.copyOf()),
        confidence = confidence?.copy(confidence = confidence.confidence.copyOf()),
    )
}

enum class DepthOverlayMode {
    Off,
    RawDepthHeatmap,
    Confidence,
    DepthMaskedByConfidence,
}

enum class DepthSourceToggle {
    Raw,
    Smoothed,
}
