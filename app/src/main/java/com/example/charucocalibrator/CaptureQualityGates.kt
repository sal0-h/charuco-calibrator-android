package com.example.charucocalibrator

object CaptureQualityGates {
    fun evaluate(
        metadata: FrameMetadata?,
        stability: CaptureStabilityState,
        requireMetadata: Boolean = AcceptanceConfig.REQUIRE_CAPTURE_METADATA
    ): String? {
        if (metadata == null) {
            return if (requireMetadata) "capture_metadata_missing" else null
        }

        stability.message?.let { return it }

        metadata.isoSensitivity?.let { iso ->
            if (iso > AcceptanceConfig.MAX_ISO) {
                return "too_dark_high_iso"
            }
        }

        metadata.exposureTimeNs?.let { exposureNs ->
            if (exposureNs > AcceptanceConfig.MAX_EXPOSURE_TIME_NS) {
                return "exposure_too_long"
            }
        }

        return null
    }
}
