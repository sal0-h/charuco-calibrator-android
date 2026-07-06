package com.example.charucocalibrator.arcore

import android.media.Image
import android.util.Log
import com.example.charucocalibrator.arcore.model.ConfidenceImageData
import com.example.charucocalibrator.arcore.model.ConfidenceStats
import com.example.charucocalibrator.arcore.model.DepthImageData
import com.example.charucocalibrator.arcore.model.DepthStats
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

object ArCoreDepthReader {

    private const val TAG = "ArCoreDepthReader"
    private const val MIN_VALID_DEPTH_MM = 1
    private const val HIGH_CONFIDENCE_THRESHOLD = 200
    private val DEPTH_BYTE_ORDER: ByteOrder = ByteOrder.LITTLE_ENDIAN

    fun readRawDepth(frame: Frame): DepthImageData? = readDepthImage(frame) { it.acquireRawDepthImage16Bits() }

    fun readSmoothedDepth(frame: Frame): DepthImageData? =
        readDepthImage(frame) { it.acquireDepthImage16Bits() }

    fun readConfidence(frame: Frame): ConfidenceImageData? {
        return try {
            frame.acquireRawDepthConfidenceImage().use { image ->
                readConfidenceImage(image)
            }
        } catch (_: NotYetAvailableException) {
            null
        } catch (error: Exception) {
            Log.w(TAG, "Failed to read confidence image", error)
            null
        }
    }

    private fun readConfidenceImage(image: Image): ConfidenceImageData {
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate().order(DEPTH_BYTE_ORDER)
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val confidence = ByteArray(width * height)
        if (pixelStride == 1 && rowStride == width) {
            buffer.rewind()
            buffer.get(confidence, 0, confidence.size)
        } else {
            var index = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val offset = y * rowStride + x * pixelStride
                    confidence[index++] = buffer.get(offset)
                }
            }
        }
        var sum = 0L
        var highCount = 0
        for (value in confidence) {
            val unsigned = value.toInt() and 0xFF
            sum += unsigned
            if (unsigned >= HIGH_CONFIDENCE_THRESHOLD) {
                highCount++
            }
        }
        val total = width * height
        return ConfidenceImageData(
            width = width,
            height = height,
            imageTimestampNs = image.timestamp,
            confidence = confidence,
            stats = ConfidenceStats(
                width = width,
                height = height,
                meanConfidence = if (total > 0) sum.toFloat() / total else 0f,
                highConfidenceFraction = if (total > 0) highCount.toFloat() / total else 0f,
            ),
        )
    }

    private inline fun readDepthImage(
        frame: Frame,
        acquire: (Frame) -> Image,
    ): DepthImageData? {
        return try {
            acquire(frame).use { image -> readDepthImage(image) }
        } catch (_: NotYetAvailableException) {
            null
        } catch (error: Exception) {
            Log.w(TAG, "Failed to read depth image", error)
            null
        }
    }

    private fun readDepthImage(image: Image): DepthImageData {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val depthMm = readDepthPlane(plane.buffer, width, height, rowStride, pixelStride)
        val validDepthsMm = ArrayList<Int>(width * height / 4)
        for (depth in depthMm) {
            val depthUnsigned = depth.toInt() and 0xFFFF
            if (depthUnsigned >= MIN_VALID_DEPTH_MM) {
                validDepthsMm.add(depthUnsigned)
            }
        }
        val stats = computeDepthStats(width, height, validDepthsMm)
        val scale = DepthPercentileScale.fromDepthMm(depthMm)
        return DepthImageData(
            width = width,
            height = height,
            imageTimestampNs = image.timestamp,
            depthMm = depthMm,
            stats = stats,
            scaleLowM = scale.lowMm / 1000f,
            scaleHighM = scale.highMm / 1000f,
        )
    }

  /**
   * ARCore depth planes are little-endian uint16 mm values. Use byte offsets for
   * [ByteBuffer.getShort]; bulk [ShortBuffer] reads are only safe when rows are densely packed.
   */
    private fun readDepthPlane(
        sourceBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): ShortArray {
        val depthMm = ShortArray(width * height)
        val buffer = sourceBuffer.duplicate().order(DEPTH_BYTE_ORDER)
        val denseRowBytes = width * pixelStride
        if (pixelStride == 2 && rowStride == denseRowBytes) {
            buffer.rewind()
            val shortView = buffer.asShortBuffer()
            val toRead = minOf(depthMm.size, shortView.remaining())
            shortView.get(depthMm, 0, toRead)
            return depthMm
        }
        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val byteIndex = y * rowStride + x * pixelStride
                depthMm[index++] = buffer.getShort(byteIndex)
            }
        }
        return depthMm
    }

    private fun computeDepthStats(
        width: Int,
        height: Int,
        validDepthsMm: List<Int>,
    ): DepthStats {
        val totalPixels = width * height
        if (validDepthsMm.isEmpty()) {
            return DepthStats(
                width = width,
                height = height,
                validPixelFraction = 0f,
            )
        }
        val sorted = validDepthsMm.sorted()
        val minMm = sorted.first()
        val maxMm = sorted.last()
        val medianMm = sorted[sorted.size / 2]
        return DepthStats(
            width = width,
            height = height,
            validPixelFraction = validDepthsMm.size.toFloat() / totalPixels,
            minDepthM = minMm / 1000f,
            medianDepthM = medianMm / 1000f,
            maxDepthM = maxMm / 1000f,
        )
    }

    fun depthMmToShortBuffer(depthMm: ShortArray): ShortBuffer {
        val buffer = ByteBuffer.allocateDirect(depthMm.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        buffer.put(depthMm)
        buffer.rewind()
        return buffer
    }
}
