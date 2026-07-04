package com.example.charucocalibrator.stereo

data class StereoDisparitySummary(
    val min: Double,
    val max: Double,
    val validPercent: Double,
    val lowPercentileRaw: Int,
    val highPercentileRaw: Int
)

object StereoDisparityAnalysis {
    fun analyze(rawDisparities: ShortArray): StereoDisparitySummary {
        var validCount = 0
        var minRaw = Int.MAX_VALUE
        var maxRaw = 0
        rawDisparities.forEach { rawValue ->
            val raw = rawValue.toInt()
            if (raw > 0) {
                validCount++
                if (raw < minRaw) minRaw = raw
                if (raw > maxRaw) maxRaw = raw
            }
        }
        if (validCount == 0) {
            return StereoDisparitySummary(
                min = 0.0,
                max = 0.0,
                validPercent = 0.0,
                lowPercentileRaw = 0,
                highPercentileRaw = 16
            )
        }

        val histogram = IntArray(maxRaw + 1)
        rawDisparities.forEach { rawValue ->
            val raw = rawValue.toInt()
            if (raw > 0) histogram[raw]++
        }
        val lowRank = ((validCount - 1) * LOW_PERCENTILE).toInt()
        val highRank = ((validCount - 1) * HIGH_PERCENTILE).toInt()
        return StereoDisparitySummary(
            min = minRaw / DISPARITY_SCALE,
            max = maxRaw / DISPARITY_SCALE,
            validPercent = validCount * 100.0 / rawDisparities.size,
            lowPercentileRaw = histogram.valueAtRank(lowRank),
            highPercentileRaw = histogram.valueAtRank(highRank)
        )
    }

    private fun IntArray.valueAtRank(rank: Int): Int {
        var accumulated = 0
        for (value in indices) {
            accumulated += this[value]
            if (accumulated > rank) return value
        }
        return lastIndex
    }

    private const val LOW_PERCENTILE = 0.02
    private const val HIGH_PERCENTILE = 0.98
    private const val DISPARITY_SCALE = 16.0
}
