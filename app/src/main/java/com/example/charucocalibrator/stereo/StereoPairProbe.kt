package com.example.charucocalibrator.stereo

import android.content.Context
import com.example.charucocalibrator.Dimensions
import com.example.charucocalibrator.stereo.model.StereoPairProbeResult
import com.example.charucocalibrator.stereo.model.StereoPhysicalCameraInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class StereoPairProbe(
    context: Context
) {
    private val applicationContext = context.applicationContext

    fun probeAll(
        cameras: List<StereoPhysicalCameraInfo>,
        onPairStarted: ((StereoPhysicalCameraInfo, StereoPhysicalCameraInfo) -> Unit)? = null,
        onPairFinished: ((StereoPairProbeResult) -> Unit)? = null
    ): List<StereoPairProbeResult> {
        val pairs = StereoPhysicalCameraEnumerator.prioritizedPairs(cameras)
        val results = mutableListOf<StereoPairProbeResult>()
        for ((left, right) in pairs) {
            onPairStarted?.invoke(left, right)
            val result = probePair(left, right)
            results.add(result)
            onPairFinished?.invoke(result)
        }
        return results
    }

    fun probePair(
        left: StereoPhysicalCameraInfo,
        right: StereoPhysicalCameraInfo
    ): StereoPairProbeResult {
        val pairLabel = StereoPhysicalCameraEnumerator.pairLabel(left, right)
        val candidates = StereoResolutionSelector.resolutionCandidates(left.yuvSizes, right.yuvSizes)
        if (candidates.isEmpty()) {
            return StereoPairProbeResult(
                leftPhysicalCameraId = left.physicalCameraId,
                rightPhysicalCameraId = right.physicalCameraId,
                pairLabel = pairLabel,
                success = false,
                resolution = null,
                fallbackReason = "no_shared_yuv_resolution",
                medianTimestampDeltaNs = null,
                halError = "No shared YUV_420_888 resolution between physical cameras"
            )
        }

        var lastHalError: String? = null
        var fallbackReason: String? = null
        val firstCandidate = candidates.first()

        for ((index, resolution) in candidates.withIndex()) {
            if (index > 0) {
                fallbackReason = "retried_at_${resolution.width}x${resolution.height}"
            }
            val sessionResult = probeAtResolution(
                leftPhysicalCameraId = left.physicalCameraId,
                rightPhysicalCameraId = right.physicalCameraId,
                resolution = resolution
            )
            lastHalError = sessionResult.halError
            if (sessionResult.success) {
                return StereoPairProbeResult(
                    leftPhysicalCameraId = left.physicalCameraId,
                    rightPhysicalCameraId = right.physicalCameraId,
                    pairLabel = pairLabel,
                    success = true,
                    resolution = resolution,
                    fallbackReason = if (resolution == firstCandidate) null else fallbackReason,
                    medianTimestampDeltaNs = sessionResult.medianTimestampDeltaNs,
                    halError = null
                )
            }
        }

        return StereoPairProbeResult(
            leftPhysicalCameraId = left.physicalCameraId,
            rightPhysicalCameraId = right.physicalCameraId,
            pairLabel = pairLabel,
            success = false,
            resolution = null,
            fallbackReason = fallbackReason ?: "all_resolutions_failed",
            medianTimestampDeltaNs = null,
            halError = lastHalError ?: "HAL rejected simultaneous physical streams"
        )
    }

    private fun probeAtResolution(
        leftPhysicalCameraId: String,
        rightPhysicalCameraId: String,
        resolution: Dimensions
    ): StereoProbeSessionResult {
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<StereoProbeSessionResult?>(null)
        val controller = StereoDualStreamController(applicationContext) { }
        controller.probePair(
            leftId = leftPhysicalCameraId,
            rightId = rightPhysicalCameraId,
            resolution = resolution
        ) { result ->
            resultRef.set(result)
            latch.countDown()
        }
        latch.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return resultRef.get() ?: StereoProbeSessionResult(
            success = false,
            medianTimestampDeltaNs = null,
            halError = "Probe timed out",
            leftFrameCount = 0,
            rightFrameCount = 0
        )
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 8_000L
    }
}
