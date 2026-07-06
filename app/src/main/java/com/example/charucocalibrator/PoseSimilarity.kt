package com.example.charucocalibrator

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Normalized board pose fingerprint used for duplicate-view rejection.
 * Compared against every accepted frame in the current session.
 */
data class BoardPoseFingerprint(
    val centerXRatio: Double,
    val centerYRatio: Double,
    val areaRatio: Double,
    val aspectRatio: Double,
    val cornerCount: Int
)

/**
 * Single pose-distance metric: 0 = identical, 1 = at the similarity boundary.
 * Values below [AcceptanceConfig.MAX_POSE_SIMILARITY_DISTANCE] are treated as duplicates.
 */
object PoseSimilarity {
    fun distance(a: BoardPoseFingerprint, b: BoardPoseFingerprint): Double {
        val centerDist = hypot(
            a.centerXRatio - b.centerXRatio,
            a.centerYRatio - b.centerYRatio
        )
        val areaDelta = abs(a.areaRatio - b.areaRatio)
        val aspectDelta = abs(a.aspectRatio - b.aspectRatio)
        val cornerDenom = max(a.cornerCount, b.cornerCount).coerceAtLeast(1)
        val cornerFractionDelta = abs(a.cornerCount - b.cornerCount).toDouble() / cornerDenom

        val centerTerm = centerDist / AcceptanceConfig.POSE_CENTER_SCALE
        val areaTerm = areaDelta / AcceptanceConfig.POSE_AREA_SCALE
        val aspectTerm = aspectDelta / AcceptanceConfig.POSE_ASPECT_SCALE
        val cornerTerm = cornerFractionDelta / AcceptanceConfig.POSE_CORNER_SCALE

        return sqrt(
            centerTerm * centerTerm +
                areaTerm * areaTerm +
                aspectTerm * aspectTerm +
                cornerTerm * cornerTerm
        )
    }

    fun isTooSimilar(a: BoardPoseFingerprint, b: BoardPoseFingerprint): Boolean =
        distance(a, b) < AcceptanceConfig.MAX_POSE_SIMILARITY_DISTANCE
}
