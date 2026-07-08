package com.example.charucocalibrator.ui.tools.arcore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.charucocalibrator.R
import com.example.charucocalibrator.arcore.ArCoreSnapshotExporter
import com.example.charucocalibrator.arcore.model.ArCoreFrameState
import com.example.charucocalibrator.arcore.model.CameraIntrinsicsSnapshot
import com.example.charucocalibrator.arcore.model.ExportCharucoIntrinsicsDiff

@Composable
fun ArCoreIntrinsicsPanel(
    frameState: ArCoreFrameState,
    charucoDiff: ExportCharucoIntrinsicsDiff,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text(
            text = "Intrinsics",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.arcore_intrinsics_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        IntrinsicsBlock(
            label = "ARCore image stream",
            intrinsics = frameState.imageIntrinsics,
        )
        IntrinsicsBlock(
            label = "ARCore texture / preview",
            intrinsics = frameState.textureIntrinsics,
        )
        Text(
            text = "ChArUco reference (scaled to ARCore image size)",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (!charucoDiff.available) {
            Text(
                text = "charuco_calibration_result.json not found in app files.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Text(
                text = "Comparison grid: ${charucoDiff.comparisonWidth}×${charucoDiff.comparisonHeight}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = "ChArUco native: ${charucoDiff.charucoImageWidth}×${charucoDiff.charucoImageHeight} " +
                    "→ scaled fx=${format(charucoDiff.charucoScaledFx)} fy=${format(charucoDiff.charucoScaledFy)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "ARCore image: fx=${format(frameState.imageIntrinsics.fx.toDouble())} " +
                    "fy=${format(frameState.imageIntrinsics.fy.toDouble())}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Δfx=${formatDelta(charucoDiff.deltaFx)} (${formatPercent(charucoDiff.deltaFxPercent)})  " +
                    "Δfy=${formatDelta(charucoDiff.deltaFy)} (${formatPercent(charucoDiff.deltaFyPercent)})",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = "Δcx=${formatDelta(charucoDiff.deltaCx)}  Δcy=${formatDelta(charucoDiff.deltaCy)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (charucoDiff.charucoTransposedToLandscape) {
                Text(
                    text = "ChArUco portrait grid transposed to landscape before scaling.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (charucoDiff.dimensionMismatchWarning) {
                Text(
                    text = "Aspect ratio differs after orientation normalize — scaled comparison may be approximate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun IntrinsicsBlock(
    label: String,
    intrinsics: CameraIntrinsicsSnapshot,
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Text(
            text = "fx=${"%.1f".format(intrinsics.fx)} fy=${"%.1f".format(intrinsics.fy)} " +
                "cx=${"%.1f".format(intrinsics.cx)} cy=${"%.1f".format(intrinsics.cy)}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "${intrinsics.width}×${intrinsics.height}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun format(value: Double?): String = value?.let { "%.1f".format(it) } ?: "n/a"

private fun formatDelta(value: Double?): String = value?.let { "%+.1f".format(it) } ?: "n/a"

private fun formatPercent(value: Double?): String = value?.let { "%+.1f%%".format(it) } ?: "n/a"

@Composable
fun rememberCharucoDiff(
    context: android.content.Context,
    frameState: ArCoreFrameState,
): ExportCharucoIntrinsicsDiff {
    return androidx.compose.runtime.remember(frameState.imageIntrinsics, frameState.timestampNs) {
        ArCoreSnapshotExporter.computeCharucoDiff(context, frameState)
    }
}
