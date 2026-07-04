package com.example.charucocalibrator.stereo

import com.example.charucocalibrator.Dimensions
import com.example.charucocalibrator.stereo.model.StereoPairProbeResult
import com.example.charucocalibrator.stereo.model.StereoProbeReport
import com.example.charucocalibrator.stereo.model.LensType
import com.example.charucocalibrator.stereo.model.StereoPhysicalCameraInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoProbeReportTest {
    @Test
    fun probeResultSerializationRoundTrip() {
        val result = StereoPairProbeResult(
            leftPhysicalCameraId = "2",
            rightPhysicalCameraId = "5",
            pairLabel = "wide_ultrawide",
            success = true,
            resolution = Dimensions(1920, 1440),
            fallbackReason = null,
            medianTimestampDeltaNs = 1_500_000L,
            halError = null
        )

        val restored = StereoPairProbeResult.fromJson(result.toJson())
        assertEquals(result.pairLabel, restored.pairLabel)
        assertEquals(result.success, restored.success)
        assertEquals(result.resolution, restored.resolution)
        assertEquals(result.medianTimestampDeltaNs, restored.medianTimestampDeltaNs)
    }

    @Test
    fun reportPartitionsWorkingAndFailedPairs() {
        val working = StereoPairProbeResult(
            leftPhysicalCameraId = "2",
            rightPhysicalCameraId = "5",
            pairLabel = "wide_ultrawide",
            success = true,
            resolution = Dimensions(1920, 1440),
            fallbackReason = null,
            medianTimestampDeltaNs = 1_000_000L,
            halError = null
        )
        val failed = working.copy(
            pairLabel = "wide_tele",
            success = false,
            resolution = null,
            halError = "Session configuration failed"
        )
        val report = StereoProbeReport(
            generatedAtUtc = "2026-07-04T00:00:00Z",
            logicalCameraId = "0",
            physicalCameras = listOf(
                StereoPhysicalCameraInfo(
                    physicalCameraId = "2",
                    focalLengthMm = 6.5f,
                    lensType = LensType.WIDE,
                    yuvSizes = listOf(Dimensions(1920, 1440)),
                    jpegSizes = emptyList()
                )
            ),
            probedPairs = listOf(working, failed),
            workingPairs = listOf(working),
            failedPairs = listOf(failed)
        )

        val restored = StereoProbeReport.fromJson(report.toJson())
        assertEquals(1, restored.workingPairs.size)
        assertEquals(1, restored.failedPairs.size)
        assertTrue(restored.probedPairs.any { it.pairLabel == "wide_tele" })
    }
}
