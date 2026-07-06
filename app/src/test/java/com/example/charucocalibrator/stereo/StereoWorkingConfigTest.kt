package com.example.charucocalibrator.stereo

import com.example.charucocalibrator.Dimensions
import com.example.charucocalibrator.stereo.model.LensType
import com.example.charucocalibrator.stereo.model.StereoPairProbeResult
import com.example.charucocalibrator.stereo.model.StereoPhysicalCameraInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoWorkingConfigTest {
    private val cameras = listOf(
        camera("2", LensType.ULTRAWIDE),
        camera("5", LensType.WIDE)
    )

    @Test
    fun supportedConfigRequiresBothIdsAndExactSharedResolution() {
        assertTrue(
            StereoWorkingConfig("2", "5", Dimensions(1280, 960))
                .isSupportedBy(cameras)
        )
        assertFalse(
            StereoWorkingConfig("2", "6", Dimensions(1280, 960))
                .isSupportedBy(cameras)
        )
        assertFalse(
            StereoWorkingConfig("2", "5", Dimensions(640, 480))
                .isSupportedBy(cameras)
        )
    }

    @Test
    fun cachedResolutionOverridesManualDefaultForSamePair() {
        val choice = StereoPairSelection.choices(cameras).single()
        val cached = StereoWorkingConfig("2", "5", Dimensions(1280, 960))

        assertEquals(
            Dimensions(1280, 960),
            StereoPairSelection.streamResolution(choice, emptyList(), cached)
        )
    }

    @Test
    fun currentSuccessfulProbeOverridesOlderCachedResolution() {
        val choice = StereoPairSelection.choices(cameras).single()
        val cached = StereoWorkingConfig("2", "5", Dimensions(1280, 960))
        val result = StereoPairProbeResult(
            leftPhysicalCameraId = "2",
            rightPhysicalCameraId = "5",
            pairLabel = choice.label,
            success = true,
            resolution = Dimensions(1920, 1440),
            fallbackReason = null,
            medianTimestampDeltaNs = 750_000L,
            halError = null
        )

        assertEquals(
            Dimensions(1920, 1440),
            StereoPairSelection.streamResolution(choice, listOf(result), cached)
        )
    }

    private fun camera(id: String, type: LensType) = StereoPhysicalCameraInfo(
        physicalCameraId = id,
        focalLengthMm = if (type == LensType.ULTRAWIDE) 2.2f else 6.3f,
        lensType = type,
        yuvSizes = listOf(Dimensions(1920, 1440), Dimensions(1280, 960)),
        jpegSizes = emptyList()
    )
}
