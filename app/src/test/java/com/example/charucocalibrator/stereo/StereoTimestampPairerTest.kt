package com.example.charucocalibrator.stereo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StereoTimestampPairerTest {
    @Test
    fun stalePreviousFrameIsNotReportedAsCurrentPair() {
        val pairer = StereoTimestampPairer<Long>(timestampNs = { it })

        assertNull(pairer.offerRight(0L))
        assertNull(pairer.offerLeft(33_000_000L))
        val pair = pairer.offerRight(33_900_000L)

        requireNotNull(pair)
        assertEquals(33_000_000L, pair.first)
        assertEquals(33_900_000L, pair.second)
    }

    @Test
    fun closestFrameWithinToleranceIsPaired() {
        val pairer = StereoTimestampPairer<Long>(timestampNs = { it })

        pairer.offerLeft(1_000_000L)
        pairer.offerLeft(5_000_000L)
        val pair = pairer.offerRight(5_800_000L)

        requireNotNull(pair)
        assertEquals(5_000_000L, pair.first)
        assertEquals(5_800_000L, pair.second)
    }

    @Test
    fun clearDropsFramesFromPreviousSession() {
        val pairer = StereoTimestampPairer<Long>(timestampNs = { it })
        pairer.offerLeft(1_000_000L)

        pairer.clear()

        assertNull(pairer.offerRight(1_200_000L))
    }
}
