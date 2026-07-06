package com.example.charucocalibrator.stereo

import kotlin.math.abs

object StereoTimestampUtils {
    const val WARN_TIMESTAMP_DELTA_NS = 5_000_000L
    const val REJECT_SAVE_TIMESTAMP_DELTA_NS = 10_000_000L

    fun deltaNs(leftTimestampNs: Long, rightTimestampNs: Long): Long =
        abs(leftTimestampNs - rightTimestampNs)

    fun isWithinProbeTolerance(deltaNs: Long): Boolean =
        deltaNs <= WARN_TIMESTAMP_DELTA_NS

    fun isSaveable(deltaNs: Long): Boolean =
        deltaNs <= REJECT_SAVE_TIMESTAMP_DELTA_NS

    fun isWarning(deltaNs: Long): Boolean =
        deltaNs > WARN_TIMESTAMP_DELTA_NS

    fun medianDeltaNs(deltas: List<Long>): Long? {
        if (deltas.isEmpty()) return null
        val sorted = deltas.sorted()
        return sorted[sorted.size / 2]
    }
}
