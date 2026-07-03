package com.example.charucocalibrator.arcore

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

object ArCoreDepthColorizer {

    private const val MIN_VALID_DEPTH_MM = 1
    const val DEFAULT_EXPORT_MAX_DEPTH_MM = 5000
    private const val DEFAULT_MAX_DEPTH_MM = DEFAULT_EXPORT_MAX_DEPTH_MM

    /** Opaque black for invalid depth in exported PNGs (transparent reads as broken/black in viewers). */
    val EXPORT_INVALID_DEPTH_COLOR: Int = Color.rgb(0, 0, 0)

    fun depthToHeatmapBitmap(
        depthMm: ShortArray,
        width: Int,
        height: Int,
        maxDepthMm: Int = DEFAULT_MAX_DEPTH_MM,
        invalidPixelColor: Int = Color.TRANSPARENT,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (index in depthMm.indices) {
            val depthUnsigned = depthMm[index].toInt() and 0xFFFF
            pixels[index] = if (depthUnsigned < MIN_VALID_DEPTH_MM) {
                invalidPixelColor
            } else {
                depthToHeatmapColor(depthUnsigned, maxDepthMm)
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    fun confidenceToBitmap(confidence: ByteArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (index in confidence.indices) {
            val value = confidence[index].toInt() and 0xFF
            pixels[index] = Color.argb(255, value, value, value)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    fun maskedDepthHeatmapBitmap(
        depthMm: ShortArray,
        confidence: ByteArray,
        width: Int,
        height: Int,
        confidenceThreshold: Int = 200,
        maxDepthMm: Int = DEFAULT_MAX_DEPTH_MM,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (index in depthMm.indices) {
            val conf = confidence.getOrElse(index) { 0 }.toInt() and 0xFF
            val depthUnsigned = depthMm[index].toInt() and 0xFFFF
            pixels[index] = if (conf < confidenceThreshold || depthUnsigned < MIN_VALID_DEPTH_MM) {
                Color.TRANSPARENT
            } else {
                depthToHeatmapColor(depthUnsigned, maxDepthMm)
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun depthToHeatmapColor(depthMm: Int, maxDepthMm: Int): Int {
        val clampedMax = max(maxDepthMm, MIN_VALID_DEPTH_MM + 1)
        val normalized = min(1f, depthMm.toFloat() / clampedMax)
        return when {
            normalized < 0.25f -> {
                val t = normalized / 0.25f
                Color.rgb(0, (t * 255).toInt(), 255)
            }
            normalized < 0.5f -> {
                val t = (normalized - 0.25f) / 0.25f
                Color.rgb(0, 255, ((1f - t) * 255).toInt())
            }
            normalized < 0.75f -> {
                val t = (normalized - 0.5f) / 0.25f
                Color.rgb((t * 255).toInt(), 255, 0)
            }
            else -> {
                val t = (normalized - 0.75f) / 0.25f
                Color.rgb(255, ((1f - t) * 255).toInt(), 0)
            }
        }
    }
}
