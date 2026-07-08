package com.example.charucocalibrator

import android.media.Image
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Shared YUV_420_888 / NV21 conversions used by the ChArUco and stereo capture paths.
 */
object YuvConversions {

    /** Packs a YUV_420_888 [image] (respecting crop rect and plane strides) into tightly packed NV21. */
    fun yuv420888ToNv21(image: Image): ByteArray {
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        require(width % 2 == 0 && height % 2 == 0) {
            "YUV crop must have even dimensions, got ${width}x$height"
        }

        val output = ByteArray(width * height * 3 / 2)
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer.duplicate()
        val yBufferStart = yBuffer.position()
        var outputOffset = 0

        for (row in 0 until height) {
            val rowStart = yBufferStart +
                (crop.top + row) * yPlane.rowStride +
                crop.left * yPlane.pixelStride
            if (yPlane.pixelStride == 1) {
                yBuffer.position(rowStart)
                yBuffer.get(output, outputOffset, width)
                outputOffset += width
            } else {
                for (column in 0 until width) {
                    output[outputOffset++] = yBuffer.get(rowStart + column * yPlane.pixelStride)
                }
            }
        }

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val uBufferStart = uBuffer.position()
        val vBufferStart = vBuffer.position()
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val cropLeft = crop.left / 2
        val cropTop = crop.top / 2

        for (row in 0 until chromaHeight) {
            val uRowStart = uBufferStart +
                (cropTop + row) * uPlane.rowStride +
                cropLeft * uPlane.pixelStride
            val vRowStart = vBufferStart +
                (cropTop + row) * vPlane.rowStride +
                cropLeft * vPlane.pixelStride
            for (column in 0 until chromaWidth) {
                output[outputOffset++] = vBuffer.get(vRowStart + column * vPlane.pixelStride)
                output[outputOffset++] = uBuffer.get(uRowStart + column * uPlane.pixelStride)
            }
        }

        return output
    }

    /** Extracts the luma plane of a YUV_420_888 [image] into a single-channel grayscale [Mat]. */
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

    /** Copies the luma plane of a packed [nv21] buffer into a single-channel grayscale [Mat]. */
    fun nv21ToGray(nv21: ByteArray, width: Int, height: Int): Mat {
        val gray = Mat(height, width, CvType.CV_8UC1)
        val written = gray.put(0, 0, nv21)
        check(written == width * height) {
            gray.release()
            "Failed to copy the NV21 luma plane"
        }
        return gray
    }

    /** Converts a single-channel grayscale [gray] Mat to a new 3-channel BGR Mat. */
    fun toBgr(gray: Mat): Mat {
        val bgr = Mat()
        Imgproc.cvtColor(gray, bgr, Imgproc.COLOR_GRAY2BGR)
        return bgr
    }
}
