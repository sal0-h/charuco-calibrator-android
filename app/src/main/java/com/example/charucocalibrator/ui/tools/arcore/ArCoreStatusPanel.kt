package com.example.charucocalibrator.ui.tools.arcore

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
import com.example.charucocalibrator.arcore.model.ArCoreFrameState
import com.google.ar.core.TrackingState

@Composable
fun ArCoreStatusPanel(
    frameState: ArCoreFrameState,
    depthModeLabel: String,
    modifier: Modifier = Modifier,
) {
    val trackingColor = when (frameState.trackingState) {
        TrackingState.TRACKING.name -> Color(0xFF2E7D32)
        TrackingState.PAUSED.name -> Color(0xFFF9A825)
        else -> Color(0xFFC62828)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text(
            text = "Tracking",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = frameState.trackingState,
            style = MaterialTheme.typography.bodyLarge,
            color = trackingColor,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (frameState.trackingFailureReason != "NONE") {
            Text(
                text = "Failure: ${frameState.trackingFailureReason}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            text = "Depth mode: $depthModeLabel",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = if (frameState.sessionRunning) "Session: running" else "Session: paused",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
