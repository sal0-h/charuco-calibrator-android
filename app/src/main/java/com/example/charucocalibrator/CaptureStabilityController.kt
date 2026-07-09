package com.example.charucocalibrator

import android.hardware.camera2.CaptureResult
import kotlin.math.max
import kotlin.math.sqrt

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
                referenceFocusDistance = referenceFocusDistance,
                message = "warming_up"
            )
        }

        when (focusPolicy) {
            FocusPolicy.METADATA_GATE_ONLY -> {
                if (isAfActivelyHunting(metadata.afState)) {
                    return CaptureStabilityState(
                        status = CaptureStabilityStatus.FOCUS_UNSTABLE,
                        message = "af_not_stable"
                    )
                }
            }
            FocusPolicy.AF_TRIGGER_THEN_REJECT_DRIFT -> {
                trackFocus(metadata)
                if (isAfActivelyHunting(metadata.afState)) {
                    return CaptureStabilityState(
                        status = CaptureStabilityStatus.FOCUS_UNSTABLE,
                        message = "af_not_stable"
                    )
                }
                if (focusOscillationTooHigh()) {
                    return CaptureStabilityState(
                        status = CaptureStabilityStatus.FOCUS_UNSTABLE,
                        referenceFocusDistance = referenceFocusDistance,
                        message = "focus_unstable"
                    )
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
                    isAfConverged(metadata.afState) &&
                    recentFocusDistances.size >= 3
                ) {
                    referenceFocusDistance = recentFocusDistances.average().toFloat()
                }
            }
        }
    }

    /**
     * Continuous AF spends much of its time in PASSIVE_SCAN while fine-tuning; only
     * ACTIVE_SCAN indicates an explicit hunt that is unsafe to accept.
     */
    private fun isAfActivelyHunting(afState: Int?): Boolean =
        afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN

    private fun isAfConverged(afState: Int?): Boolean = when (afState) {
        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
        CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> true
        else -> false
    }

    /**
     * Detect AF oscillation via short-window variance instead of a fixed reference.
     * Smooth pose motion changes focus gradually; hunting produces high variance.
     */
    private fun focusOscillationTooHigh(): Boolean {
        if (recentFocusDistances.size < AcceptanceConfig.MIN_FOCUS_SAMPLES_FOR_VARIANCE) {
            return false
        }
        val values = recentFocusDistances.toList()
        val mean = values.average().toFloat()
        if (mean <= 0f) return false
        val variance = values.map { distance ->
            val delta = distance - mean
            delta * delta
        }.average()
        val relativeStdDev = sqrt(variance).toFloat() / max(mean, 0.05f)
        return relativeStdDev > AcceptanceConfig.MAX_FOCUS_RELATIVE_STD_DEV
    }
}

private const val CALIBRATION_LOCK_DELAY_MS = 2_500L
