package com.example.charucocalibrator

/**
 * Rotates sensor-native landscape intrinsics to the qsiurp pipeline portrait grid.
 *
 * The transform is a 90° **clockwise** image rotation, which is what turns the S23 Ultra's
 * sensor-native landscape buffer (SENSOR_ORIENTATION 90°) into an upright portrait image.
 * Pixel map: x_p = H_l - y_l, y_p = x_l. Intrinsics: W_p = H_l, H_p = W_l, fx_p = fy_l,
 * fy_p = fx_l, cx_p = H_l - cy_l, cy_p = cx_l. Distortion unchanged.
 *
 * Note: the exported [PipelinePortraitIntrinsicsRotator.ORIENTATION_CONVENTION] label
 * "pipeline_portrait_ccw90" is a legacy contract string kept for downstream compatibility;
 * it denotes this clockwise upright-portrait mapping despite the "ccw" spelling.
 */
data class PipelinePortraitIntrinsics(
    val imageWidth: Int,
    val imageHeight: Int,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val cameraMatrix: Array<DoubleArray>,
    val distortionCoefficients: DoubleArray
)

object PipelinePortraitIntrinsicsRotator {
    const val ORIENTATION_CONVENTION = "pipeline_portrait_ccw90"
    const val ORIENTATION_NOTE =
        "Pipeline portrait grid (90° CCW from sensor-native landscape capture buffers)"

    fun rotateFromSensorLandscape(
        sensorLandscapeWidth: Int,
        sensorLandscapeHeight: Int,
        fx: Double,
        fy: Double,
        cx: Double,
        cy: Double,
        distortionCoefficients: DoubleArray
    ): PipelinePortraitIntrinsics {
        require(sensorLandscapeWidth > 0 && sensorLandscapeHeight > 0) {
            "Invalid sensor landscape dimensions"
        }
        val portraitWidth = sensorLandscapeHeight
        val portraitHeight = sensorLandscapeWidth
        val portraitFx = fy
        val portraitFy = fx
        val portraitCx = sensorLandscapeHeight.toDouble() - cy
        val portraitCy = cx
        return PipelinePortraitIntrinsics(
            imageWidth = portraitWidth,
            imageHeight = portraitHeight,
            fx = portraitFx,
            fy = portraitFy,
            cx = portraitCx,
            cy = portraitCy,
            cameraMatrix = arrayOf(
                doubleArrayOf(portraitFx, 0.0, portraitCx),
                doubleArrayOf(0.0, portraitFy, portraitCy),
                doubleArrayOf(0.0, 0.0, 1.0)
            ),
            distortionCoefficients = distortionCoefficients.copyOf(5)
        )
    }
}
