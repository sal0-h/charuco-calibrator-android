package com.example.charucocalibrator.stereo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StereoSupportBundleExporterTest {
    @Test
    fun collectSupportFiles_includesMetadataOnlyAndChoosesLatestSnapshots() {
        val root = Files.createTempDirectory("stereo-support-test").toFile()
        try {
            root.resolve("camera_report.json").writeText("{}")
            root.resolve("stereo_probe_report.json").writeText("{}")
            root.resolve("stereo_calibration.json").writeText("{}")
            root.resolve("disparity_1.json").writeText("{}")
            root.resolve("disparity_1.json").setLastModified(1L)
            root.resolve("disparity_2.json").writeText("{}")
            root.resolve("disparity_2.json").setLastModified(2L)
            root.resolve("disparity_2.png").writeText("image")

            val oldPair = root.resolve("stereo_pairs/stereo_pair_1").apply { mkdirs() }
            oldPair.resolve("metadata.json").writeText("{}")
            oldPair.resolve("left.jpg").writeText("image")
            oldPair.setLastModified(1L)
            val latestPair = root.resolve("stereo_pairs/stereo_pair_2").apply { mkdirs() }
            latestPair.resolve("metadata.json").writeText("{}")
            latestPair.resolve("right.jpg").writeText("image")
            latestPair.setLastModified(2L)

            root.resolve("stereo_calibration_pairs/pair_1").apply { mkdirs() }
                .resolve("corners.json").writeText("{}")
            root.resolve(StereoDiagnosticsLogger.DIAGNOSTICS_DIRECTORY).apply { mkdirs() }
                .resolve(StereoDiagnosticsLogger.LATEST_FILE_NAME).writeText("{}\n")
            root.resolve(StereoSupportBundleExporter.OUTPUT_FILE_NAME).writeText("old bundle")

            val paths = StereoSupportBundleExporter.collectSupportFiles(root)
                .map { it.relativeTo(root).invariantSeparatorsPath }

            assertEquals(paths.sorted(), paths)
            assertTrue("camera_report.json" in paths)
            assertTrue("stereo_probe_report.json" in paths)
            assertTrue("stereo_calibration.json" in paths)
            assertTrue("disparity_2.json" in paths)
            assertFalse("disparity_1.json" in paths)
            assertTrue("stereo_pairs/stereo_pair_2/metadata.json" in paths)
            assertFalse("stereo_pairs/stereo_pair_1/metadata.json" in paths)
            assertTrue("stereo_calibration_pairs/pair_1/corners.json" in paths)
            assertTrue(
                "stereo_diagnostics/${StereoDiagnosticsLogger.LATEST_FILE_NAME}" in paths
            )
            assertFalse(paths.any { it.endsWith(".jpg") || it.endsWith(".png") })
            assertFalse(StereoSupportBundleExporter.OUTPUT_FILE_NAME in paths)
        } finally {
            root.deleteRecursively()
        }
    }
}
