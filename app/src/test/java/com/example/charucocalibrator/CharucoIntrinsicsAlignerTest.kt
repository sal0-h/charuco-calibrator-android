package com.example.charucocalibrator

import com.example.charucocalibrator.arcore.CharucoIntrinsicsAligner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharucoIntrinsicsAlignerTest {
    @Test
    fun scalesCharucoToArcoreImageSize() {
        val scaled = CharucoIntrinsicsAligner.charucoScaledToArcoreImage(
            charucoWidth = 4000,
            charucoHeight = 3000,
            charucoFx = 2827.724497832398,
            charucoFy = 2817.338019132614,
            charucoCx = 2010.7555743850075,
            charucoCy = 1497.4907606928784,
            arcoreImageWidth = 640,
            arcoreImageHeight = 480,
        )
        assertEquals(640, scaled.width)
        assertEquals(480, scaled.height)
        assertEquals(452.4, scaled.fx, 0.5)
        assertEquals(450.8, scaled.fy, 0.5)
        assertEquals(321.7, scaled.cx, 0.5)
        assertEquals(239.6, scaled.cy, 0.5)
    }

    @Test
    fun transposesPortraitCharucoBeforeScaling() {
        val scaled = CharucoIntrinsicsAligner.charucoScaledToArcoreImage(
            charucoWidth = 3000,
            charucoHeight = 4000,
            charucoFx = 2817.0,
            charucoFy = 2827.0,
            charucoCx = 1497.0,
            charucoCy = 2010.0,
            arcoreImageWidth = 640,
            arcoreImageHeight = 480,
        )
        assertTrue(scaled.transposedToLandscape)
        assertEquals(640, scaled.width)
        assertEquals(480, scaled.height)
    }

    @Test
    fun matchingAspectRatiosDoNotWarn() {
        assertTrue(
            CharucoIntrinsicsAligner.aspectRatiosMatch(4000, 3000, 640, 480),
        )
        assertFalse(
            CharucoIntrinsicsAligner.aspectRatiosMatch(4000, 3000, 640, 360),
        )
    }
}
