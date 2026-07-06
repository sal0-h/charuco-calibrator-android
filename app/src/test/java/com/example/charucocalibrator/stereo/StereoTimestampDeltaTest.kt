package com.example.charucocalibrator.stereo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoTimestampDeltaTest {
    @Test
    fun deltaNsUsesAbsoluteDifference() {
        assertEquals(2_000L, StereoTimestampUtils.deltaNs(5_000L, 3_000L))
        assertEquals(2_000L, StereoTimestampUtils.deltaNs(3_000L, 5_000L))
    }

    @Test
    fun probeToleranceFlagsLargeDeltas() {
        assertTrue(StereoTimestampUtils.isWithinProbeTolerance(4_000_000L))
        assertFalse(StereoTimestampUtils.isWithinProbeTolerance(6_000_000L))
        assertTrue(StereoTimestampUtils.isWarning(6_000_000L))
    }

    @Test
    fun medianDeltaHandlesOddCounts() {
        assertEquals(3L, StereoTimestampUtils.medianDeltaNs(listOf(1, 3, 9)))
    }
}
