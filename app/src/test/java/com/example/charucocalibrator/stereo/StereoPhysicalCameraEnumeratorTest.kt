package com.example.charucocalibrator.stereo

import com.example.charucocalibrator.Dimensions
import com.example.charucocalibrator.stereo.model.LensType
import com.example.charucocalibrator.stereo.model.StereoPhysicalCameraInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class StereoPhysicalCameraEnumeratorTest {
    @Test
    fun s23UltraIdsDisambiguateDuplicateReportedFocalLengths() {
        val classified = StereoPhysicalCameraEnumerator.classifyLensTypes(
            listOf(
                camera("2", 2.2f),
                camera("5", 6.3f),
                camera("6", 6.4f),
                camera("7", 6.3f)
            )
        ).associate { it.physicalCameraId to it.lensType }

        assertEquals(LensType.ULTRAWIDE, classified["2"])
        assertEquals(LensType.WIDE, classified["5"])
        assertEquals(LensType.WIDE, classified["6"])
        assertEquals(LensType.TELE, classified["7"])
    }

    @Test
    fun otherCameraSetsUseFocalLengthBandsWithoutInventingTeleLens() {
        val classified = StereoPhysicalCameraEnumerator.classifyLensTypes(
            listOf(
                camera("a", 2.4f),
                camera("b", 6.1f),
                camera("c", 6.5f)
            )
        ).map { it.lensType }

        assertEquals(
            listOf(LensType.ULTRAWIDE, LensType.WIDE, LensType.WIDE),
            classified
        )
    }

    private fun camera(id: String, focalLengthMm: Float) = StereoPhysicalCameraInfo(
        physicalCameraId = id,
        focalLengthMm = focalLengthMm,
        lensType = LensType.UNKNOWN,
        yuvSizes = listOf(Dimensions(1920, 1440)),
        jpegSizes = emptyList()
    )
}
