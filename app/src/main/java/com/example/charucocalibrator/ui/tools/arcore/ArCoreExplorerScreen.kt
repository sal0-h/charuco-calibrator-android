package com.example.charucocalibrator.ui.tools.arcore

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.view.Display
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.charucocalibrator.R
import com.example.charucocalibrator.arcore.ArCoreAvailabilityStatus
import com.example.charucocalibrator.arcore.ArCoreCapabilityChecker
import com.example.charucocalibrator.arcore.ArCoreInstallResult
import com.example.charucocalibrator.arcore.ArCoreSessionController
import com.example.charucocalibrator.arcore.ArCorePreviewRenderer
import com.example.charucocalibrator.arcore.ArCoreSnapshotCapture
import com.example.charucocalibrator.arcore.ArCoreSnapshotExporter
import com.example.charucocalibrator.arcore.ArCoreSnapshotResult
import com.example.charucocalibrator.arcore.ArCoreSnapshotShare
import com.example.charucocalibrator.arcore.model.ArCoreFrameState
import com.example.charucocalibrator.arcore.model.DepthImageData
import com.example.charucocalibrator.arcore.model.DepthOverlayMode
import com.example.charucocalibrator.arcore.model.DepthSourceToggle
import kotlinx.coroutines.launch

@Composable
fun ArCoreExplorerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(context.hasCameraPermission())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    var availability by remember { mutableStateOf(ArCoreCapabilityChecker.checkAvailability(context)) }
    var installMessage by remember { mutableStateOf<String?>(null) }
    var sessionError by remember { mutableStateOf<String?>(null) }
    var frameState by remember { mutableStateOf(ArCoreFrameState()) }
    var overlayMode by remember { mutableStateOf(DepthOverlayMode.RawDepthHeatmap) }
    var depthSource by remember { mutableStateOf(DepthSourceToggle.Smoothed) }
    var overlayOpacity by remember { mutableStateOf(ArCoreSessionController.DEFAULT_OVERLAY_OPACITY) }
    var confidenceThreshold by remember { mutableStateOf(ArCoreSessionController.DEFAULT_CONFIDENCE_THRESHOLD) }
    var lastExport by remember { mutableStateOf<ArCoreSnapshotResult?>(null) }
    var exportInProgress by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var glSurfaceView by remember { mutableStateOf<GLSurfaceView?>(null) }

    val sessionController = remember {
        ArCoreSessionController(
            context = context,
            onFrameState = { state -> frameState = state },
            onError = { message -> sessionError = message },
        )
    }
    val renderer = remember(sessionController) { ArCorePreviewRenderer(sessionController) }
    val display = context.defaultDisplay()

    SideEffect {
        sessionController.setDisplayRotation(display.rotation)
        sessionController.setOverlaySettings(
            overlayMode = overlayMode,
            depthSource = depthSource,
            opacity = overlayOpacity,
            confidenceThreshold = confidenceThreshold,
        )
    }

    DisposableEffect(lifecycleOwner, hasCameraPermission, availability) {
        if (!hasCameraPermission || availability != ArCoreAvailabilityStatus.Supported) {
            return@DisposableEffect onDispose { }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> sessionController.resume(display)
                Lifecycle.Event.ON_PAUSE -> sessionController.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sessionController.pause()
            Thread { sessionController.close() }.start()
        }
    }

    val charucoDiff = rememberCharucoDiff(context, frameState)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.nav_back),
                )
            }
            Text(
                text = stringResource(R.string.arcore_explorer_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            when {
                !hasCameraPermission -> {
                    PermissionPrompt(
                        onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    )
                }
                availability == ArCoreAvailabilityStatus.UnsupportedDevice -> {
                    StatusMessage("This device is not ARCore compatible.")
                }
                availability == ArCoreAvailabilityStatus.SupportedApkUpdateRequired -> {
                    InstallPrompt(
                        message = "Google Play Services for AR is missing or too old. Install or update it to continue.",
                        onInstall = {
                            activity?.let { act ->
                                when (val result = ArCoreCapabilityChecker.requestInstall(act)) {
                                    is ArCoreInstallResult.Installed -> {
                                        availability = ArCoreCapabilityChecker.checkAvailability(context)
                                    }
                                    is ArCoreInstallResult.InstallRequested -> {
                                        installMessage = "ARCore install requested. Return after installation."
                                    }
                                    is ArCoreInstallResult.UserDeclined -> {
                                        installMessage = result.message
                                    }
                                    is ArCoreInstallResult.Error -> {
                                        installMessage = result.message
                                    }
                                }
                            }
                        },
                    )
                    installMessage?.let { StatusMessage(it) }
                }
                availability != ArCoreAvailabilityStatus.Supported -> {
                    StatusMessage("ARCore availability unknown. Try again or update ARCore.")
                }
                else -> {
                    sessionError?.let { StatusMessage(it) }

                    Text(
                        text = stringResource(R.string.arcore_intrinsics_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                GLSurfaceView(ctx).apply {
                                    preserveEGLContextOnPause = true
                                    setEGLContextClientVersion(2)
                                    setRenderer(renderer)
                                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                                    glSurfaceView = this
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    ArCoreOverlayControlSection(
                        overlayMode = overlayMode,
                        depthSource = depthSource,
                        rawAvailable = frameState.rawDepth != null,
                        smoothedAvailable = frameState.smoothedDepth != null,
                        selectedDepth = selectedDepth(frameState, depthSource),
                        overlayOpacity = overlayOpacity,
                        confidenceThreshold = confidenceThreshold,
                        onOverlayModeChange = { overlayMode = it },
                        onDepthSourceChange = { depthSource = it },
                        onOpacityChange = { overlayOpacity = it },
                        onConfidenceThresholdChange = { confidenceThreshold = it },
                        modifier = Modifier.padding(top = 12.dp),
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    ArCoreStatusPanel(
                        frameState = frameState,
                        depthModeLabel = frameState.depthModeLabel,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )

                    ArCoreDiagnosticsPanel(
                        frameState = frameState,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )

                    ArCoreIntrinsicsPanel(
                        frameState = frameState,
                        charucoDiff = charucoDiff,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )

                    DepthStatsPanel(frameState = frameState)

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    ArCoreSnapshotPanel(
                        lastExport = lastExport,
                        exportInProgress = exportInProgress,
                        exportError = exportError,
                        onExport = {
                            val surfaceView = glSurfaceView
                            if (surfaceView == null) {
                                exportError = "Camera preview not ready yet."
                                return@ArCoreSnapshotPanel
                            }
                            if (!frameState.sessionRunning) {
                                exportError = "ARCore session is not running."
                                return@ArCoreSnapshotPanel
                            }
                            scope.launch {
                                exportInProgress = true
                                exportError = null
                                try {
                                    val result = ArCoreSnapshotCapture.captureAndExport(
                                        context = context,
                                        surfaceView = surfaceView,
                                        sessionController = sessionController,
                                        mode = overlayMode,
                                        source = depthSource,
                                        opacity = overlayOpacity,
                                        confidenceThreshold = confidenceThreshold,
                                    )
                                    lastExport = result
                                    if (!result.rawDepthAvailable &&
                                        !result.smoothedDepthAvailable &&
                                        !result.confidenceAvailable
                                    ) {
                                        exportError =
                                            "Exported JSON only — no depth/confidence in this frame. " +
                                                "Wait for valid depth stats, then export again."
                                    }
                                } catch (error: Exception) {
                                    exportError = error.message ?: error.javaClass.simpleName
                                } finally {
                                    exportInProgress = false
                                }
                            }
                        },
                        onShare = {
                            lastExport?.let { result ->
                                ArCoreSnapshotShare.shareExport(context, result)
                            }
                        },
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Column(modifier = Modifier.padding(vertical = 24.dp)) {
        Text("Camera permission is required for ARCore Explorer.")
        Button(onClick = onRequest, modifier = Modifier.padding(top = 12.dp)) {
            Text("Grant camera permission")
        }
    }
}

@Composable
private fun InstallPrompt(message: String, onInstall: () -> Unit) {
    Column(modifier = Modifier.padding(vertical = 24.dp)) {
        Text(message)
        Button(onClick = onInstall, modifier = Modifier.padding(top = 12.dp)) {
            Text("Install ARCore")
        }
    }
}

@Composable
private fun StatusMessage(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun ArCoreOverlayControlSection(
    overlayMode: DepthOverlayMode,
    depthSource: DepthSourceToggle,
    rawAvailable: Boolean,
    smoothedAvailable: Boolean,
    selectedDepth: DepthImageData?,
    overlayOpacity: Float,
    confidenceThreshold: Int,
    onOverlayModeChange: (DepthOverlayMode) -> Unit,
    onDepthSourceChange: (DepthSourceToggle) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onConfidenceThresholdChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.arcore_overlay_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.arcore_overlay_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "Overlay mode",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            DepthOverlayMode.entries.forEach { mode ->
                FilterChip(
                    selected = overlayMode == mode,
                    onClick = { onOverlayModeChange(mode) },
                    label = { Text(mode.label()) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        Text(
            text = "Depth read path (export always captures both when available)",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            FilterChip(
                selected = depthSource == DepthSourceToggle.Raw,
                onClick = { onDepthSourceChange(DepthSourceToggle.Raw) },
                enabled = rawAvailable,
                label = { Text("Raw") },
                modifier = Modifier.padding(end = 8.dp),
            )
            FilterChip(
                selected = depthSource == DepthSourceToggle.Smoothed,
                onClick = { onDepthSourceChange(DepthSourceToggle.Smoothed) },
                enabled = smoothedAvailable,
                label = { Text("Smoothed") },
            )
        }
        selectedDepth?.let { depth ->
            Text(
                text = "Color scale: ${"%.2f".format(depth.scaleLowM)}m → ${"%.2f".format(depth.scaleHighM)}m " +
                    "(2–98% current frame)",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Text(
            text = "Opacity: ${"%.0f".format(overlayOpacity * 100f)}%",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp),
        )
        Slider(
            value = overlayOpacity,
            onValueChange = onOpacityChange,
            valueRange = 0.15f..0.85f,
            steps = 13,
        )
        if (overlayMode == DepthOverlayMode.DepthMaskedByConfidence) {
            Text(
                text = "Confidence threshold: $confidenceThreshold / 255",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "Mask uses ARCore raw-depth confidence. If Smoothed is selected, only the color depth source changes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Slider(
                value = confidenceThreshold.toFloat(),
                onValueChange = { onConfidenceThresholdChange(it.toInt().coerceIn(0, 255)) },
                valueRange = 0f..255f,
                steps = 16,
            )
        }
    }
}

@Composable
private fun DepthStatsPanel(frameState: ArCoreFrameState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text(text = "Depth stats", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Heatmap uses 2–98% depth percentile scaling per frame (not metric-absolute).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        frameState.rawDepth?.let { raw ->
            Text(
                text = "Raw ${raw.width}×${raw.height}: valid=${"%.1f".format(raw.stats.validPixelFraction * 100)}% " +
                    "min=${"%.2f".format(raw.stats.minDepthM)}m " +
                    "median=${"%.2f".format(raw.stats.medianDepthM)}m " +
                    "max=${"%.2f".format(raw.stats.maxDepthM)}m",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        } ?: Text(
            text = "Raw depth: not available",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        frameState.smoothedDepth?.let { smoothed ->
            Text(
                text = "Smoothed ${smoothed.width}×${smoothed.height}: valid=${"%.1f".format(smoothed.stats.validPixelFraction * 100)}% " +
                    "median=${"%.2f".format(smoothed.stats.medianDepthM)}m",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        } ?: Text(
            text = "Smoothed depth: not available",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        frameState.confidence?.let { conf ->
            Text(
                text = "Confidence ${conf.width}×${conf.height}: mean=${"%.0f".format(conf.stats.meanConfidence)} " +
                    "high=${"%.1f".format(conf.stats.highConfidenceFraction * 100)}%",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        } ?: Text(
            text = "Confidence: not available",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun DepthOverlayMode.label(): String = when (this) {
    DepthOverlayMode.Off -> "Off"
    DepthOverlayMode.RawDepthHeatmap -> "Depth"
    DepthOverlayMode.Confidence -> "Conf"
    DepthOverlayMode.DepthMaskedByConfidence -> "Masked"
}

private fun selectedDepth(
    frameState: ArCoreFrameState,
    depthSource: DepthSourceToggle,
): DepthImageData? =
    when (depthSource) {
        DepthSourceToggle.Raw -> frameState.rawDepth
        DepthSourceToggle.Smoothed -> frameState.smoothedDepth ?: frameState.rawDepth
    }

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private fun Context.defaultDisplay(): Display {
    val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    return windowManager.defaultDisplay
}
