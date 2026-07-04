package com.example.charucocalibrator.arcore

import kotlin.math.abs

data class AlignedIntrinsics(
    val width: Int,
    val height: Int,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val transposedToLandscape: Boolean = false,
)

object CharucoIntrinsicsAligner {
    private const val ASPECT_RATIO_TOLERANCE = 0.02

    /**
     * Pinhole intrinsics scaled from [source] to [targetWidth]×[targetHeight].
     */
    fun scaleToSize(
        source: AlignedIntrinsics,
        targetWidth: Int,
        targetHeight: Int,
    ): AlignedIntrinsics {
        require(source.width > 0 && source.height > 0)
        val scaleX = targetWidth.toDouble() / source.width
        val scaleY = targetHeight.toDouble() / source.height
        return AlignedIntrinsics(
            width = targetWidth,
            height = targetHeight,
            fx = source.fx * scaleX,
            fy = source.fy * scaleY,
            cx = source.cx * scaleX,
            cy = source.cy * scaleY,
            transposedToLandscape = source.transposedToLandscape,
        )
    }

    /**
     * Normalize to landscape (width ≥ height). Portrait grids swap dimensions and fx/fy, cx/cy.
     */
    fun normalizeToLandscape(
        width: Int,
        height: Int,
        fx: Double,
        fy: Double,
        cx: Double,
        cy: Double,
    ): AlignedIntrinsics {
        if (width >= height) {
            return AlignedIntrinsics(width, height, fx, fy, cx, cy, transposedToLandscape = false)
        }
        return AlignedIntrinsics(
            width = height,
            height = width,
            fx = fy,
            fy = fx,
            cx = cy,
            cy = cx,
            transposedToLandscape = true,
        )
    }

    fun aspectRatio(width: Int, height: Int): Double =
        width.toDouble() / height.coerceAtLeast(1)

    fun aspectRatiosMatch(widthA: Int, heightA: Int, widthB: Int, heightB: Int): Boolean {
        val ratioA = aspectRatio(widthA, heightA)
        val ratioB = aspectRatio(widthB, heightB)
        return abs(ratioA - ratioB) <= ASPECT_RATIO_TOLERANCE
    }

    /**
     * Scale ChArUco calibration intrinsics to the ARCore image stream size for apples-to-apples Δ.
     */
    fun charucoScaledToArcoreImage(
        charucoWidth: Int,
        charucoHeight: Int,
        charucoFx: Double,
        charucoFy: Double,
        charucoCx: Double,
        charucoCy: Double,
        arcoreImageWidth: Int,
        arcoreImageHeight: Int,
    ): AlignedIntrinsics {
        val normalized = normalizeToLandscape(
            charucoWidth,
            charucoHeight,
            charucoFx,
            charucoFy,
            charucoCx,
            charucoCy,
        )
        val arLandscape = normalizeToLandscape(
            arcoreImageWidth,
            arcoreImageHeight,
            0.0,
            0.0,
            0.0,
            0.0,
        )
        return scaleToSize(normalized, arLandscape.width, arLandscape.height)
    }
}
