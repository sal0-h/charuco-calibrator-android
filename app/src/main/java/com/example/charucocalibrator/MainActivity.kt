package com.example.charucocalibrator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
    val coroutineScope = rememberCoroutineScope()
    var report by remember { mutableStateOf<CameraReport?>(null) }
    var diagnosticsError by remember { mutableStateOf<String?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

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
        CameraPreview(modifier = Modifier.fillMaxSize())
        Text(
            text = "Camera running",
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
            report = report,
            error = diagnosticsError,
            exportMessage = exportMessage,
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
                .fillMaxHeight(0.58f)
        )
    }
}

@Composable
private fun DiagnosticsPanel(
    report: CameraReport?,
    error: String?,
    exportMessage: String?,
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
            text = "Camera2 diagnostics",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall
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
private fun CameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(context, lifecycleOwner, previewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var cameraProvider: ProcessCameraProvider? = null
        var disposed = false

        cameraProviderFuture.addListener(
            {
                try {
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider

                    if (!disposed) {
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview
                        )
                    }
                } catch (exception: Exception) {
                    Log.e(TAG, "Unable to bind camera preview", exception)
                }
            },
            ContextCompat.getMainExecutor(context)
        )

        onDispose {
            disposed = true
            cameraProvider?.unbindAll()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private const val TAG = "CharucoCalibrator"
