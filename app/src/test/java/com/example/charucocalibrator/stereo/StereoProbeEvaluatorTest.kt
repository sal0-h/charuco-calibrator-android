package com.example.charucocalibrator.stereo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoProbeEvaluatorTest {
    @Test
    fun openingProbeIsNotTerminalBeforeTimeout() {
        assertNull(
            StereoProbeEvaluator.terminalResult(
                snapshot(streamState = StereoStreamState.OPENING)
            )
        )
    }

    @Test
    fun collectedSynchronizedFramesPass() {
        val result = StereoProbeEvaluator.terminalResult(
            snapshot(
                streamState = StereoStreamState.STREAMING,
                leftFrameCount = 12,
                rightFrameCount = 12,
                timestampDeltasNs = listOf(800_000L, 1_000_000L, 1_200_000L),
                collectionComplete = true
            )
        )

        requireNotNull(result)
        assertTrue(result.success)
        assertEquals(1_000_000L, result.medianTimestampDeltaNs)
    }

    @Test
    fun framesWithoutTimestampMatchesFailClearly() {
        val result = StereoProbeEvaluator.terminalResult(
            snapshot(
                streamState = StereoStreamState.STREAMING,
                leftFrameCount = 10,
                rightFrameCount = 10,
                collectionComplete = true
            )
        )

        requireNotNull(result)
        assertFalse(result.success)
        assertEquals("Streams produced no timestamp-matched pairs", result.halError)
    }

    @Test
    fun halFailureIsTerminalImmediately() {
        val result = StereoProbeEvaluator.terminalResult(
            snapshot(
                streamState = StereoStreamState.FAILED,
                halError = "Session configuration failed"
            )
        )

        requireNotNull(result)
        assertFalse(result.success)
        assertEquals("Session configuration failed", result.halError)
    }

    private fun snapshot(
        streamState: StereoStreamState,
        leftFrameCount: Long = 0,
        rightFrameCount: Long = 0,
        timestampDeltasNs: List<Long> = emptyList(),
        halError: String? = null,
        collectionComplete: Boolean = false,
        timedOut: Boolean = false
    ) = StereoProbeSnapshot(
        streamState = streamState,
        leftFrameCount = leftFrameCount,
        rightFrameCount = rightFrameCount,
        timestampDeltasNs = timestampDeltasNs,
        halError = halError,
        collectionComplete = collectionComplete,
        timedOut = timedOut
    )
}
