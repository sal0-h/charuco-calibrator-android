package com.example.charucocalibrator.stereo

import com.example.charucocalibrator.Dimensions
import com.example.charucocalibrator.stereo.model.StereoPairProbeResult
import com.example.charucocalibrator.stereo.model.StereoPhysicalCameraInfo

data class StereoPairChoice(
    val left: StereoPhysicalCameraInfo,
    val right: StereoPhysicalCameraInfo,
    val label: String,
    val recommended: Boolean
) {
    val key: String
        get() = "${left.physicalCameraId}:${right.physicalCameraId}"
}

object StereoPairSelection {
    fun choices(cameras: List<StereoPhysicalCameraInfo>): List<StereoPairChoice> =
        StereoPhysicalCameraEnumerator.prioritizedPairs(cameras).mapIndexed { index, pair ->
            StereoPairChoice(
                left = pair.first,
                right = pair.second,
                label = StereoPhysicalCameraEnumerator.pairLabel(pair.first, pair.second),
                recommended = index == 0
            )
        }

    fun resultFor(
        choice: StereoPairChoice,
        results: List<StereoPairProbeResult>
    ): StereoPairProbeResult? = results.firstOrNull { result ->
        result.leftPhysicalCameraId == choice.left.physicalCameraId &&
            result.rightPhysicalCameraId == choice.right.physicalCameraId
    }

    fun streamResolution(
        choice: StereoPairChoice,
        results: List<StereoPairProbeResult>,
        cachedWorkingConfig: StereoWorkingConfig? = null
    ): Dimensions? = resultFor(choice, results)
        ?.takeIf { it.success }
        ?.resolution
        ?: cachedWorkingConfig
            ?.takeIf { it.pairKey == choice.key }
            ?.resolution
            ?.takeIf { resolution ->
                resolution in choice.left.yuvSizes && resolution in choice.right.yuvSizes
            }
        ?: StereoResolutionSelector.resolutionCandidates(
            choice.left.yuvSizes,
            choice.right.yuvSizes
        ).firstOrNull()
}
