package com.example.charucocalibrator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseSimilarityTest {
    @Test
    fun identicalPoses_areTooSimilar() {
        val pose = BoardPoseFingerprint(0.5, 0.5, 0.25, 1.2, 40)
        assertTrue(PoseSimilarity.isTooSimilar(pose, pose))
    }

    @Test
    fun movedCenter_isDistinctEnough() {
        val a = BoardPoseFingerprint(0.50, 0.50, 0.25, 1.20, 40)
        val b = BoardPoseFingerprint(0.62, 0.50, 0.25, 1.20, 40)
        assertFalse(PoseSimilarity.isTooSimilar(a, b))
    }

    @Test
    fun oldBoxDuplicate_isStillRejected() {
        val a = BoardPoseFingerprint(0.50, 0.50, 0.25, 1.20, 40)
        val b = BoardPoseFingerprint(0.51, 0.51, 0.248, 1.19, 39)
        assertTrue(PoseSimilarity.isTooSimilar(a, b))
    }

    @Test
    fun sameBboxDifferentCorners_canDifferEnough() {
        val a = BoardPoseFingerprint(0.50, 0.50, 0.25, 1.20, 54)
        val b = BoardPoseFingerprint(0.50, 0.50, 0.25, 1.20, 40)
        assertFalse(PoseSimilarity.isTooSimilar(a, b))
    }
}
