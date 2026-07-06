package com.example.charucocalibrator.stereo

import com.example.charucocalibrator.Dimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoResolutionSelectorTest {
    @Test
    fun highestMatchedResolutionPicksSharedMaxArea() {
        val left = listOf(
            Dimensions(4000, 3000),
            Dimensions(1920, 1440)
        )
        val right = listOf(
            Dimensions(3648, 2736),
            Dimensions(1920, 1440)
        )

        val matched = StereoResolutionSelector.highestMatchedResolution(left, right)
        assertEquals(Dimensions(1920, 1440), matched)
    }

    @Test
    fun candidatesPrioritizeBoundedFallbackOverFullResolution() {
        val left = listOf(
            Dimensions(4000, 3000),
            Dimensions(1920, 1440),
            Dimensions(1280, 960)
        )
        val right = listOf(
            Dimensions(3648, 2736),
            Dimensions(1920, 1440),
            Dimensions(1280, 960)
        )

        val candidates = StereoResolutionSelector.resolutionCandidates(left, right)
        assertTrue(candidates.isNotEmpty())
        assertEquals(Dimensions(1920, 1440), candidates.first())
        assertTrue(candidates.contains(StereoResolutionSelector.FALLBACK_SIZE))
    }

    @Test
    fun candidatesRequireExactSharedDimensionsNotOnlyMatchingArea() {
        val left = listOf(Dimensions(1600, 1200))
        val right = listOf(Dimensions(1200, 1600))

        assertTrue(StereoResolutionSelector.resolutionCandidates(left, right).isEmpty())
    }

    @Test
    fun candidatesPreferLowerProbeSizesBeforeLargeSharedOutput() {
        val sizes = listOf(
            Dimensions(4000, 3000),
            Dimensions(1920, 1440),
            Dimensions(1280, 960),
            Dimensions(640, 480)
        )

        assertEquals(
            listOf(
                Dimensions(1920, 1440),
                Dimensions(1280, 960),
                Dimensions(640, 480),
                Dimensions(4000, 3000)
            ),
            StereoResolutionSelector.resolutionCandidates(sizes, sizes)
        )
    }
}
