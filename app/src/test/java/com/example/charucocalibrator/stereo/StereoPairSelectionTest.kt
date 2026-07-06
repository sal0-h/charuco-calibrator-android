package com.example.charucocalibrator.stereo

import com.example.charucocalibrator.Dimensions
import com.example.charucocalibrator.stereo.model.LensType
import com.example.charucocalibrator.stereo.model.StereoPairProbeResult
import com.example.charucocalibrator.stereo.model.StereoPhysicalCameraInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoPairSelectionTest {
    @Test
    fun duplicateWideCamerasAreBothPrioritizedWithUltrawide() {
        val cameras = listOf(
            camera("2", 2.2f, LensType.ULTRAWIDE),
            camera("5", 6.3f, LensType.WIDE),
            camera("6", 6.4f, LensType.WIDE),
            camera("7", 7.9f, LensType.TELE)
        )

        val choices = StereoPairSelection.choices(cameras)

        assertEquals("2:5", choices[0].key)
        assertEquals("2:6", choices[1].key)
        assertTrue(choices.first().recommended)
        assertFalse(choices[1].recommended)
        assertEquals(6, choices.size)
    }

    @Test
    fun manualSelectionUsesSafeSharedResolutionWithoutProbePass() {
        val choice = StereoPairSelection.choices(
            listOf(
                camera("2", 2.2f, LensType.ULTRAWIDE),
                camera("5", 6.3f, LensType.WIDE)
            )
        ).single()

        assertEquals(
            Dimensions(1920, 1440),
            StereoPairSelection.streamResolution(choice, emptyList())
        )
    }

    @Test
    fun successfulProbeResolutionOverridesManualDefault() {
        val choice = StereoPairSelection.choices(
            listOf(
                camera("2", 2.2f, LensType.ULTRAWIDE),
                camera("5", 6.3f, LensType.WIDE)
            )
        ).single()
        val result = StereoPairProbeResult(
            leftPhysicalCameraId = "2",
            rightPhysicalCameraId = "5",
            pairLabel = choice.label,
            success = true,
            resolution = Dimensions(1280, 960),
            fallbackReason = "retried_at_1280x960",
            medianTimestampDeltaNs = 900_000L,
            halError = null
        )

        assertEquals(
            Dimensions(1280, 960),
            StereoPairSelection.streamResolution(choice, listOf(result))
        )
    }

    private fun camera(id: String, focalLength: Float, lensType: LensType) =
        StereoPhysicalCameraInfo(
            physicalCameraId = id,
            focalLengthMm = focalLength,
            lensType = lensType,
            yuvSizes = listOf(
                Dimensions(4000, 3000),
                Dimensions(1920, 1440),
                Dimensions(1280, 960)
            ),
            jpegSizes = emptyList()
        )
}
