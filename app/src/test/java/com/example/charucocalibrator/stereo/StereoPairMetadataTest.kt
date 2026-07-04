package com.example.charucocalibrator.stereo

import com.example.charucocalibrator.Dimensions
import com.example.charucocalibrator.stereo.model.StereoPairMetadata
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoPairMetadataTest {
    @Test
    fun jsonRoundTripPreservesFields() {
        val original = StereoPairMetadata(
            logicalCameraId = "0",
            leftPhysicalCameraId = "2",
            rightPhysicalCameraId = "5",
            pairLabel = "wide_ultrawide",
            leftTimestampNs = 1_000_000L,
            rightTimestampNs = 1_002_000L,
            timestampDeltaNs = 2_000L,
            leftResolution = Dimensions(1920, 1440),
            rightResolution = Dimensions(1920, 1440),
            leftFocalLengthMm = 6.5,
            rightFocalLengthMm = 2.2,
            leftIso = 100,
            rightIso = 120,
            leftExposureTimeNs = 10_000_000L,
            rightExposureTimeNs = 11_000_000L,
            leftLensFocusDistance = 0.2f,
            rightLensFocusDistance = 0.21f,
            leftAfState = 4,
            rightAfState = 4,
            oisDisabled = true,
            notes = "test",
            afPolicy = "fixed_after_warmup"
        )

        val restored = StereoPairMetadata.fromJson(original.toJson())
        assertEquals(original.logicalCameraId, restored.logicalCameraId)
        assertEquals(original.pairLabel, restored.pairLabel)
        assertEquals(original.timestampDeltaNs, restored.timestampDeltaNs)
        assertEquals(original.leftResolution, restored.leftResolution)
        assertEquals(original.rightIso, restored.rightIso)
        assertEquals(original.oisDisabled, restored.oisDisabled)
        assertEquals(original.afPolicy, restored.afPolicy)
        assertEquals(original.notes, restored.notes)
    }
}
