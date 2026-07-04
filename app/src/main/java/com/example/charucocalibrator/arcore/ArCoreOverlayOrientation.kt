package com.example.charucocalibrator.arcore

import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.Surface

/**
 * Rotates depth/confidence bitmaps so landscape ARCore buffers align with the
 * portrait-oriented GLES preview ([Frame.transformDisplayUvCoords] on the camera texture).
 */
object ArCoreOverlayOrientation {

    fun alignBitmapToPreview(
        source: Bitmap,
        displayRotation: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Bitmap {
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            return source
        }
        val degrees = rotationDegreesForOverlay(
            displayRotation = displayRotation,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            bitmapWidth = source.width,
            bitmapHeight = source.height,
        )
        if (degrees == 0) {
            return source
        }
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) {
            source.recycle()
        }
        return rotated
    }

    internal fun rotationDegreesForOverlay(
        displayRotation: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): Int {
        val previewPortrait = viewportHeight > viewportWidth
        val bitmapLandscape = bitmapWidth > bitmapHeight
        if (!previewPortrait || !bitmapLandscape) {
            return 0
        }
        // Portrait preview + landscape depth buffer (typical on phone): rotate to match GLES UV transform.
        return when (displayRotation) {
            Surface.ROTATION_0 -> 90
            Surface.ROTATION_90 -> 0
            Surface.ROTATION_180 -> 270
            Surface.ROTATION_270 -> 180
            else -> 90
        }
    }
}
