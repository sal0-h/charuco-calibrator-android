package com.example.charucocalibrator.ui.tools.stereo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.charucocalibrator.stereo.StereoLiveState
import com.example.charucocalibrator.stereo.StereoStreamState
import com.example.charucocalibrator.stereo.StereoTimestampUtils
import com.example.charucocalibrator.stereo.model.StereoCalibrationResult
import com.example.charucocalibrator.stereo.model.StereoPairProbeResult

@Composable
fun StereoStatusPanel(
    liveState: StereoLiveState,
    enumerationError: String?,
    physicalCameraSummary: String,
    probeResults: List<StereoPairProbeResult>,
    calibrationPairCount: Int,
    calibrationMinPairs: Int,
    calibrationResult: StereoCalibrationResult?,
    statusMessage: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        Text("Status", style = MaterialTheme.typography.titleSmall)
        enumerationError?.let {
            Text("Enumeration: $it", style = MaterialTheme.typography.bodySmall)
        }
        Text(physicalCameraSummary, style = MaterialTheme.typography.bodySmall)
        Text("Stream: ${liveState.streamState.name}", style = MaterialTheme.typography.bodySmall)
        liveState.pairLabel?.let {
            Text("Pair: $it (${liveState.leftPhysicalId} + ${liveState.rightPhysicalId})", style = MaterialTheme.typography.bodySmall)
        }
        liveState.resolution?.let {
            Text("Resolution: ${it.width}x${it.height}", style = MaterialTheme.typography.bodySmall)
        }
        liveState.fallbackReason?.let {
            Text("Fallback: $it", style = MaterialTheme.typography.bodySmall)
        }
        if (liveState.streamState == StereoStreamState.STREAMING) {
            Text(
                "FPS: L=${"%.1f".format(liveState.leftFps)} R=${"%.1f".format(liveState.rightFps)}",
                style = MaterialTheme.typography.bodySmall
            )
            val deltaColor = if (liveState.timestampDeltaNs != null &&
                StereoTimestampUtils.isWarning(liveState.timestampDeltaNs)
            ) {
                Color.Red
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                "timestamp_delta_ns: ${liveState.timestampDeltaNs ?: "n/a"}",
                style = MaterialTheme.typography.bodySmall,
                color = deltaColor
            )
            Text(
                "Left ISO=${liveState.leftMetadata?.isoSensitivity ?: "n/a"} " +
                    "exp=${liveState.leftMetadata?.exposureTimeNs ?: "n/a"} " +
                    "focus=${liveState.leftMetadata?.lensFocusDistance ?: "n/a"} " +
                    "AF=${liveState.leftMetadata?.afState ?: "n/a"}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Right ISO=${liveState.rightMetadata?.isoSensitivity ?: "n/a"} " +
                    "exp=${liveState.rightMetadata?.exposureTimeNs ?: "n/a"} " +
                    "focus=${liveState.rightMetadata?.lensFocusDistance ?: "n/a"} " +
                    "AF=${liveState.rightMetadata?.afState ?: "n/a"}",
                style = MaterialTheme.typography.bodySmall
            )
            Text("AF policy: ${liveState.afPolicy}", style = MaterialTheme.typography.bodySmall)
            Text("OIS disabled: ${liveState.oisDisabled}", style = MaterialTheme.typography.bodySmall)
        }
        liveState.warningMessage?.let {
            Text("Warning: $it", style = MaterialTheme.typography.bodySmall, color = Color.Red)
        }
        liveState.halError?.let {
            Text("HAL error: $it", style = MaterialTheme.typography.bodySmall, color = Color.Red)
        }
        if (probeResults.isNotEmpty()) {
            Text("Probe results:", style = MaterialTheme.typography.bodySmall)
            probeResults.forEach { result ->
                Text(
                    "  ${result.pairLabel}: ${if (result.success) "PASS" else "FAIL"} " +
                        "${result.resolution?.width ?: "-"}x${result.resolution?.height ?: "-"} " +
                        "delta=${result.medianTimestampDeltaNs ?: "n/a"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Text(
            "Calibration pairs: $calibrationPairCount / $calibrationMinPairs",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )
        calibrationResult?.let { result ->
            if (result.success) {
                Text(
                    "Stereo RMS: ${"%.4f".format(result.stereoRms)} baseline=${"%.4f".format(result.baselineM)} m",
                    style = MaterialTheme.typography.bodySmall
                )
                result.medianTimestampDeltaNs?.let {
                    Text("Median calibration timestamp delta: ${it}ns", style = MaterialTheme.typography.bodySmall)
                }
            } else if (result.statusMessage.isNotBlank()) {
                Text("Calibration: ${result.statusMessage}", style = MaterialTheme.typography.bodySmall)
            }
        }
        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
