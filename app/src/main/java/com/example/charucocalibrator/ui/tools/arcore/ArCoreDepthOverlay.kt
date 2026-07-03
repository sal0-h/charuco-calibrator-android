package com.example.charucocalibrator.ui.tools.arcore

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.charucocalibrator.arcore.ArCoreDepthColorizer
import com.example.charucocalibrator.arcore.model.ArCoreFrameState
import com.example.charucocalibrator.arcore.model.DepthImageData
import com.example.charucocalibrator.arcore.model.DepthOverlayMode
import com.example.charucocalibrator.arcore.model.DepthSourceToggle
import kotlin.math.roundToInt

@Composable
fun ArCoreDepthOverlay(
    frameState: ArCoreFrameState,
    overlayMode: DepthOverlayMode,
    depthSource: DepthSourceToggle,
    modifier: Modifier = Modifier,
) {
    if (overlayMode == DepthOverlayMode.Off) {
        return
    }
    val overlayBitmap = remember(
        frameState.timestampNs,
        frameState.rawDepth,
        frameState.smoothedDepth,
        frameState.confidence,
        overlayMode,
        depthSource,
    ) {
        buildOverlayBitmap(frameState, overlayMode, depthSource)
    } ?: return

    val imageBitmap = remember(overlayBitmap) { overlayBitmap.asImageBitmap() }
    val depthWidth = overlayBitmap.width
    val depthHeight = overlayBitmap.height

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val viewW = size.width
            val viewH = size.height
            val depthAspect = depthWidth.toFloat() / depthHeight.coerceAtLeast(1)
            val viewAspect = viewW / viewH.coerceAtLeast(1f)
            val (dstW, dstH) = if (depthAspect > viewAspect) {
                viewW.roundToInt() to (viewW / depthAspect).roundToInt()
            } else {
                (viewH * depthAspect).roundToInt() to viewH.roundToInt()
            }
            val dstOffset = IntOffset(
                ((viewW - dstW) / 2f).roundToInt(),
                ((viewH - dstH) / 2f).roundToInt(),
            )
            drawImage(
                image = imageBitmap,
                dstOffset = dstOffset,
                dstSize = IntSize(dstW, dstH),
                filterQuality = FilterQuality.None,
            )
        }
    }
}

private fun buildOverlayBitmap(
    frameState: ArCoreFrameState,
    overlayMode: DepthOverlayMode,
    depthSource: DepthSourceToggle,
): android.graphics.Bitmap? {
    return when (overlayMode) {
        DepthOverlayMode.Off -> null
        DepthOverlayMode.RawDepthHeatmap -> {
            val depth = selectDepth(frameState, depthSource) ?: return null
            depthToHeatmap(depth)
        }
        DepthOverlayMode.Confidence -> {
            val confidence = frameState.confidence ?: return null
            ArCoreDepthColorizer.confidenceToBitmap(
                confidence = confidence.confidence,
                width = confidence.width,
                height = confidence.height,
            )
        }
        DepthOverlayMode.DepthMaskedByConfidence -> {
            val depth = selectDepth(frameState, depthSource) ?: return null
            val confidence = frameState.confidence ?: return null
            if (depth.width != confidence.width || depth.height != confidence.height) {
                return depthToHeatmap(depth)
            }
            val maxDepthMm = depthMaxScaleMm(depth)
            ArCoreDepthColorizer.maskedDepthHeatmapBitmap(
                depthMm = depth.depthMm,
                confidence = confidence.confidence,
                width = depth.width,
                height = depth.height,
                maxDepthMm = maxDepthMm,
            )
        }
    }
}

private fun depthToHeatmap(depth: DepthImageData): android.graphics.Bitmap {
    val maxDepthMm = depthMaxScaleMm(depth)
    return ArCoreDepthColorizer.depthToHeatmapBitmap(
        depthMm = depth.depthMm,
        width = depth.width,
        height = depth.height,
        maxDepthMm = maxDepthMm,
    )
}

private fun depthMaxScaleMm(depth: DepthImageData): Int = when {
    depth.stats.maxDepthM > 0f -> ((depth.stats.maxDepthM * 1000f).toInt() + 500).coerceAtLeast(1000)
    depth.stats.medianDepthM > 0f -> ((depth.stats.medianDepthM * 1000f).toInt() + 500).coerceAtLeast(1000)
    else -> ArCoreDepthColorizer.DEFAULT_EXPORT_MAX_DEPTH_MM
}

private fun selectDepth(frameState: ArCoreFrameState, depthSource: DepthSourceToggle): DepthImageData? =
    when (depthSource) {
        DepthSourceToggle.Raw -> frameState.rawDepth
        DepthSourceToggle.Smoothed -> frameState.smoothedDepth ?: frameState.rawDepth
    }
