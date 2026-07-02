package com.example.charucocalibrator

import android.media.Image
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

object YuvToGrayMat {
    fun fromYuv420888(image: Image): Mat {
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer.duplicate()
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val gray = Mat(height, width, CvType.CV_8UC1)

        if (pixelStride == 1 && rowStride == width && crop.left == 0 && crop.top == 0) {
            val bytes = ByteArray(width * height)
            yBuffer.position(0)
            yBuffer.get(bytes)
            gray.put(0, 0, bytes)
            return gray
        }

        val row = ByteArray(width)
        for (y in 0 until height) {
            val rowStart = (crop.top + y) * rowStride + crop.left * pixelStride
            if (pixelStride == 1) {
                yBuffer.position(rowStart)
                yBuffer.get(row, 0, width)
            } else {
                for (x in 0 until width) {
                    row[x] = yBuffer.get(rowStart + x * pixelStride)
                }
            }
            gray.put(y, 0, row)
        }
        return gray
    }

    fun toBgr(gray: Mat): Mat {
        val bgr = Mat()
        Imgproc.cvtColor(gray, bgr, Imgproc.COLOR_GRAY2BGR)
        return bgr
    }
}
