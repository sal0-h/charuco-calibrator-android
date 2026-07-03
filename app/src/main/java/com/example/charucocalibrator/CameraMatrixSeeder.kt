package com.example.charucocalibrator

import android.util.SizeF
import org.opencv.core.CvType
import org.opencv.core.Mat

object CameraMatrixSeeder {
    fun seed(
        imageWidth: Int,
        imageHeight: Int,
        focalLengthMm: Float?,
        sensorPhysicalSize: SizeF?
    ): Mat {
        val matrix = Mat.eye(3, 3, CvType.CV_64F)
        val cx = imageWidth / 2.0
        val cy = imageHeight / 2.0
        val focalPixels = estimateFocalLengthPixels(
            imageWidth = imageWidth,
            focalLengthMm = focalLengthMm,
            sensorPhysicalSize = sensorPhysicalSize
        )
        matrix.put(0, 0, focalPixels)
        matrix.put(1, 1, focalPixels)
        matrix.put(0, 2, cx)
        matrix.put(1, 2, cy)
        return matrix
    }

    private fun estimateFocalLengthPixels(
        imageWidth: Int,
        focalLengthMm: Float?,
        sensorPhysicalSize: SizeF?
    ): Double {
        if (
            focalLengthMm != null &&
            sensorPhysicalSize != null &&
            sensorPhysicalSize.width > 0f
        ) {
            return focalLengthMm / sensorPhysicalSize.width * imageWidth.toDouble()
        }
        return imageWidth.toDouble()
    }
}
