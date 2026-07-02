package com.example.charucocalibrator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.charucocalibrator.ui.theme.CharucoCalibratorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CharucoCalibratorTheme {
                CameraApp(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun CameraApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(context.hasCameraPermission())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    if (hasCameraPermission) {
        CameraScreen(modifier = modifier)
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant camera permission")
            }
        }
    }
}

@Composable
private fun CameraScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val coroutineScope = rememberCoroutineScope()
    var report by remember { mutableStateOf<CameraReport?>(null) }
    var diagnosticsError by remember { mutableStateOf<String?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var streamConfiguration by remember {
        mutableStateOf<CameraStreamConfiguration?>(null)
    }
    var frameCount by remember { mutableLongStateOf(0L) }
    var pipelineStatus by remember { mutableStateOf("Waiting for camera surface...") }
    var frameSaveMessage by remember { mutableStateOf<String?>(null) }

    val cameraController = remember(applicationContext) {
        Camera2Controller(
            context = applicationContext,
            cameraId = DEFAULT_CAMERA_ID,
            onStreamConfigured = { streamConfiguration = it },
            onFrameCountChanged = { frameCount = it },
            onStatusChanged = { pipelineStatus = it },
            onFrameSaveResult = { result ->
                frameSaveMessage = result.fold(
                    onSuccess = {
                        "Saved image:\n${it.imageFile.absolutePath}\n" +
                            "Metadata:\n${it.metadataFile.absolutePath}"
                    },
                    onFailure = {
                        Log.e(TAG, "Unable to save test frame", it)
                        "Frame save failed: ${it.message ?: it.javaClass.simpleName}"
                    }
                )
            }
        )
    }

    LaunchedEffect(context) {
        val result = withContext(Dispatchers.Default) {
            runCatching { CameraDiagnostics.collect(context.applicationContext) }
        }
        result.fold(
            onSuccess = {
                report = it
                diagnosticsError = null
            },
            onFailure = {
                diagnosticsError = it.message ?: it.javaClass.simpleName
                Log.e(TAG, "Unable to collect camera diagnostics", it)
            }
        )
    }

    Box(modifier = modifier.background(Color.Black)) {
        Camera2Preview(
            controller = cameraController,
            modifier = Modifier.fillMaxSize()
        )
        Text(
            text = "Camera2 • camera_id $DEFAULT_CAMERA_ID",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        DiagnosticsPanel(
            streamConfiguration = streamConfiguration,
            frameCount = frameCount,
            pipelineStatus = pipelineStatus,
            frameSaveMessage = frameSaveMessage,
            report = report,
            error = diagnosticsError,
            exportMessage = exportMessage,
            onSaveTestFrame = {
                frameSaveMessage = if (cameraController.requestSaveNextFrame()) {
                    "Waiting for the next YUV frame..."
                } else {
                    "Camera is not ready or a frame save is already in progress"
                }
            },
            onExport = {
                report?.let { currentReport ->
                    coroutineScope.launch {
                        exportMessage = "Saving report..."
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                CameraDiagnostics.export(
                                    context.applicationContext,
                                    currentReport
                                )
                            }
                        }
                        exportMessage = result.fold(
                            onSuccess = { "Saved to:\n${it.absolutePath}" },
                            onFailure = {
                                Log.e(TAG, "Unable to export camera report", it)
                                "Export failed: ${it.message ?: it.javaClass.simpleName}"
                            }
                        )
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.70f)
        )
    }
}

@Composable
private fun DiagnosticsPanel(
    streamConfiguration: CameraStreamConfiguration?,
    frameCount: Long,
    pipelineStatus: String,
    frameSaveMessage: String?,
    report: CameraReport?,
    error: String?,
    exportMessage: String?,
    onSaveTestFrame: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.82f))
            .navigationBarsPadding()
            .padding(12.dp)
    ) {
        Text(
            text = "Calibration capture",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = buildString {
                appendLine("camera_id: ${streamConfiguration?.cameraId ?: DEFAULT_CAMERA_ID}")
                appendLine(
                    "analysis: ${streamConfiguration?.analysisSize?.display() ?: "configuring"}"
                )
                appendLine(
                    "preview: ${streamConfiguration?.previewSize?.display() ?: "configuring"}"
                )
                append("frames: $frameCount")
            },
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = pipelineStatus,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        Button(
            onClick = onSaveTestFrame,
            enabled = streamConfiguration != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save test frame")
        }
        frameSaveMessage?.let {
            Text(
                text = it,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Text(
            text = "Camera2 diagnostics",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            text = when {
                error != null -> "Diagnostics failed: $error"
                report == null -> "Loading camera characteristics..."
                else -> report.toDisplayText()
            },
            color = if (error == null) Color.White else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        )
        Button(
            onClick = onExport,
            enabled = report != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Export camera report")
        }
        exportMessage?.let {
            Text(
                text = it,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun Camera2Preview(
    controller: Camera2Controller,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val textureView = remember(context) { TextureView(context) }

    DisposableEffect(lifecycleOwner, controller, textureView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.start(textureView)
                Lifecycle.Event.ON_PAUSE -> controller.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            controller.start(textureView)
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.release()
        }
    }

    AndroidView(
        factory = { textureView },
        modifier = modifier
    )
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private const val TAG = "CharucoCalibrator"
