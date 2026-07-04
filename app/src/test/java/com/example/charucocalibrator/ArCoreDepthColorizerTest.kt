package com.example.charucocalibrator

import com.example.charucocalibrator.arcore.DepthPercentileScale
import com.example.charucocalibrator.arcore.depthPercentile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArCoreDepthColorizerTest {
    @Test
    fun percentileScaleMapsP2ToZeroAndP98ToOne() {
        val depths = ShortArray(100) { index ->
            when (index) {
                0 -> 0.toShort()
                99 -> 50_000.toShort()
                else -> ((index + 1) * 10).toShort()
            }
        }
        val scale = DepthPercentileScale.fromDepthMm(depths)
        assertTrue(scale.lowMm < scale.highMm)
        assertEquals(0f, scale.normalize(scale.lowMm.toInt()), 0.001f)
        assertEquals(1f, scale.normalize(scale.highMm.toInt()), 0.001f)
        val mid = ((scale.lowMm + scale.highMm) / 2f).toInt()
        assertTrue(scale.normalize(mid) in 0.35f..0.65f)
    }

    @Test
    fun percentileInterpolationAtBoundaries() {
        val sorted = listOf(100, 200, 300, 400, 500)
        assertEquals(100f, depthPercentile(sorted, 0f), 0.01f)
        assertEquals(500f, depthPercentile(sorted, 1f), 0.01f)
    }
}
