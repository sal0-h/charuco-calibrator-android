package com.example.charucocalibrator

import com.example.charucocalibrator.arcore.model.ExportOverlayEvidenceSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArCoreOverlayEvidenceExportTest {
    @Test
    fun overlayEvidenceIncludesPreviewAndTransformCoordinates() {
        val json = ExportOverlayEvidenceSection(
            available = true,
            mode = "RawDepthHeatmap",
            source = "Smoothed",
            opacity = 0.55f,
            confidenceThreshold = 190,
            viewportWidth = 1080,
            viewportHeight = 1440,
            displayRotation = 0,
            openglNdcToDepthTextureUv = listOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f),
            previewPngPath = "arcore_overlay_preview_123.png",
        ).toJson()

        assertTrue(json.getBoolean("available"))
        assertEquals("RawDepthHeatmap", json.getString("mode"))
        assertEquals("Smoothed", json.getString("source"))
        assertEquals(190, json.getInt("confidence_threshold"))
        assertEquals("arcore_overlay_preview_123.png", json.getString("preview_png_path"))
        assertEquals(8, json.getJSONArray("opengl_ndc_to_depth_texture_uv").length())
    }
}
