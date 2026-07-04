package com.example.charucocalibrator.ui.tools.stereo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.charucocalibrator.AcceptanceConfig
import com.example.charucocalibrator.OpenCvInitializer
import com.example.charucocalibrator.R
import com.example.charucocalibrator.stereo.EnumerationResult
import com.example.charucocalibrator.stereo.StereoBoardPairStore
import com.example.charucocalibrator.stereo.StereoCalibrationEngine
import com.example.charucocalibrator.stereo.StereoDisparityEngine
import com.example.charucocalibrator.stereo.StereoDualStreamController
import com.example.charucocalibrator.stereo.StereoLiveState
import com.example.charucocalibrator.stereo.StereoPairExporter
import com.example.charucocalibrator.stereo.StereoPairProbe
import com.example.charucocalibrator.stereo.StereoPhysicalCameraEnumerator
import com.example.charucocalibrator.stereo.StereoProbeReportExporter
import com.example.charucocalibrator.stereo.StereoStreamState
import com.example.charucocalibrator.stereo.model.StereoCalibrationResult
import com.example.charucocalibrator.stereo.model.StereoPairProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun StereoProbeScreen(
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
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.stereo_opencv_unavailable))
            }
        }
        hasCameraPermission -> {
            StereoProbeContent(modifier = modifier, onBack = onBack)
        }
        else -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.stereo_grant_camera_permission))
                }
            }
        }
    }
}

@Composable
private fun StereoProbeContent(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var enumeration by remember { mutableStateOf<EnumerationResult?>(null) }
    var liveState by remember { mutableStateOf(StereoLiveState()) }
    var probeResults by remember { mutableStateOf<List<StereoPairProbeResult>>(emptyList()) }
    var selectedPairIndex by remember { mutableIntStateOf(0) }
    var showPreviews by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var calibrationPairCount by remember { mutableIntStateOf(0) }
    var calibrationResult by remember { mutableStateOf<StereoCalibrationResult?>(null) }
    var latestPairDirectory by remember { mutableStateOf<File?>(null) }
    var probing by remember { mutableStateOf(false) }

    val boardPairStore = remember { StereoBoardPairStore(context) }
    val calibrationEngine = remember { StereoCalibrationEngine() }
    val disparityEngine = remember { StereoDisparityEngine() }
    val pairProbe = remember { StereoPairProbe(context) }

    val leftPreview = remember { TextureView(context) }
    val rightPreview = remember { TextureView(context) }

    val controller = remember {
        StereoDualStreamController(context) { state ->
            liveState = state
        }
    }

    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> controller.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.release()
        }
    }

    DisposableEffect(Unit) {
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                StereoPhysicalCameraEnumerator.enumerate(context)
            }
            enumeration = result
            calibrationPairCount = boardPairStore.count()
        }
        onDispose { }
    }

    val cameras = enumeration?.cameras.orEmpty()
    val workingPairs = remember(probeResults) { probeResults.filter { it.success } }
    val selectedProbe = workingPairs.getOrNull(selectedPairIndex)
        ?: probeResults.firstOrNull { it.success }

    fun cameraSummary(): String =
        if (cameras.isEmpty()) {
            enumeration?.error ?: "No physical cameras enumerated"
        } else {
            cameras.joinToString { "${it.physicalCameraId}(${it.lensType.label}, ${it.focalLengthMm}mm)" }
        }

    fun startSelectedPair() {
        val result = selectedProbe ?: run {
            statusMessage = "Run pair probe first or select a working pair"
            return
        }
        val resolution = result.resolution ?: run {
            statusMessage = "Selected pair has no working resolution"
            return
        }
        controller.start(
            leftId = result.leftPhysicalCameraId,
            rightId = result.rightPhysicalCameraId,
            resolution = resolution,
            label = result.pairLabel,
            enablePreviews = showPreviews,
            leftPreview = if (showPreviews) leftPreview else null,
            rightPreview = if (showPreviews) rightPreview else null,
            reason = result.fallbackReason
        )
        statusMessage = "Starting ${result.pairLabel} at ${resolution.width}x${resolution.height}"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.nav_back)
                )
            }
            Text(
                text = stringResource(R.string.stereo_probe_title),
                style = MaterialTheme.typography.titleLarge
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            StereoStatusPanel(
                liveState = liveState,
                enumerationError = enumeration?.error,
                physicalCameraSummary = cameraSummary(),
                probeResults = probeResults,
                calibrationPairCount = calibrationPairCount,
                calibrationMinPairs = AcceptanceConfig.MIN_FRAMES_FOR_CALIBRATION,
                calibrationResult = calibrationResult,
                statusMessage = statusMessage
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.stereo_show_previews),
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = showPreviews,
                    onCheckedChange = { enabled ->
                        showPreviews = enabled
                        if (liveState.streamState == StereoStreamState.STREAMING) {
                            controller.stop()
                            startSelectedPair()
                        }
                    }
                )
            }

            if (showPreviews) {
                StereoPreviewOverlay(
                    leftPreview = leftPreview,
                    rightPreview = rightPreview
                )
            }

            Button(
                onClick = {
                    if (probing) return@Button
                    probing = true
                    statusMessage = "Probing physical camera pairs..."
                    scope.launch {
                        val results = withContext(Dispatchers.IO) {
                            pairProbe.probeAll(cameras)
                        }
                        probeResults = results
                        probing = false
                        val firstWorking = results.indexOfFirst { it.success }
                        if (firstWorking >= 0) selectedPairIndex = firstWorking
                        statusMessage = if (results.any { it.success }) {
                            "Probe complete: ${results.count { it.success }} working pair(s)"
                        } else {
                            "Probe complete: no working pairs. HAL rejected simultaneous physical streams; try another pair or lower resolution."
                        }
                    }
                },
                enabled = cameras.size >= 2 && !probing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.stereo_run_probe))
            }

            if (workingPairs.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Button(
                        onClick = {
                            selectedPairIndex = (selectedPairIndex - 1).coerceAtLeast(0)
                        },
                        enabled = selectedPairIndex > 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Prev pair")
                    }
                    Button(
                        onClick = {
                            selectedPairIndex = (selectedPairIndex + 1)
                                .coerceAtMost(workingPairs.lastIndex)
                        },
                        enabled = selectedPairIndex < workingPairs.lastIndex,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    ) {
                        Text("Next pair")
                    }
                }
            }

            Button(
                onClick = { startSelectedPair() },
                enabled = selectedProbe != null && liveState.streamState != StereoStreamState.STREAMING,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.stereo_start_streams))
            }

            Button(
                onClick = { controller.stop() },
                enabled = liveState.streamState == StereoStreamState.STREAMING,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.stereo_stop_streams))
            }

            Button(
                onClick = {
                    val (left, right) = controller.getLatestFrames()
                    if (left == null || right == null) {
                        statusMessage = "No frames available to save"
                        return@Button
                    }
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            StereoPairExporter.export(
                                context = context,
                                leftFrame = left,
                                rightFrame = right,
                                logicalCameraId = enumeration?.logicalCameraId ?: "0",
                                leftPhysicalCameraId = liveState.leftPhysicalId ?: "",
                                rightPhysicalCameraId = liveState.rightPhysicalId ?: "",
                                pairLabel = liveState.pairLabel ?: "unknown",
                                oisDisabled = liveState.oisDisabled,
                                afPolicy = liveState.afPolicy
                            )
                        }
                        statusMessage = result.fold(
                            onSuccess = { export ->
                                latestPairDirectory = export.directory
                                "Saved stereo pair to ${export.directory.absolutePath} " +
                                    "(delta=${export.metadata.timestampDeltaNs}ns)"
                            },
                            onFailure = { error ->
                                error.message ?: "Failed to save stereo pair"
                            }
                        )
                    }
                },
                enabled = liveState.streamState == StereoStreamState.STREAMING,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.stereo_save_pair))
            }

            Button(
                onClick = {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            StereoProbeReportExporter.export(
                                context = context,
                                logicalCameraId = enumeration?.logicalCameraId ?: "0",
                                physicalCameras = cameras,
                                probedPairs = probeResults
                            )
                        }
                        statusMessage = "Exported probe report: ${file.absolutePath}"
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.stereo_export_probe_report))
            }

            Button(
                onClick = {
                    val (left, right) = controller.getLatestFrames()
                    if (left == null || right == null) {
                        statusMessage = "No frames available for board pair"
                        return@Button
                    }
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            boardPairStore.savePair(left, right)
                        }
                        result.fold(
                            onSuccess = { record ->
                                calibrationPairCount = boardPairStore.count()
                                statusMessage =
                                    "Saved board pair ${record.index} " +
                                        "(L=${record.leftCornerCount}, R=${record.rightCornerCount}, " +
                                        "delta=${record.timestampDeltaNs}ns)"
                            },
                            onFailure = { error ->
                                statusMessage = error.message ?: "Board pair rejected"
                            }
                        )
                    }
                },
                enabled = liveState.streamState == StereoStreamState.STREAMING,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.stereo_save_board_pair))
            }

            Button(
                onClick = {
                    scope.launch {
                        val pairs = withContext(Dispatchers.IO) { boardPairStore.listPairs() }
                        val result = withContext(Dispatchers.Default) {
                            calibrationEngine.calibrate(
                                pairs = pairs,
                                leftPhysicalCameraId = liveState.leftPhysicalId ?: selectedProbe?.leftPhysicalCameraId.orEmpty(),
                                rightPhysicalCameraId = liveState.rightPhysicalId ?: selectedProbe?.rightPhysicalCameraId.orEmpty(),
                                logicalCameraId = enumeration?.logicalCameraId ?: "0"
                            )
                        }
                        calibrationResult = result
                        if (result.success) {
                            withContext(Dispatchers.IO) {
                                calibrationEngine.exportResult(context, result)
                            }
                            statusMessage =
                                "Stereo calibration exported (RMS=${result.stereoRms}, baseline=${result.baselineM} m)"
                        } else {
                            statusMessage = result.statusMessage
                        }
                    }
                },
                enabled = calibrationPairCount >= AcceptanceConfig.MIN_FRAMES_FOR_CALIBRATION,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.stereo_calibrate))
            }

            Button(
                onClick = {
                    val calibration = calibrationResult
                    val pairDir = latestPairDirectory
                    if (calibration == null || !calibration.success) {
                        statusMessage = "Run stereo calibration first"
                        return@Button
                    }
                    val leftFile = pairDir?.resolve("left.jpg")
                    val rightFile = pairDir?.resolve("right.jpg")
                    if (leftFile == null || rightFile == null || !leftFile.isFile || !rightFile.isFile) {
                        statusMessage = "Save a stereo pair first for disparity input"
                        return@Button
                    }
                    scope.launch {
                        val result = withContext(Dispatchers.Default) {
                            disparityEngine.computeAndExport(
                                context = context,
                                leftImageFile = leftFile,
                                rightImageFile = rightFile,
                                calibration = calibration
                            )
                        }
                        statusMessage = if (result.success) {
                            "Disparity exported: ${result.pngFile?.absolutePath}"
                        } else {
                            result.statusMessage
                        }
                    }
                },
                enabled = calibrationResult?.success == true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.stereo_compute_disparity))
            }

            Button(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { boardPairStore.clear() }
                        calibrationPairCount = 0
                        calibrationResult = null
                        statusMessage = "Calibration session cleared"
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp)
            ) {
                Text(stringResource(R.string.stereo_clear_calibration))
            }

            Text(
                text = stringResource(R.string.stereo_depth_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
