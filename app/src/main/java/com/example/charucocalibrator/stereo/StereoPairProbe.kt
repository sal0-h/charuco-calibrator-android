package com.example.charucocalibrator.stereo

import android.content.Context
import com.example.charucocalibrator.Dimensions
import com.example.charucocalibrator.stereo.model.StereoPairProbeResult
import com.example.charucocalibrator.stereo.model.StereoPhysicalCameraInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class StereoProbeProgress(
    val pairIndex: Int,
    val pairCount: Int,
    val left: StereoPhysicalCameraInfo,
    val right: StereoPhysicalCameraInfo,
    val resolutionIndex: Int,
    val resolutionCount: Int,
    val resolution: Dimensions
)

class StereoPairProbe(
    context: Context
) {
    private val applicationContext = context.applicationContext
    private val cancelled = AtomicBoolean(false)
    private val activeController = AtomicReference<StereoDualStreamController?>(null)
    private val activeLatch = AtomicReference<CountDownLatch?>(null)

    fun cancel() {
        cancelled.set(true)
        activeController.getAndSet(null)?.release()
        activeLatch.getAndSet(null)?.countDown()
    }

    fun probeAll(
        cameras: List<StereoPhysicalCameraInfo>,
        onProgress: ((StereoProbeProgress) -> Unit)? = null,
        onPairFinished: ((StereoPairProbeResult) -> Unit)? = null
    ): List<StereoPairProbeResult> {
        cancelled.set(false)
        val deadlineMs = monotonicMs() + MAX_PROBE_RUNTIME_MS
        val pairs = StereoPhysicalCameraEnumerator.prioritizedPairs(cameras)
        val results = mutableListOf<StereoPairProbeResult>()
        for ((pairOffset, pair) in pairs.withIndex()) {
            if (cancelled.get() || monotonicMs() >= deadlineMs) break
            val (left, right) = pair
            val result = probePair(
                left = left,
                right = right,
                pairIndex = pairOffset + 1,
                pairCount = pairs.size,
                deadlineMs = deadlineMs,
                onProgress = onProgress
            )
            if (cancelled.get()) break
            results.add(result)
            onPairFinished?.invoke(result)
            if (result.success) break
        }
        return results
    }

    fun probePair(
        left: StereoPhysicalCameraInfo,
        right: StereoPhysicalCameraInfo,
        pairIndex: Int = 1,
        pairCount: Int = 1,
        deadlineMs: Long = Long.MAX_VALUE,
        onProgress: ((StereoProbeProgress) -> Unit)? = null
    ): StereoPairProbeResult {
        val pairLabel = StereoPhysicalCameraEnumerator.pairLabel(left, right)
        val candidates = StereoResolutionSelector
            .resolutionCandidates(left.yuvSizes, right.yuvSizes)
            .take(MAX_RESOLUTION_ATTEMPTS)
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
            if (cancelled.get() || monotonicMs() >= deadlineMs) break
            onProgress?.invoke(
                StereoProbeProgress(
                    pairIndex = pairIndex,
                    pairCount = pairCount,
                    left = left,
                    right = right,
                    resolutionIndex = index + 1,
                    resolutionCount = candidates.size,
                    resolution = resolution
                )
            )
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

        if (cancelled.get()) {
            return StereoPairProbeResult(
                leftPhysicalCameraId = left.physicalCameraId,
                rightPhysicalCameraId = right.physicalCameraId,
                pairLabel = pairLabel,
                success = false,
                resolution = null,
                fallbackReason = "probe_cancelled",
                medianTimestampDeltaNs = null,
                halError = "Probe cancelled"
            )
        }

        if (monotonicMs() >= deadlineMs) {
            fallbackReason = "probe_time_limit_reached"
            lastHalError = "Probe stopped after ${MAX_PROBE_RUNTIME_MS / 1_000}s; untested pairs remain selectable manually"
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
        activeLatch.set(latch)
        val resultRef = AtomicReference<StereoProbeSessionResult?>(null)
        val controller = StereoDualStreamController(applicationContext) { }
        activeController.set(controller)
        controller.probePair(
            leftId = leftPhysicalCameraId,
            rightId = rightPhysicalCameraId,
            resolution = resolution
        ) { result ->
            resultRef.set(result)
            latch.countDown()
        }
        latch.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        activeLatch.compareAndSet(latch, null)
        activeController.compareAndSet(controller, null)
        val result = resultRef.get()
        if (result == null) controller.release()
        return result ?: StereoProbeSessionResult(
            success = false,
            medianTimestampDeltaNs = null,
            halError = if (cancelled.get()) "Probe cancelled" else "Probe timed out",
            leftFrameCount = 0,
            rightFrameCount = 0
        )
    }

    companion object {
        private const val MAX_RESOLUTION_ATTEMPTS = 3
        private const val MAX_PROBE_RUNTIME_MS = 30_000L
        private const val PROBE_TIMEOUT_MS = 10_000L

        private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L
    }
}
