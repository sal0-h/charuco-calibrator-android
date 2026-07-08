package com.example.charucocalibrator.ui.tools.arcore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.charucocalibrator.arcore.model.ArCoreFrameState
import com.example.charucocalibrator.arcore.model.CameraIntrinsicsSnapshot

@Composable
fun ArCoreDiagnosticsPanel(
    frameState: ArCoreFrameState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text(text = "Diagnostics", style = MaterialTheme.typography.titleSmall)
        DiagnosticLine("tracking_state", frameState.trackingState)
        if (frameState.trackingFailureReason != "NONE") {
            DiagnosticLine("tracking_failure", frameState.trackingFailureReason)
        }
        DiagnosticLine("timestamp_ns", frameState.timestampNs.toString())
        DiagnosticLine("android_camera_timestamp_ns", frameState.androidCameraTimestampNs.toString())
        DiagnosticLine("depth_mode", frameState.depthModeLabel)
        DiagnosticLine("session", if (frameState.sessionRunning) "running" else "paused")

        Text(
            text = "image_intrinsics",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        IntrinsicsDiagnosticLines(frameState.imageIntrinsics)

        Text(
            text = "texture_intrinsics",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        IntrinsicsDiagnosticLines(frameState.textureIntrinsics)

        Text(
            text = "raw_depth",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        frameState.rawDepth?.let { raw ->
            DiagnosticLine("width", raw.width.toString())
            DiagnosticLine("height", raw.height.toString())
            DiagnosticLine("valid_pixel_fraction", "%.3f".format(raw.stats.validPixelFraction))
            DiagnosticLine("median_depth_m", "%.3f".format(raw.stats.medianDepthM))
        } ?: DiagnosticLine("status", "not_available")

        Text(
            text = "smoothed_depth",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        frameState.smoothedDepth?.let { smoothed ->
            DiagnosticLine("width", smoothed.width.toString())
            DiagnosticLine("height", smoothed.height.toString())
            DiagnosticLine("valid_pixel_fraction", "%.3f".format(smoothed.stats.validPixelFraction))
            DiagnosticLine("median_depth_m", "%.3f".format(smoothed.stats.medianDepthM))
        } ?: DiagnosticLine("status", "not_available")

        Text(
            text = "confidence",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        frameState.confidence?.let { conf ->
            DiagnosticLine("width", conf.width.toString())
            DiagnosticLine("height", conf.height.toString())
            DiagnosticLine("mean_confidence", "%.1f".format(conf.stats.meanConfidence))
            DiagnosticLine("high_confidence_fraction", "%.3f".format(conf.stats.highConfidenceFraction))
        } ?: DiagnosticLine("status", "not_available")
    }
}

@Composable
private fun IntrinsicsDiagnosticLines(intrinsics: CameraIntrinsicsSnapshot) {
    DiagnosticLine("fx", fmt(intrinsics.fx))
    DiagnosticLine("fy", fmt(intrinsics.fy))
    DiagnosticLine("cx", fmt(intrinsics.cx))
    DiagnosticLine("cy", fmt(intrinsics.cy))
    DiagnosticLine("width", intrinsics.width.toString())
    DiagnosticLine("height", intrinsics.height.toString())
}

@Composable
private fun DiagnosticLine(key: String, value: String) {
    Text(
        text = "$key: $value",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 2.dp),
    )
}

private fun fmt(value: Float): String = "%.2f".format(value)
