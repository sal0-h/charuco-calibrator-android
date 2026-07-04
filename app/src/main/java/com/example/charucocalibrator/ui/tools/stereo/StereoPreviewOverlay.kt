package com.example.charucocalibrator.ui.tools.stereo

import android.view.TextureView
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun StereoPreviewOverlay(
    leftPreview: TextureView,
    rightPreview: TextureView,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        AndroidView(
            factory = { leftPreview },
            modifier = Modifier
                .weight(1f)
                .padding(end = 4.dp)
        )
        AndroidView(
            factory = { rightPreview },
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
        )
    }
}
