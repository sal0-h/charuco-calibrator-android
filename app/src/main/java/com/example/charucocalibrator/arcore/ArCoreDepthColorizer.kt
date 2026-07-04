package com.example.charucocalibrator.arcore

import android.graphics.Bitmap
import android.graphics.Color

object ArCoreDepthColorizer {

    /** Opaque black for invalid depth in exported PNGs (transparent reads as broken/black in viewers). */
    val EXPORT_INVALID_DEPTH_COLOR: Int = Color.rgb(0, 0, 0)

    fun depthToHeatmapBitmap(
        depthMm: ShortArray,
        width: Int,
        height: Int,
        scale: DepthPercentileScale = DepthPercentileScale.fromDepthMm(depthMm),
        invalidPixelColor: Int = Color.TRANSPARENT,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (index in depthMm.indices) {
            val depthUnsigned = depthMm[index].toInt() and 0xFFFF
            pixels[index] = if (depthUnsigned < 1) {
                invalidPixelColor
            } else {
                depthToHeatmapColor(depthUnsigned, scale)
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
        scale: DepthPercentileScale = DepthPercentileScale.fromDepthMm(depthMm),
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (index in depthMm.indices) {
            val conf = confidence.getOrElse(index) { 0 }.toInt() and 0xFF
            val depthUnsigned = depthMm[index].toInt() and 0xFFFF
            pixels[index] = if (conf < confidenceThreshold || depthUnsigned < 1) {
                Color.TRANSPARENT
            } else {
                depthToHeatmapColor(depthUnsigned, scale)
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun depthToHeatmapColor(depthMm: Int, scale: DepthPercentileScale): Int {
        val normalized = scale.normalize(depthMm)
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
