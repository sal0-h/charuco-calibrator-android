package com.example.charucocalibrator

import org.junit.Assert.assertEquals
import org.junit.Test

class PipelinePortraitIntrinsicsTest {
    @Test
    fun rotateSession1783328517989_matchesPipelinePromotion() {
        val portrait = PipelinePortraitIntrinsicsRotator.rotateFromSensorLandscape(
            sensorLandscapeWidth = 4000,
            sensorLandscapeHeight = 3000,
            fx = 2810.534640382213,
            fy = 2819.609798733673,
            cx = 1983.6966263580885,
            cy = 1521.279385583898,
            distortionCoefficients = doubleArrayOf(0.138036351979993, 0.0, 0.0, 0.0, 0.0)
        )

        assertEquals(3000, portrait.imageWidth)
        assertEquals(4000, portrait.imageHeight)
        assertEquals(2819.609798733673, portrait.fx, 1e-6)
        assertEquals(2810.534640382213, portrait.fy, 1e-6)
        assertEquals(1478.720614416102, portrait.cx, 1e-6)
        assertEquals(1983.6966263580885, portrait.cy, 1e-6)
        assertEquals(0.138036351979993, portrait.distortionCoefficients[0], 1e-12)
    }
}
