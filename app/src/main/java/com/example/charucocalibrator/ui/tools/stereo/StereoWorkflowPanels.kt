package com.example.charucocalibrator.ui.tools.stereo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.charucocalibrator.Dimensions
import com.example.charucocalibrator.R
import com.example.charucocalibrator.stereo.StereoLiveState
import com.example.charucocalibrator.stereo.StereoPairChoice
import com.example.charucocalibrator.stereo.StereoPairSelection
import com.example.charucocalibrator.stereo.StereoProbeProgress
import com.example.charucocalibrator.stereo.StereoStreamState
import com.example.charucocalibrator.stereo.model.StereoCalibrationResult
import com.example.charucocalibrator.stereo.model.StereoPairProbeResult
import com.example.charucocalibrator.stereo.model.StereoPhysicalCameraInfo

@Composable
fun StereoStatusBanner(
    message: String,
    isError: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = if (isError) {
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = colors.second,
        modifier = modifier
            .fillMaxWidth()
            .background(colors.first)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

@Composable
fun StereoSetupPanel(
    cameras: List<StereoPhysicalCameraInfo>,
    enumerationError: String?,
    probing: Boolean,
    progress: StereoProbeProgress?,
    canRunProbe: Boolean,
    canExportReport: Boolean,
    onRunProbe: () -> Unit,
    onCancelProbe: () -> Unit,
    onExportReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    StereoStepCard(
        step = 1,
        title = stringResource(R.string.stereo_setup_title),
        subtitle = stringResource(R.string.stereo_setup_subtitle),
        modifier = modifier
    ) {
        when {
            enumerationError != null -> SupportingText(enumerationError, isError = true)
            cameras.isEmpty() -> SupportingText(stringResource(R.string.stereo_reading_camera))
            else -> cameras.forEach { camera ->
                Text(
                    text = "ID ${camera.physicalCameraId}  ${camera.lensType.label}  " +
                        "${"%.1f".format(camera.focalLengthMm)} mm",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (probing) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = progress?.let {
                    "Probing pair ${it.pairIndex}/${it.pairCount}: " +
                        "${it.left.physicalCameraId} + ${it.right.physicalCameraId}  •  " +
                        "${it.resolution.width}×${it.resolution.height} " +
                        "(${it.resolutionIndex}/${it.resolutionCount})"
                } ?: stringResource(R.string.stereo_probe_preparing),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            OutlinedButton(
                onClick = onCancelProbe,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Text(stringResource(R.string.stereo_cancel_probe))
            }
        } else {
            Button(
                onClick = onRunProbe,
                enabled = canRunProbe,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text(stringResource(R.string.stereo_run_probe))
            }
            if (!canRunProbe) {
                SupportingText(
                    if (cameras.size < 2) {
                        stringResource(R.string.stereo_probe_requires_two)
                    } else {
                        stringResource(R.string.stereo_probe_stop_stream)
                    }
                )
            }
        }

        OutlinedButton(
            onClick = onExportReport,
            enabled = canExportReport,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            Text(stringResource(R.string.stereo_export_probe_report))
        }
    }
}

@Composable
fun StereoPairSelectionPanel(
    choices: List<StereoPairChoice>,
    selectedKey: String?,
    probeResults: List<StereoPairProbeResult>,
    selectionEnabled: Boolean,
    onSelect: (StereoPairChoice) -> Unit,
    modifier: Modifier = Modifier
) {
    StereoStepCard(
        step = 2,
        title = stringResource(R.string.stereo_select_title),
        subtitle = stringResource(R.string.stereo_select_subtitle),
        modifier = modifier
    ) {
        if (choices.isEmpty()) {
            SupportingText(stringResource(R.string.stereo_select_waiting))
        }
        choices.forEach { choice ->
            val result = StereoPairSelection.resultFor(choice, probeResults)
            val status = when {
                result == null -> "NOT TESTED"
                result.success -> "PASS"
                else -> "FAIL"
            }
            FilterChip(
                selected = selectedKey == choice.key,
                onClick = { onSelect(choice) },
                enabled = selectionEnabled,
                label = {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            buildString {
                                append(choice.label)
                                if (choice.recommended) append("  •  recommended")
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            buildString {
                                append(status)
                                result?.resolution?.let { append("  ${it.width}×${it.height}") }
                                result?.medianTimestampDeltaNs?.let { append("  Δ ${formatDelta(it)}") }
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (result?.success == false) {
                            Text(
                                result.halError ?: "HAL rejected this configuration",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
        }
        if (!selectionEnabled && choices.isNotEmpty()) {
            SupportingText(stringResource(R.string.stereo_select_locked))
        }
    }
}

@Composable
fun StereoStreamPanel(
    liveState: StereoLiveState,
    displayState: StereoStreamState,
    selectedResolution: Dimensions?,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    StereoStepCard(
        step = 3,
        title = stringResource(R.string.stereo_stream_title),
        subtitle = stringResource(R.string.stereo_stream_subtitle),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StreamStateBadge(displayState)
            selectedResolution?.let {
                Text("${it.width}×${it.height}", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Text(
            text = liveState.timestampDeltaNs?.let(::formatDelta) ?: "—",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = deltaColor(liveState.timestampDeltaNs),
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            "${stringResource(R.string.stereo_timestamp_delta_label)}: " +
                "${liveState.timestampDeltaNs ?: "—"}",
            style = MaterialTheme.typography.labelMedium
        )

        if (displayState == StereoStreamState.STREAMING) {
            Text(
                "FPS  L ${"%.1f".format(liveState.leftFps)}  •  R ${"%.1f".format(liveState.rightFps)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                "Left  ISO ${liveState.leftMetadata?.isoSensitivity ?: "—"}  " +
                    "exp ${formatExposure(liveState.leftMetadata?.exposureTimeNs)}  " +
                    "AF ${liveState.leftMetadata?.afState ?: "—"}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Right  ISO ${liveState.rightMetadata?.isoSensitivity ?: "—"}  " +
                    "exp ${formatExposure(liveState.rightMetadata?.exposureTimeNs)}  " +
                    "AF ${liveState.rightMetadata?.afState ?: "—"}",
                style = MaterialTheme.typography.bodySmall
            )
            liveState.warningMessage?.let { SupportingText(it, isError = true) }
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text(stringResource(R.string.stereo_stop_streams))
            }
        } else {
            Button(
                onClick = onStart,
                enabled = canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text(stringResource(R.string.stereo_start_streams))
            }
            if (!canStart) {
                SupportingText(
                    when {
                        selectedResolution == null -> stringResource(R.string.stereo_no_shared_size)
                        displayState == StereoStreamState.PROBING -> stringResource(R.string.stereo_wait_probe)
                        displayState == StereoStreamState.OPENING -> stringResource(R.string.stereo_wait_opening)
                        else -> stringResource(R.string.stereo_select_first)
                    }
                )
            }
        }
        liveState.halError?.let { SupportingText("HAL: $it", isError = true) }
    }
}

@Composable
fun StereoCapturePanel(
    streaming: Boolean,
    matchedPairReady: Boolean,
    calibrationPairCount: Int,
    busy: Boolean,
    onSaveStereoPair: () -> Unit,
    onSaveBoardPair: () -> Unit,
    modifier: Modifier = Modifier
) {
    StereoStepCard(
        step = 4,
        title = stringResource(R.string.stereo_capture_title),
        subtitle = stringResource(R.string.stereo_capture_subtitle),
        modifier = modifier
    ) {
        Button(
            onClick = onSaveStereoPair,
            enabled = streaming && matchedPairReady && !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) stringResource(R.string.stereo_saving) else stringResource(R.string.stereo_save_pair))
        }
        OutlinedButton(
            onClick = onSaveBoardPair,
            enabled = streaming && matchedPairReady && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            Text("Save board pair  •  $calibrationPairCount saved")
        }
        if (!streaming) {
            SupportingText(stringResource(R.string.stereo_start_before_capture))
        } else if (!matchedPairReady) {
            SupportingText(stringResource(R.string.stereo_wait_matched_pair))
        }
    }
}

@Composable
fun StereoCalibrationPanel(
    pairCount: Int,
    minimumPairs: Int,
    result: StereoCalibrationResult?,
    busy: Boolean,
    onCalibrate: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    StereoStepCard(
        step = 5,
        title = stringResource(R.string.stereo_calibrate_title),
        subtitle = stringResource(R.string.stereo_calibrate_subtitle),
        modifier = modifier
    ) {
        LinearProgressIndicator(
            progress = { (pairCount.toFloat() / minimumPairs).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "$pairCount / $minimumPairs board pairs",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        result?.let {
            if (it.success) {
                Text(
                    "RMS ${"%.4f".format(it.stereoRms)}  •  baseline ${"%.4f".format(it.baselineM)} m",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                SupportingText(it.statusMessage, isError = true)
            }
        }
        Button(
            onClick = onCalibrate,
            enabled = pairCount >= minimumPairs && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        ) {
            Text(if (busy) stringResource(R.string.stereo_calibrating) else stringResource(R.string.stereo_calibrate))
        }
        if (pairCount < minimumPairs) {
            SupportingText("Capture ${minimumPairs - pairCount} more board pair(s).")
        }
        TextButton(
            onClick = onClear,
            enabled = pairCount > 0 && !busy,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(stringResource(R.string.stereo_clear_pair_captures))
        }
    }
}

@Composable
fun StereoDisparityPanel(
    calibrationReady: Boolean,
    stereoPairReady: Boolean,
    busy: Boolean,
    disclaimer: String,
    onCompute: () -> Unit,
    modifier: Modifier = Modifier
) {
    StereoStepCard(
        step = 6,
        title = stringResource(R.string.stereo_disparity_title),
        subtitle = stringResource(R.string.stereo_disparity_subtitle),
        modifier = modifier
    ) {
        Text(
            disclaimer,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onCompute,
            enabled = calibrationReady && stereoPairReady && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        ) {
            Text(if (busy) stringResource(R.string.stereo_computing) else stringResource(R.string.stereo_compute_disparity))
        }
        if (!calibrationReady) {
            SupportingText(stringResource(R.string.stereo_calibration_required))
        } else if (!stereoPairReady) {
            SupportingText(stringResource(R.string.stereo_pair_required))
        }
    }
}

@Composable
fun StereoPreviewPanel(
    enabled: Boolean,
    toggleEnabled: Boolean,
    leftPreview: android.view.TextureView,
    rightPreview: android.view.TextureView,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.stereo_previews_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.stereo_previews_subtitle),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                enabled = toggleEnabled
            )
        }
        if (enabled) {
            StereoPreviewOverlay(leftPreview, rightPreview)
        }
    }
}

@Composable
private fun StereoStepCard(
    step: Int,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "STEP $step",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )
            content()
        }
    }
}

@Composable
private fun StreamStateBadge(state: StereoStreamState) {
    val colors = when (state) {
        StereoStreamState.STREAMING -> Color(0xFFD7F2DD) to Color(0xFF155D27)
        StereoStreamState.FAILED -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
        StereoStreamState.OPENING, StereoStreamState.PROBING ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        StereoStreamState.IDLE -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = colors.first, contentColor = colors.second, shape = MaterialTheme.shapes.small) {
        Text(
            state.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun SupportingText(message: String, isError: Boolean = false) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun deltaColor(deltaNs: Long?): Color = when {
    deltaNs == null -> MaterialTheme.colorScheme.onSurfaceVariant
    deltaNs < 3_000_000L -> Color(0xFF2E7D32)
    deltaNs < 5_000_000L -> Color(0xFFF9A825)
    else -> MaterialTheme.colorScheme.error
}

private fun formatDelta(deltaNs: Long): String =
    "${"%.3f".format(deltaNs / 1_000_000.0)} ms"

private fun formatExposure(exposureNs: Long?): String =
    exposureNs?.let { "${"%.2f".format(it / 1_000_000.0)} ms" } ?: "—"
