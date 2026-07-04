package com.example.charucocalibrator

import android.view.Surface
import com.example.charucocalibrator.arcore.ArCoreOverlayOrientation
import org.junit.Assert.assertEquals
import org.junit.Test

class ArCoreOverlayOrientationTest {
    @Test
    fun rotatesLandscapeDepthForPortraitPreview() {
        assertEquals(
            90,
            ArCoreOverlayOrientation.rotationDegreesForOverlay(
                displayRotation = Surface.ROTATION_0,
                viewportWidth = 1080,
                viewportHeight = 1440,
                bitmapWidth = 160,
                bitmapHeight = 90,
            ),
        )
    }

    @Test
    fun skipsRotationWhenPreviewIsLandscape() {
        assertEquals(
            0,
            ArCoreOverlayOrientation.rotationDegreesForOverlay(
                displayRotation = Surface.ROTATION_0,
                viewportWidth = 1920,
                viewportHeight = 1080,
                bitmapWidth = 160,
                bitmapHeight = 90,
            ),
        )
    }
}
