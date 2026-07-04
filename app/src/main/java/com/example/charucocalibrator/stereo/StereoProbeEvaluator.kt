package com.example.charucocalibrator.stereo

data class StereoProbeSnapshot(
    val streamState: StereoStreamState,
    val leftFrameCount: Long,
    val rightFrameCount: Long,
    val timestampDeltasNs: List<Long>,
    val halError: String?,
    val collectionComplete: Boolean,
    val timedOut: Boolean
)

object StereoProbeEvaluator {
    fun terminalResult(snapshot: StereoProbeSnapshot): StereoProbeSessionResult? {
        val terminal = snapshot.streamState == StereoStreamState.FAILED ||
            snapshot.collectionComplete || snapshot.timedOut
        if (!terminal) return null

        val median = StereoTimestampUtils.medianDeltaNs(snapshot.timestampDeltasNs)
        val error = snapshot.halError ?: when {
            snapshot.timedOut -> "Probe timed out while opening streams"
            snapshot.leftFrameCount == 0L || snapshot.rightFrameCount == 0L ->
                "One or both streams produced no frames"
            median == null -> "Streams produced no timestamp-matched pairs"
            !StereoTimestampUtils.isWithinProbeTolerance(median) ->
                "Median timestamp delta ${median}ns exceeds probe tolerance"
            else -> null
        }
        return StereoProbeSessionResult(
            success = error == null,
            medianTimestampDeltaNs = median,
            halError = error,
            leftFrameCount = snapshot.leftFrameCount,
            rightFrameCount = snapshot.rightFrameCount
        )
    }
}
