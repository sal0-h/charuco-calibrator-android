package com.example.charucocalibrator.stereo

import com.example.charucocalibrator.Dimensions
import kotlin.math.abs

object StereoResolutionSelector {
    val FALLBACK_SIZE = Dimensions(width = 1920, height = 1440)

    fun highestMatchedResolution(
        leftSizes: List<Dimensions>,
        rightSizes: List<Dimensions>
    ): Dimensions? {
        val leftCandidates = filterFourThree(leftSizes)
        val rightCandidates = filterFourThree(rightSizes)
        if (leftCandidates.isEmpty() || rightCandidates.isEmpty()) return null

        val rightAreas = rightCandidates.map { it.area }.toSet()
        return leftCandidates
            .filter { it.area in rightAreas }
            .maxByOrNull { it.area }
    }

    fun resolutionCandidates(
        leftSizes: List<Dimensions>,
        rightSizes: List<Dimensions>
    ): List<Dimensions> {
        val candidates = mutableListOf<Dimensions>()
        highestMatchedResolution(leftSizes, rightSizes)?.let(candidates::add)
        if (candidates.none { it == FALLBACK_SIZE }) {
            if (
                leftSizes.any { it == FALLBACK_SIZE } &&
                rightSizes.any { it == FALLBACK_SIZE }
            ) {
                candidates.add(FALLBACK_SIZE)
            }
        }

        val sharedAreas = leftSizes.map { it.area }.toSet()
            .intersect(rightSizes.map { it.area }.toSet())
        filterFourThree(
            sharedAreas.mapNotNull { area ->
                leftSizes.find { it.area == area }
            }
        )
            .sortedByDescending { it.area }
            .forEach { size ->
                if (candidates.none { it == size }) {
                    candidates.add(size)
                }
            }

        return candidates.distinct()
    }

    fun filterFourThree(sizes: List<Dimensions>): List<Dimensions> =
        sizes.filter { it.hasAspectRatio(4, 3) }
            .sortedByDescending { it.area }

    private fun Dimensions.hasAspectRatio(widthRatio: Int, heightRatio: Int): Boolean =
        width.toLong() * heightRatio == height.toLong() * widthRatio

    private val Dimensions.area: Long
        get() = width.toLong() * height
}
