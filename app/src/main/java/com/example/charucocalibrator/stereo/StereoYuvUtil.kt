package com.example.charucocalibrator.stereo

import android.media.Image

internal object StereoYuvUtil {
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
}
