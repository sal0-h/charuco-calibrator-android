package com.example.charucocalibrator.ui.tools.arcore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.charucocalibrator.arcore.ArCoreSnapshotResult

@Composable
fun ArCoreSnapshotPanel(
    lastExport: ArCoreSnapshotResult?,
    exportInProgress: Boolean,
    exportError: String? = null,
    onExport: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text(
            text = "Snapshot export",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = "After export, tap Share to send files to Drive, email, or a PC — no adb required.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            Button(
                onClick = onExport,
                enabled = !exportInProgress,
            ) {
                Text(if (exportInProgress) "Exporting…" else "Export snapshot")
            }
            if (lastExport != null) {
                OutlinedButton(
                    onClick = onShare,
                    enabled = !exportInProgress,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text("Share files")
                }
            }
        }
        exportError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        lastExport?.let { result ->
            Text(
                text = "Folder: ${result.exportDir.absolutePath}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "Depth in export: raw=${result.rawDepthAvailable} " +
                    "smoothed=${result.smoothedDepthAvailable} " +
                    "confidence=${result.confidenceAvailable}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = "Files: ${result.filesWritten.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "Linux adb (optional): sudo pacman -S android-tools",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
