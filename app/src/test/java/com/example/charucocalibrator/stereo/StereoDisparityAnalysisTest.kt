package com.example.charucocalibrator.stereo

import org.junit.Assert.assertEquals
import org.junit.Test

class StereoDisparityAnalysisTest {
    @Test
    fun emptyAndInvalidDisparitiesProduceZeroStats() {
        val summary = StereoDisparityAnalysis.analyze(shortArrayOf(-16, 0, -16, 0))

        assertEquals(0.0, summary.min, 0.0)
        assertEquals(0.0, summary.max, 0.0)
        assertEquals(0.0, summary.validPercent, 0.0)
        assertEquals(0, summary.lowPercentileRaw)
        assertEquals(16, summary.highPercentileRaw)
    }

    @Test
    fun fixedPointValuesAreScaledAndPercentilesIgnoreInvalidPixels() {
        val values = ShortArray(100) { index ->
            when {
                index < 20 -> 0
                index <= 21 -> 16
                index == 99 -> 1_600
                else -> 160
            }
        }

        val summary = StereoDisparityAnalysis.analyze(values)

        assertEquals(1.0, summary.min, 0.0)
        assertEquals(100.0, summary.max, 0.0)
        assertEquals(80.0, summary.validPercent, 0.0)
        assertEquals(16, summary.lowPercentileRaw)
        assertEquals(160, summary.highPercentileRaw)
    }
}
