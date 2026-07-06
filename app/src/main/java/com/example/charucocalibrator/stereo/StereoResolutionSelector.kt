package com.example.charucocalibrator.stereo

import com.example.charucocalibrator.Dimensions
object StereoResolutionSelector {
    val FALLBACK_SIZE = Dimensions(width = 1920, height = 1440)
    private val PROBE_PREFERRED_SIZES = listOf(
        FALLBACK_SIZE,
        Dimensions(width = 1280, height = 960),
        Dimensions(width = 640, height = 480)
    )

    fun highestMatchedResolution(
        leftSizes: List<Dimensions>,
        rightSizes: List<Dimensions>
    ): Dimensions? {
        val leftCandidates = filterFourThree(leftSizes)
        val rightCandidates = filterFourThree(rightSizes)
        if (leftCandidates.isEmpty() || rightCandidates.isEmpty()) return null

        val rightSizesSet = rightCandidates.toSet()
        return leftCandidates
            .filter { it in rightSizesSet }
            .maxByOrNull { it.area }
    }

    fun resolutionCandidates(
        leftSizes: List<Dimensions>,
        rightSizes: List<Dimensions>
    ): List<Dimensions> {
        val shared = leftSizes.toSet()
            .intersect(rightSizes.toSet())
            .filter { it.hasAspectRatio(4, 3) }
        if (shared.isEmpty()) return emptyList()

        val preferred = PROBE_PREFERRED_SIZES.filter { it in shared }
        val remaining = shared
            .filterNot { it in preferred }
            .sortedByDescending { it.area }
        return preferred + remaining
    }

    fun filterFourThree(sizes: List<Dimensions>): List<Dimensions> =
        sizes.filter { it.hasAspectRatio(4, 3) }
            .sortedByDescending { it.area }

    private fun Dimensions.hasAspectRatio(widthRatio: Int, heightRatio: Int): Boolean =
        width.toLong() * heightRatio == height.toLong() * widthRatio

    private val Dimensions.area: Long
        get() = width.toLong() * height
}
