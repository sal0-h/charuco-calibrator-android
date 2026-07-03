package com.example.charucocalibrator

import android.hardware.camera2.CaptureResult
import kotlin.math.abs

enum class FocusPolicy {
    METADATA_GATE_ONLY,
    AF_TRIGGER_THEN_REJECT_DRIFT,
    MANUAL_FIXED_FOCUS
}

enum class CaptureStabilityStatus {
    WARMING_UP,
    STABLE,
    FOCUS_UNSTABLE,
    METADATA_MISSING
}

data class CaptureStabilityState(
    val status: CaptureStabilityStatus,
    val referenceFocusDistance: Float? = null,
    val message: String? = null
)

class CaptureStabilityController(
    private val focusPolicy: FocusPolicy = FocusPolicy.AF_TRIGGER_THEN_REJECT_DRIFT,
    private val warmupDurationMs: Long = CALIBRATION_LOCK_DELAY_MS
) {
    private var autoCaptureActive = false
    private var autoCaptureStartedAtMs = 0L
    private var referenceFocusDistance: Float? = null
    private val recentFocusDistances = ArrayDeque<Float>()

    fun onAutoCaptureStarted(nowMs: Long = System.currentTimeMillis()) {
        autoCaptureActive = true
        autoCaptureStartedAtMs = nowMs
        referenceFocusDistance = null
        recentFocusDistances.clear()
    }

    fun onAutoCaptureStopped() {
        autoCaptureActive = false
        referenceFocusDistance = null
        recentFocusDistances.clear()
    }

    fun evaluate(metadata: FrameMetadata?, nowMs: Long = System.currentTimeMillis()): CaptureStabilityState {
        if (!autoCaptureActive) {
            return CaptureStabilityState(CaptureStabilityStatus.STABLE)
        }

        if (metadata == null) {
            return CaptureStabilityState(
                status = CaptureStabilityStatus.METADATA_MISSING,
                message = "capture_metadata_missing"
            )
        }

        if (nowMs - autoCaptureStartedAtMs < warmupDurationMs) {
            trackFocus(metadata)
            return CaptureStabilityState(
                status = CaptureStabilityStatus.WARMING_UP,
                referenceFocusDistance = referenceFocusDistance
            )
        }

        when (focusPolicy) {
            FocusPolicy.METADATA_GATE_ONLY -> {
                if (isAfHunting(metadata.afState)) {
                    return CaptureStabilityState(
                        status = CaptureStabilityStatus.FOCUS_UNSTABLE,
                        message = "af_not_stable"
                    )
                }
            }
            FocusPolicy.AF_TRIGGER_THEN_REJECT_DRIFT -> {
                trackFocus(metadata)
                if (referenceFocusDistance == null) {
                    if (isAfStable(metadata.afState) && metadata.lensFocusDistance != null) {
                        referenceFocusDistance = metadata.lensFocusDistance
                    } else if (isAfHunting(metadata.afState)) {
                        return CaptureStabilityState(
                            status = CaptureStabilityStatus.FOCUS_UNSTABLE,
                            message = "af_not_stable"
                        )
                    }
                } else {
                    val current = metadata.lensFocusDistance
                    if (current != null) {
                        val delta = abs(current - referenceFocusDistance!!)
                        if (delta > AcceptanceConfig.MAX_FOCUS_DISTANCE_DELTA) {
                            return CaptureStabilityState(
                                status = CaptureStabilityStatus.FOCUS_UNSTABLE,
                                referenceFocusDistance = referenceFocusDistance,
                                message = "focus_unstable"
                            )
                        }
                    }
                }
            }
            FocusPolicy.MANUAL_FIXED_FOCUS -> Unit
        }

        return CaptureStabilityState(
            status = CaptureStabilityStatus.STABLE,
            referenceFocusDistance = referenceFocusDistance
        )
    }

    private fun trackFocus(metadata: FrameMetadata) {
        metadata.lensFocusDistance?.let { distance ->
            if (distance > 0f) {
                recentFocusDistances.addLast(distance)
                while (recentFocusDistances.size > 12) {
                    recentFocusDistances.removeFirst()
                }
                if (
                    referenceFocusDistance == null &&
                    isAfStable(metadata.afState) &&
                    recentFocusDistances.size >= 3
                ) {
                    referenceFocusDistance = recentFocusDistances.average().toFloat()
                }
            }
        }
    }

    private fun isAfHunting(afState: Int?): Boolean = when (afState) {
        CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN,
        CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> true
        else -> false
    }

    private fun isAfStable(afState: Int?): Boolean = when (afState) {
        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> true
        else -> false
    }
}

private const val CALIBRATION_LOCK_DELAY_MS = 2_500L
