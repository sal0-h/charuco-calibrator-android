package com.example.charucocalibrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAcceptanceControllerTest {
    private val controller = FrameAcceptanceController()

    @Test
    fun rejectsPoseTooSimilarToAnyPriorAcceptInSession() {
        val frameWidth = 4000
        val frameHeight = 3000
        val detection = detectedBoard(centerX = 2000, centerY = 1500, areaRatio = 0.25)

        assertTrue(
            controller.evaluate(
                detection = detection,
                sharpness = 150.0,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                acceptedCount = 0
            ).accepted
        )

        waitBetweenAccepts()

        val duplicate = detectedBoard(centerX = 2010, centerY = 1510, areaRatio = 0.248)
        val decision = controller.evaluate(
            detection = duplicate,
            sharpness = 150.0,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            acceptedCount = 1
        )

        assertFalse(decision.accepted)
        assertEquals("rejected: too_similar_to_accepted_pose", decision.message)
    }

    @Test
    fun acceptsMeaningfullyDifferentPoseAfterManyAccepts() {
        val frameWidth = 4000
        val frameHeight = 3000
        var acceptedCount = 0

        val anchors = listOf(
            detectedBoard(800, 600, 0.18),
            detectedBoard(3200, 600, 0.20),
            detectedBoard(2000, 2200, 0.22),
            detectedBoard(1200, 1800, 0.16),
            detectedBoard(2800, 1800, 0.19)
        )

        anchors.forEach { detection ->
            val decision = controller.evaluate(
                detection = detection,
                sharpness = 150.0,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                acceptedCount = acceptedCount
            )
            assertTrue(decision.message, decision.accepted)
            acceptedCount++
            waitBetweenAccepts()
        }

        val distinct = detectedBoard(2000, 900, 0.30, aspectHeight = 900)
        val decision = controller.evaluate(
            detection = distinct,
            sharpness = 150.0,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            acceptedCount = acceptedCount
        )
        assertTrue(decision.message, decision.accepted)
    }

    private fun waitBetweenAccepts() {
        Thread.sleep(AcceptanceConfig.MIN_TIME_BETWEEN_ACCEPTED_MS + 50L)
    }

    private fun detectedBoard(
        centerX: Int,
        centerY: Int,
        areaRatio: Double,
        aspectHeight: Int = 1000
    ): DetectionResult {
        val width = (aspectHeight * 1.2).toInt()
        val height = aspectHeight
        val left = centerX - width / 2
        val top = centerY - height / 2
        return DetectionResult(
            markerCount = 20,
            charucoCornerCount = 40,
            status = "detected",
            rejectionReason = null,
            bbox = DetectionBoundingBox(
                left = left,
                top = top,
                right = left + width,
                bottom = top + height,
                areaRatio = areaRatio
            )
        )
    }
}
