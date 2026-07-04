package com.example.charucocalibrator.arcore

private const val MIN_VALID_DEPTH_MM = 1
const val DEFAULT_EXPORT_MAX_DEPTH_MM = 5000
const val LOW_PERCENTILE = 0.02f
const val HIGH_PERCENTILE = 0.98f

data class DepthPercentileScale(
    val lowMm: Float,
    val highMm: Float,
) {
    fun normalize(depthMm: Int): Float {
        val range = (highMm - lowMm).coerceAtLeast(1f)
        return ((depthMm - lowMm) / range).coerceIn(0f, 1f)
    }

    companion object {
        fun fromDepthMm(depthMm: ShortArray): DepthPercentileScale {
            val valid = ArrayList<Int>(depthMm.size / 2)
            for (sample in depthMm) {
                val unsigned = sample.toInt() and 0xFFFF
                if (unsigned >= MIN_VALID_DEPTH_MM) {
                    valid.add(unsigned)
                }
            }
            if (valid.isEmpty()) {
                return DepthPercentileScale(0f, DEFAULT_EXPORT_MAX_DEPTH_MM.toFloat())
            }
            val sorted = valid.sorted()
            val low = depthPercentile(sorted, LOW_PERCENTILE)
            val high = depthPercentile(sorted, HIGH_PERCENTILE)
            if (high <= low) {
                return DepthPercentileScale(low, low + 1f)
            }
            return DepthPercentileScale(low, high)
        }
    }
}

fun depthPercentile(sorted: List<Int>, fraction: Float): Float {
    if (sorted.isEmpty()) return 0f
    if (sorted.size == 1) return sorted[0].toFloat()
    val rank = fraction.coerceIn(0f, 1f) * (sorted.size - 1)
    val lowIndex = rank.toInt()
    val highIndex = minOf(lowIndex + 1, sorted.size - 1)
    val blend = rank - lowIndex
    return sorted[lowIndex] * (1f - blend) + sorted[highIndex] * blend
}
