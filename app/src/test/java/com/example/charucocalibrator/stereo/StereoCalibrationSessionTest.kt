package com.example.charucocalibrator.stereo

import com.example.charucocalibrator.Dimensions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoCalibrationSessionTest {
    private val session = StereoCalibrationSessionKey(
        leftPhysicalCameraId = "2",
        rightPhysicalCameraId = "5",
        resolution = Dimensions(1920, 1440)
    )

    @Test
    fun matchesOnlySamePairAndResolution() {
        assertTrue(
            session.matches(
                leftPhysicalCameraId = "2",
                rightPhysicalCameraId = "5",
                leftResolution = Dimensions(1920, 1440),
                rightResolution = Dimensions(1920, 1440)
            )
        )
    }

    @Test
    fun rejectsMixedResolutionCapture() {
        assertFalse(
            session.matches(
                leftPhysicalCameraId = "2",
                rightPhysicalCameraId = "5",
                leftResolution = Dimensions(1920, 1440),
                rightResolution = Dimensions(1280, 960)
            )
        )
    }

    @Test
    fun rejectsDifferentPhysicalPair() {
        assertFalse(
            session.matches(
                leftPhysicalCameraId = "2",
                rightPhysicalCameraId = "6",
                leftResolution = Dimensions(1920, 1440),
                rightResolution = Dimensions(1920, 1440)
            )
        )
    }
}
