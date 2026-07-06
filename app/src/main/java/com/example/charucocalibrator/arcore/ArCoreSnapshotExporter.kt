package com.example.charucocalibrator.arcore

import android.content.Context
import android.graphics.Bitmap
import com.example.charucocalibrator.CharucoCalibrationEngine
import com.example.charucocalibrator.arcore.model.ArCoreFrameState
import com.example.charucocalibrator.arcore.model.ArCoreSnapshotExport
import com.example.charucocalibrator.arcore.model.ExportCharucoIntrinsicsDiff
import com.example.charucocalibrator.arcore.model.ExportConfidenceSection
import com.example.charucocalibrator.arcore.model.ExportDepthSection
import com.example.charucocalibrator.arcore.model.ExportIntrinsics
import com.example.charucocalibrator.arcore.model.ExportOverlayEvidenceSection
import com.example.charucocalibrator.arcore.model.ExportSmoothedDepthSection
import com.example.charucocalibrator.arcore.model.DepthOverlayMode
import com.example.charucocalibrator.arcore.model.DepthSourceToggle
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ArCoreSnapshotResult(
    val exportDir: File,
    val jsonPath: String,
    val filesWritten: List<String>,
    val rawDepthAvailable: Boolean = false,
    val smoothedDepthAvailable: Boolean = false,
    val confidenceAvailable: Boolean = false,
)

data class ArCoreOverlayEvidence(
    val previewBitmap: Bitmap?,
    val mode: DepthOverlayMode,
    val source: DepthSourceToggle,
    val opacity: Float,
    val confidenceThreshold: Int,
)

object ArCoreSnapshotExporter {
    private const val EXPORT_SUBDIR = "arcore_snapshots"
    private const val JSON_INDENT = 2

    fun exportSnapshot(
        context: Context,
        frameState: ArCoreFrameState,
        overlayEvidence: ArCoreOverlayEvidence? = null,
    ): ArCoreSnapshotResult {
        val appContext = context.applicationContext
        val exportDir = (appContext.getExternalFilesDir(null) ?: appContext.filesDir)
            .resolve(EXPORT_SUBDIR)
            .also { it.mkdirs() }
        val epochMs = System.currentTimeMillis()
        val filesWritten = mutableListOf<String>()

        val rawBinName = "arcore_raw_depth_$epochMs.bin"
        val rawPngName = "arcore_raw_depth_$epochMs.png"
        val smoothedBinName = "arcore_smoothed_depth_$epochMs.bin"
        val smoothedPngName = "arcore_smoothed_depth_$epochMs.png"
        val confidencePngName = "arcore_confidence_$epochMs.png"
        val overlayPngName = "arcore_overlay_preview_$epochMs.png"
        val jsonName = "arcore_snapshot_$epochMs.json"

        val rawDepth = frameState.rawDepth
        val smoothedDepth = frameState.smoothedDepth
        val confidence = frameState.confidence

        val rawBinPath = if (rawDepth != null) {
            writeDepthBin(exportDir.resolve(rawBinName), rawDepth.depthMm).also(filesWritten::add)
        } else {
            ""
        }
        val rawPngPath = if (rawDepth != null) {
            writeDepthPng(exportDir.resolve(rawPngName), rawDepth).also(filesWritten::add)
        } else {
            ""
        }
        val smoothedBinPath = if (smoothedDepth != null) {
            writeDepthBin(exportDir.resolve(smoothedBinName), smoothedDepth.depthMm).also(filesWritten::add)
        } else {
            ""
        }
        val smoothedPngPath = if (smoothedDepth != null) {
            writeDepthPng(exportDir.resolve(smoothedPngName), smoothedDepth).also(filesWritten::add)
        } else {
            ""
        }
        val confidencePngPath = if (confidence != null) {
            writeConfidencePng(exportDir.resolve(confidencePngName), confidence).also(filesWritten::add)
        } else {
            ""
        }
        val overlayPngPath = overlayEvidence?.previewBitmap?.let { preview ->
            writeBitmapPng(exportDir.resolve(overlayPngName), preview)
            preview.recycle()
            filesWritten.add(overlayPngName)
            overlayPngName
        } ?: ""

        val charucoDiff = computeCharucoDiff(context, frameState)
        val snapshot = ArCoreSnapshotExport(
            timestampNs = frameState.timestampNs,
            androidCameraTimestampNs = frameState.androidCameraTimestampNs,
            trackingState = frameState.trackingState,
            trackingFailureReason = frameState.trackingFailureReason,
            imageIntrinsics = frameState.imageIntrinsics.toExport(),
            textureIntrinsics = frameState.textureIntrinsics.toExport(),
            rawDepth = ExportDepthSection(
                available = rawDepth != null,
                width = rawDepth?.width ?: 0,
                height = rawDepth?.height ?: 0,
                imageTimestampNs = rawDepth?.imageTimestampNs ?: 0L,
                matchesFrameTimestamp = rawDepth?.imageTimestampNs == frameState.timestampNs,
                validPixelFraction = rawDepth?.stats?.validPixelFraction ?: 0f,
                minDepthM = rawDepth?.stats?.minDepthM ?: 0f,
                medianDepthM = rawDepth?.stats?.medianDepthM ?: 0f,
                maxDepthM = rawDepth?.stats?.maxDepthM ?: 0f,
                scaleLowM = rawDepth?.scaleLowM ?: 0f,
                scaleHighM = rawDepth?.scaleHighM ?: 0f,
                binPath = rawBinPath,
                pngPath = rawPngPath,
            ),
            smoothedDepth = ExportSmoothedDepthSection(
                available = smoothedDepth != null,
                width = smoothedDepth?.width ?: 0,
                height = smoothedDepth?.height ?: 0,
                imageTimestampNs = smoothedDepth?.imageTimestampNs ?: 0L,
                matchesFrameTimestamp = smoothedDepth?.imageTimestampNs == frameState.timestampNs,
                validPixelFraction = smoothedDepth?.stats?.validPixelFraction ?: 0f,
                minDepthM = smoothedDepth?.stats?.minDepthM ?: 0f,
                medianDepthM = smoothedDepth?.stats?.medianDepthM ?: 0f,
                maxDepthM = smoothedDepth?.stats?.maxDepthM ?: 0f,
                scaleLowM = smoothedDepth?.scaleLowM ?: 0f,
                scaleHighM = smoothedDepth?.scaleHighM ?: 0f,
                binPath = smoothedBinPath,
                pngPath = smoothedPngPath,
            ),
            confidence = ExportConfidenceSection(
                available = confidence != null,
                width = confidence?.width ?: 0,
                height = confidence?.height ?: 0,
                imageTimestampNs = confidence?.imageTimestampNs ?: 0L,
                matchesFrameTimestamp = confidence?.imageTimestampNs == frameState.timestampNs,
                meanConfidence = confidence?.stats?.meanConfidence ?: 0f,
                highConfidenceFraction = confidence?.stats?.highConfidenceFraction ?: 0f,
                pngPath = confidencePngPath,
            ),
            overlayEvidence = ExportOverlayEvidenceSection(
                available = overlayPngPath.isNotEmpty(),
                mode = overlayEvidence?.mode?.name ?: DepthOverlayMode.Off.name,
                source = overlayEvidence?.source?.name ?: DepthSourceToggle.Smoothed.name,
                opacity = overlayEvidence?.opacity ?: 0f,
                confidenceThreshold = overlayEvidence?.confidenceThreshold ?: 0,
                viewportWidth = frameState.viewportWidth,
                viewportHeight = frameState.viewportHeight,
                displayRotation = frameState.displayRotation,
                openglNdcToDepthTextureUv = frameState.overlayNdcToDepthTextureUv,
                previewPngPath = overlayPngPath,
            ),
            charucoIntrinsicsDiff = charucoDiff,
            jsonFileName = jsonName,
        )

        val jsonFile = exportDir.resolve(jsonName)
        jsonFile.writeText(snapshot.toJson().toString(JSON_INDENT))
        filesWritten.add(jsonName)

        return ArCoreSnapshotResult(
            exportDir = exportDir,
            jsonPath = jsonFile.absolutePath,
            filesWritten = filesWritten,
            rawDepthAvailable = rawDepth != null,
            smoothedDepthAvailable = smoothedDepth != null,
            confidenceAvailable = confidence != null,
        )
    }

    fun readCharucoCalibration(context: Context): JSONObject? {
        val candidates = listOf(
            context.filesDir.resolve(CharucoCalibrationEngine.CALIBRATION_OUTPUT_FILE),
            context.getExternalFilesDir(null)?.resolve(CharucoCalibrationEngine.CALIBRATION_OUTPUT_FILE),
        )
        return candidates.filterNotNull()
            .firstOrNull { it.isFile }
            ?.let { file ->
                runCatching { JSONObject(file.readText()) }.getOrNull()
            }
    }

    fun computeCharucoDiff(context: Context, frameState: ArCoreFrameState): ExportCharucoIntrinsicsDiff {
        val json = readCharucoCalibration(context) ?: return ExportCharucoIntrinsicsDiff(available = false)
        val charucoFx = json.optDouble("fx").takeIf { !it.isNaN() }
        val charucoFy = json.optDouble("fy").takeIf { !it.isNaN() }
        val charucoCx = json.optDouble("cx").takeIf { !it.isNaN() }
        val charucoCy = json.optDouble("cy").takeIf { !it.isNaN() }
        val charucoWidth = json.optInt("image_width").takeIf { it > 0 }
        val charucoHeight = json.optInt("image_height").takeIf { it > 0 }
        if (
            charucoFx == null || charucoFy == null || charucoCx == null || charucoCy == null ||
            charucoWidth == null || charucoHeight == null
        ) {
            return ExportCharucoIntrinsicsDiff(available = false)
        }

        val arWidth = frameState.imageIntrinsics.width
        val arHeight = frameState.imageIntrinsics.height
        val arFx = frameState.imageIntrinsics.fx.toDouble()
        val arFy = frameState.imageIntrinsics.fy.toDouble()
        val arCx = frameState.imageIntrinsics.cx.toDouble()
        val arCy = frameState.imageIntrinsics.cy.toDouble()

        val scaled = CharucoIntrinsicsAligner.charucoScaledToArcoreImage(
            charucoWidth = charucoWidth,
            charucoHeight = charucoHeight,
            charucoFx = charucoFx,
            charucoFy = charucoFy,
            charucoCx = charucoCx,
            charucoCy = charucoCy,
            arcoreImageWidth = arWidth,
            arcoreImageHeight = arHeight,
        )

        val deltaFx = arFx - scaled.fx
        val deltaFy = arFy - scaled.fy
        val deltaCx = arCx - scaled.cx
        val deltaCy = arCy - scaled.cy
        val deltaFxPercent = if (scaled.fx != 0.0) (deltaFx / scaled.fx) * 100.0 else null
        val deltaFyPercent = if (scaled.fy != 0.0) (deltaFy / scaled.fy) * 100.0 else null

        val charucoLandscape = CharucoIntrinsicsAligner.normalizeToLandscape(
            charucoWidth,
            charucoHeight,
            charucoFx,
            charucoFy,
            charucoCx,
            charucoCy,
        )
        val arLandscape = CharucoIntrinsicsAligner.normalizeToLandscape(
            arWidth,
            arHeight,
            arFx,
            arFy,
            arCx,
            arCy,
        )
        val aspectMismatch = !CharucoIntrinsicsAligner.aspectRatiosMatch(
            charucoLandscape.width,
            charucoLandscape.height,
            arLandscape.width,
            arLandscape.height,
        )

        val comparisonNote = buildString {
            append(
                "ChArUco scaled from ${charucoWidth}×${charucoHeight} to ${arWidth}×${arHeight} " +
                    "(landscape-normalized) for comparison with ARCore image intrinsics.",
            )
            if (scaled.transposedToLandscape) {
                append(" ChArUco grid transposed from portrait to landscape.")
            }
        }

        return ExportCharucoIntrinsicsDiff(
            available = true,
            charucoFx = charucoFx,
            charucoFy = charucoFy,
            charucoCx = charucoCx,
            charucoCy = charucoCy,
            charucoImageWidth = charucoWidth,
            charucoImageHeight = charucoHeight,
            comparisonWidth = arWidth,
            comparisonHeight = arHeight,
            charucoScaledFx = scaled.fx,
            charucoScaledFy = scaled.fy,
            charucoScaledCx = scaled.cx,
            charucoScaledCy = scaled.cy,
            charucoTransposedToLandscape = scaled.transposedToLandscape,
            deltaFx = deltaFx,
            deltaFy = deltaFy,
            deltaCx = deltaCx,
            deltaCy = deltaCy,
            deltaFxPercent = deltaFxPercent,
            deltaFyPercent = deltaFyPercent,
            dimensionMismatchWarning = aspectMismatch,
            comparisonNote = comparisonNote,
        )
    }

    private fun com.example.charucocalibrator.arcore.model.CameraIntrinsicsSnapshot.toExport(): ExportIntrinsics =
        ExportIntrinsics(
            fx = fx,
            fy = fy,
            cx = cx,
            cy = cy,
            width = width,
            height = height,
        )

    private fun writeDepthBin(file: File, depthMm: ShortArray): String {
        val buffer = ByteBuffer.allocate(depthMm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (value in depthMm) {
            buffer.putShort(value)
        }
        FileOutputStream(file).use { it.write(buffer.array()) }
        return file.name
    }

    private fun writeDepthPng(
        file: File,
        depth: com.example.charucocalibrator.arcore.model.DepthImageData,
    ): String {
        val bitmap = ArCoreDepthColorizer.depthToHeatmapBitmap(
            depthMm = depth.depthMm,
            width = depth.width,
            height = depth.height,
            invalidPixelColor = ArCoreDepthColorizer.EXPORT_INVALID_DEPTH_COLOR,
        )
        writeBitmapPng(file, bitmap)
        bitmap.recycle()
        return file.name
    }

    private fun writeConfidencePng(
        file: File,
        confidence: com.example.charucocalibrator.arcore.model.ConfidenceImageData,
    ): String {
        val bitmap = ArCoreDepthColorizer.confidenceToBitmap(
            confidence = confidence.confidence,
            width = confidence.width,
            height = confidence.height,
        )
        writeBitmapPng(file, bitmap)
        bitmap.recycle()
        return file.name
    }

    private fun writeBitmapPng(file: File, bitmap: Bitmap) {
        FileOutputStream(file).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "PNG compression failed for ${file.name}"
            }
            stream.fd.sync()
        }
    }
}
