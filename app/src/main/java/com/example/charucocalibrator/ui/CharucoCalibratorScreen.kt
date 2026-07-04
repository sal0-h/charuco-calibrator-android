package com.example.charucocalibrator.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.charucocalibrator.AcceptanceConfig
import com.example.charucocalibrator.Camera2Controller
import com.example.charucocalibrator.CameraDiagnostics
import com.example.charucocalibrator.CameraReport
import com.example.charucocalibrator.CameraStreamConfiguration
import com.example.charucocalibrator.DEFAULT_CAMERA_ID
import com.example.charucocalibrator.FrameAnalysisSnapshot
import com.example.charucocalibrator.OpenCvInitializer
import com.example.charucocalibrator.R
import com.example.charucocalibrator.SavedFrameFiles
import com.example.charucocalibrator.aeStateName
import com.example.charucocalibrator.afStateName
import com.example.charucocalibrator.awbStateName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CharucoCalibratorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(context.hasCameraPermission())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    when {
        !OpenCvInitializer.isInitialized() -> {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Text(
                    text = "OpenCV failed to load. Reinstall the app and try again.",
                    color = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.85f))
                        .padding(16.dp)
                )
            }
        }
        hasCameraPermission -> {
            CharucoCalibratorContent(
                modifier = modifier,
                onBack = onBack
            )
        }
        else -> {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant camera permission")
                }
            }
        }
    }
}

@Composable
private fun CharucoCalibratorContent(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
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
    var analysisSnapshot by remember { mutableStateOf<FrameAnalysisSnapshot?>(null) }
    var pipelineStatus by remember { mutableStateOf("Waiting for camera surface...") }
    var frameSaveMessage by remember { mutableStateOf<String?>(null) }
    var latestSavedFrame by remember { mutableStateOf<SavedFrameFiles?>(null) }
    var debugExportMessage by remember { mutableStateOf<String?>(null) }

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
                        latestSavedFrame = it
                        "Test frame saved successfully"
                    },
                    onFailure = {
                        Log.e(TAG, "Unable to save test frame", it)
                        "Frame save failed: ${it.message ?: it.javaClass.simpleName}"
                    }
                )
            },
            onAnalysisSnapshot = { analysisSnapshot = it }
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
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
                .size(40.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.nav_back),
                tint = Color.White
            )
        }
        DiagnosticsPanel(
            streamConfiguration = streamConfiguration,
            frameCount = frameCount,
            analysisSnapshot = analysisSnapshot,
            pipelineStatus = pipelineStatus,
            frameSaveMessage = frameSaveMessage,
            latestSavedFrame = latestSavedFrame,
            report = report,
            error = diagnosticsError,
            exportMessage = exportMessage,
            debugExportMessage = debugExportMessage,
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
            onStartAutoCapture = { cameraController.startAutoCapture() },
            onStopAutoCapture = { cameraController.stopAutoCapture() },
            onClearAcceptedFrames = { cameraController.clearAcceptedFrames() },
            onStartNewSession = {
                val sessionId = cameraController.startNewSession()
                debugExportMessage = "New session: $sessionId"
            },
            onExportDebugOverlays = {
                coroutineScope.launch {
                    debugExportMessage = "Exporting ID overlays..."
                    val result = withContext(Dispatchers.IO) {
                        runCatching { cameraController.exportDebugOverlays() }
                    }
                    debugExportMessage = result.fold(
                        onSuccess = { files ->
                            if (files.isEmpty()) {
                                "No accepted frames in current session to export"
                            } else {
                                "Saved ${files.size} overlay(s):\n" +
                                    files.joinToString("\n") { it.absolutePath }
                            }
                        },
                        onFailure = {
                            Log.e(TAG, "Unable to export debug overlays", it)
                            "Export failed: ${it.message ?: it.javaClass.simpleName}"
                        }
                    )
                }
            },
            onRunCalibration = { cameraController.runCalibration() },
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
    analysisSnapshot: FrameAnalysisSnapshot?,
    pipelineStatus: String,
    frameSaveMessage: String?,
    latestSavedFrame: SavedFrameFiles?,
    report: CameraReport?,
    error: String?,
    exportMessage: String?,
    debugExportMessage: String?,
    onSaveTestFrame: () -> Unit,
    onExport: () -> Unit,
    onStartAutoCapture: () -> Unit,
    onStopAutoCapture: () -> Unit,
    onClearAcceptedFrames: () -> Unit,
    onStartNewSession: () -> Unit,
    onExportDebugOverlays: () -> Unit,
    onRunCalibration: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val label = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.88f))
            .navigationBarsPadding()
    ) {
        Text(
            text = "Calibration capture",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
        ) {
            Text(
                text = buildLiveStatsText(streamConfiguration, frameCount, analysisSnapshot),
                color = Color.White,
                style = mono
            )
            Text(
                text = pipelineStatus,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            SectionLabel("Actions")
            Button(
                onClick = onSaveTestFrame,
                enabled = streamConfiguration != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save test frame")
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onStartAutoCapture,
                    enabled = streamConfiguration != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start auto")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onStopAutoCapture,
                    enabled = streamConfiguration != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Stop auto")
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onClearAcceptedFrames,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear session")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onStartNewSession,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("New session")
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onExportDebugOverlays,
                    enabled = (analysisSnapshot?.acceptedFrameCount ?: 0) > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Export ID overlays")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onRunCalibration,
                    enabled = (analysisSnapshot?.acceptedFrameCount ?: 0) >= AcceptanceConfig.MIN_FRAMES_FOR_CALIBRATION &&
                        analysisSnapshot?.isCalibrating != true,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (analysisSnapshot?.isCalibrating == true) {
                            "Calibrating..."
                        } else {
                            "Calibrate session"
                        }
                    )
                }
            }

            analysisSnapshot?.let { snapshot ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = buildAcceptanceText(snapshot),
                    color = Color.White,
                    style = label
                )
            }

            analysisSnapshot?.calibrationStatus?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = buildCalibrationText(analysisSnapshot),
                    color = Color.White,
                    style = label
                )
            }

            debugExportMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            frameSaveMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = it, color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            latestSavedFrame?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = buildSavedFrameText(it),
                    color = Color.White,
                    style = label
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.25f))
            Spacer(modifier = Modifier.height(8.dp))
            SectionLabel("Camera2 diagnostics")
            Text(
                text = when {
                    error != null -> "Diagnostics failed: $error"
                    report == null -> "Loading camera characteristics..."
                    else -> report.toDisplayText()
                },
                color = if (error == null) Color.White else MaterialTheme.colorScheme.error,
                style = mono,
                modifier = Modifier.padding(vertical = 8.dp)
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
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.85f),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

private fun buildLiveStatsText(
    streamConfiguration: CameraStreamConfiguration?,
    frameCount: Long,
    analysisSnapshot: FrameAnalysisSnapshot?
): String = buildString {
    appendLine("camera_id: ${streamConfiguration?.cameraId ?: DEFAULT_CAMERA_ID}")
    appendLine("analysis: ${streamConfiguration?.analysisSize?.display() ?: "configuring"}")
    appendLine("raw frames: $frameCount")
    analysisSnapshot?.let { snapshot ->
        appendLine("processed: ${snapshot.processedFrameCount}")
        appendLine("sharpness: ${snapshot.sharpness?.let { "%.1f".format(it) } ?: "n/a"}")
        val cornerCount = snapshot.charucoCornerCount
        val minCorners = snapshot.minCharucoCornersRequired
        val cornersLine = "ChArUco corners: $cornerCount (min $minCorners)"
        appendLine(
            if (cornerCount < minCorners && snapshot.detectionStatus != "idle") {
                "$cornersLine  ← below minimum"
            } else {
                cornersLine
            }
        )
        appendLine("markers: ${snapshot.markerCount}")
        appendLine("detection: ${snapshot.detectionStatus}")
        snapshot.rejectionReason?.let { appendLine("rejection: $it") }
        snapshot.captureMetadata?.let { metadata ->
            appendLine("ISO: ${metadata.isoSensitivity ?: "n/a"}")
            appendLine(
                "exposure: ${
                    metadata.exposureTimeMs?.let { "%.2f ms".format(it) } ?: "n/a"
                }"
            )
            appendLine("focus dist: ${metadata.lensFocusDistance?.let { "%.3f".format(it) } ?: "n/a"}")
            appendLine(
                "AF/AE/AWB: ${afStateName(metadata.afState)} / " +
                    "${aeStateName(metadata.aeState)} / ${awbStateName(metadata.awbState)}"
            )
        }
        snapshot.captureStabilityStatus?.let { status ->
            appendLine("capture stability: $status")
            snapshot.referenceFocusDistance?.let {
                appendLine("reference focus: ${"%.3f".format(it)}")
            }
            snapshot.captureStabilityMessage?.let { appendLine("stability note: $it") }
        }
    }
}

private fun buildAcceptanceText(snapshot: FrameAnalysisSnapshot): String = buildString {
    snapshot.captureSessionId?.let { appendLine("session: $it") }
    appendLine(
        "accepted (session): ${snapshot.acceptedFrameCount}/${snapshot.maxAcceptedFrames}"
    )
    appendLine("auto capture: ${if (snapshot.autoCaptureActive) "on" else "off"}")
    snapshot.lastAcceptanceReason?.let { appendLine("last decision: $it") }
    snapshot.bboxAreaRatio?.let { append("coverage: ${"%.3f".format(it)}") }
}

private fun buildCalibrationText(snapshot: FrameAnalysisSnapshot): String = buildString {
    snapshot.calibrationStatus?.let { appendLine(it) }
    snapshot.calibrationReprojectionError?.let {
        appendLine("RMS: ${"%.3f".format(it)} px")
    }
    snapshot.calibrationMedianPerViewError?.let {
        appendLine("median: ${"%.3f".format(it)} px")
    }
    snapshot.calibrationP90PerViewError?.let {
        appendLine("p90: ${"%.3f".format(it)} px")
    }
    snapshot.calibrationSolverVariant?.let {
        appendLine("solver: $it")
    }
    snapshot.calibrationUsedFrames?.let { used ->
        val dropped = snapshot.calibrationDroppedFrames ?: 0
        appendLine("frames: $used used, $dropped dropped")
    }
    if (
        snapshot.calibrationFx != null &&
        snapshot.calibrationFy != null &&
        snapshot.calibrationCx != null &&
        snapshot.calibrationCy != null
    ) {
        appendLine(
            "fx/fy/cx/cy: ${"%.1f".format(snapshot.calibrationFx)} / " +
                "${"%.1f".format(snapshot.calibrationFy)} / " +
                "${"%.1f".format(snapshot.calibrationCx)} / " +
                "${"%.1f".format(snapshot.calibrationCy)}"
        )
    }
    snapshot.calibrationOutputPath?.let { appendLine("output: $it") }
}

private fun buildSavedFrameText(savedFrame: SavedFrameFiles): String = buildString {
    appendLine("Latest saved frame")
    appendLine("  saved: ${savedFrame.savedAtUtc}")
    appendLine("  dimensions: ${savedFrame.imageWidth}x${savedFrame.imageHeight}")
    appendLine("  image: ${savedFrame.imageFile.absolutePath}")
    append("  metadata: ${savedFrame.metadataFile.absolutePath}")
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
