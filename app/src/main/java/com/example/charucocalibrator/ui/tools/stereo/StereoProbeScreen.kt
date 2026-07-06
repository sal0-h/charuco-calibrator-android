package com.example.charucocalibrator.ui.tools.stereo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.example.charucocalibrator.stereo.StereoCalibrationSessionKey
import com.example.charucocalibrator.stereo.StereoDisparityEngine
import com.example.charucocalibrator.stereo.StereoDiagnosticsLogger
import com.example.charucocalibrator.stereo.StereoDualStreamController
import com.example.charucocalibrator.stereo.StereoFrameSnapshot
import com.example.charucocalibrator.stereo.StereoLiveState
import com.example.charucocalibrator.stereo.StereoPairChoice
import com.example.charucocalibrator.stereo.StereoPairExporter
import com.example.charucocalibrator.stereo.StereoPairProbe
import com.example.charucocalibrator.stereo.StereoPairSelection
import com.example.charucocalibrator.stereo.StereoPhysicalCameraEnumerator
import com.example.charucocalibrator.stereo.StereoProbeProgress
import com.example.charucocalibrator.stereo.StereoProbeReportExporter
import com.example.charucocalibrator.stereo.StereoStreamState
import com.example.charucocalibrator.stereo.StereoSupportBundleExporter
import com.example.charucocalibrator.stereo.StereoWorkingConfig
import com.example.charucocalibrator.stereo.StereoWorkingConfigStore
import com.example.charucocalibrator.stereo.model.StereoCalibrationResult
import com.example.charucocalibrator.stereo.model.StereoPairProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

@Composable
fun StereoProbeScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasCameraPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    when {
        !OpenCvInitializer.isInitialized() -> StereoUnavailable(
            message = stringResource(R.string.stereo_opencv_unavailable),
            onBack = onBack,
            modifier = modifier
        )
        !hasCameraPermission -> StereoPermissionPrompt(
            onBack = onBack,
            onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            modifier = modifier
        )
        else -> StereoProbeContent(onBack = onBack, modifier = modifier)
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
    val workingConfigStore = remember { StereoWorkingConfigStore(context) }
    val diagnostics = remember { StereoDiagnosticsLogger(context) }

    var enumeration by remember { mutableStateOf<EnumerationResult?>(null) }
    var liveState by remember { mutableStateOf(StereoLiveState()) }
    var probeResults by remember { mutableStateOf<List<StereoPairProbeResult>>(emptyList()) }
    var probeProgress by remember { mutableStateOf<StereoProbeProgress?>(null) }
    var probing by remember { mutableStateOf(false) }
    var cancelRequested by remember { mutableStateOf(false) }
    var selectedPairKey by remember { mutableStateOf<String?>(null) }
    var showPreviews by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var captureBusy by remember { mutableStateOf(false) }
    var calibrationBusy by remember { mutableStateOf(false) }
    var disparityBusy by remember { mutableStateOf(false) }
    var supportBundleBusy by remember { mutableStateOf(false) }
    var calibrationPairCount by remember { mutableIntStateOf(0) }
    var calibrationResult by remember { mutableStateOf<StereoCalibrationResult?>(null) }
    var latestPairDirectory by remember { mutableStateOf<File?>(null) }
    var cachedWorkingConfig by remember {
        mutableStateOf(workingConfigStore.load())
    }

    val boardPairStore = remember { StereoBoardPairStore(context) }
    val calibrationEngine = remember { StereoCalibrationEngine() }
    val disparityEngine = remember { StereoDisparityEngine() }
    val pairProbe = remember { StereoPairProbe(context) }
    val leftPreview = remember { TextureView(context) }
    val rightPreview = remember { TextureView(context) }

    val controller = remember {
        StereoDualStreamController(context) { state ->
            liveState = state
            diagnostics.logLiveState(state)
            if (state.timestampDeltaNs != null &&
                state.leftFrameCount > 0L && state.rightFrameCount > 0L
            ) {
                val leftId = state.leftPhysicalId
                val rightId = state.rightPhysicalId
                val resolution = state.resolution
                if (leftId != null && rightId != null && resolution != null) {
                    val workingConfig = StereoWorkingConfig(leftId, rightId, resolution)
                    if (cachedWorkingConfig != workingConfig) {
                        workingConfigStore.save(workingConfig)
                        cachedWorkingConfig = workingConfig
                    }
                }
            }
            when (state.streamState) {
                StereoStreamState.STREAMING -> {
                    if (!statusIsError) {
                        statusMessage = STREAMING_STATUS_MESSAGE
                    }
                }
                StereoStreamState.FAILED -> {
                    statusMessage = state.halError ?: "The camera HAL rejected the stream."
                    statusIsError = true
                }
                else -> Unit
            }
        }
    }

    LaunchedEffect(statusMessage, statusIsError) {
        if (!statusIsError || liveState.streamState == StereoStreamState.FAILED) {
            return@LaunchedEffect
        }
        val displayedError = statusMessage
        delay(ERROR_STATUS_DURATION_MS)
        if (statusIsError && statusMessage == displayedError) {
            statusIsError = false
            statusMessage = if (liveState.streamState == StereoStreamState.STREAMING) {
                STREAMING_STATUS_MESSAGE
            } else {
                null
            }
        }
    }

    DisposableEffect(lifecycleOwner, controller, pairProbe, diagnostics) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                diagnostics.log("lifecycle_paused")
                pairProbe.cancel()
                probing = false
                controller.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            pairProbe.cancel()
            diagnostics.log("screen_disposed")
            controller.release()
            diagnostics.close()
        }
    }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.Default) {
            StereoPhysicalCameraEnumerator.enumerate(context)
        }
        enumeration = result
        diagnostics.log(
            "camera_enumeration_completed",
            JSONObject().apply {
                put("logical_camera_id", result.logicalCameraId)
                put("error", result.error ?: JSONObject.NULL)
                put("physical_cameras", JSONArray().apply {
                    result.cameras.forEach { put(it.toJson()) }
                })
            }
        )
    }

    val cameras = enumeration?.cameras.orEmpty()
    val pairChoices = remember(cameras) { StereoPairSelection.choices(cameras) }
    val supportedCachedConfig = remember(cameras, cachedWorkingConfig) {
        cachedWorkingConfig?.takeIf { it.isSupportedBy(cameras) }
    }
    val selectedChoice = pairChoices.firstOrNull { it.key == selectedPairKey }
    val selectedResolution = selectedChoice?.let {
        StereoPairSelection.streamResolution(it, probeResults, supportedCachedConfig)
    }
    val selectedCalibrationSession = selectedChoice?.let { choice ->
        selectedResolution?.let { resolution ->
            StereoCalibrationSessionKey(
                leftPhysicalCameraId = choice.left.physicalCameraId,
                rightPhysicalCameraId = choice.right.physicalCameraId,
                resolution = resolution
            )
        }
    }

    LaunchedEffect(pairChoices, supportedCachedConfig) {
        if (selectedPairKey !in pairChoices.map { it.key }) {
            selectedPairKey = supportedCachedConfig?.pairKey
                ?.takeIf { cachedKey -> pairChoices.any { it.key == cachedKey } }
                ?: pairChoices.firstOrNull()?.key
            if (supportedCachedConfig != null && statusMessage == null) {
                statusMessage = "Restored the last working physical pair and resolution."
                statusIsError = false
            }
        }
    }

    LaunchedEffect(cameras, cachedWorkingConfig, supportedCachedConfig) {
        if (cameras.isNotEmpty() && cachedWorkingConfig != null && supportedCachedConfig == null) {
            workingConfigStore.clear()
            cachedWorkingConfig = null
        }
    }

    LaunchedEffect(selectedCalibrationSession) {
        calibrationResult = null
        latestPairDirectory = null
        calibrationPairCount = selectedCalibrationSession?.let { sessionKey ->
            withContext(Dispatchers.IO) {
                boardPairStore.count(sessionKey)
            }
        } ?: 0
    }

    fun selectPair(choice: StereoPairChoice) {
        selectedPairKey = choice.key
        diagnostics.log(
            "pair_selected",
            JSONObject().apply {
                put("left_physical_camera_id", choice.left.physicalCameraId)
                put("right_physical_camera_id", choice.right.physicalCameraId)
                put("label", choice.label)
            }
        )
        statusMessage = "Selected ${choice.label}. Probe is optional; start streams when ready."
        statusIsError = false
    }

    fun startSelectedPair() {
        val choice = selectedChoice ?: run {
            statusMessage = "Select a physical camera pair first."
            statusIsError = true
            return
        }
        val resolution = selectedResolution ?: run {
            statusMessage = "The selected cameras have no shared 4:3 YUV resolution."
            statusIsError = true
            return
        }
        val probeResult = StereoPairSelection.resultFor(choice, probeResults)
        diagnostics.log(
            "stream_start_requested",
            JSONObject().apply {
                put("left_physical_camera_id", choice.left.physicalCameraId)
                put("right_physical_camera_id", choice.right.physicalCameraId)
                put("resolution", "${resolution.width}x${resolution.height}")
                put("previews_enabled", showPreviews)
                put("probe_fallback_reason", probeResult?.fallbackReason ?: JSONObject.NULL)
            }
        )
        controller.start(
            leftId = choice.left.physicalCameraId,
            rightId = choice.right.physicalCameraId,
            resolution = resolution,
            label = choice.label,
            enablePreviews = showPreviews,
            leftPreview = leftPreview.takeIf { showPreviews },
            rightPreview = rightPreview.takeIf { showPreviews },
            reason = probeResult?.fallbackReason
        )
        statusMessage = "Opening ${choice.label} at ${resolution.width}×${resolution.height}…"
        statusIsError = false
    }

    fun runProbe() {
        if (probing) return
        if (liveState.streamState == StereoStreamState.FAILED) {
            statusMessage = "Closing the failed camera session before probing…"
            statusIsError = false
            controller.stop { runProbe() }
            return
        }
        probing = true
        cancelRequested = false
        probeResults = emptyList()
        probeProgress = null
        statusMessage = "Testing prioritized physical camera pairs."
        statusIsError = false
        diagnostics.log(
            "probe_started",
            JSONObject().put("physical_camera_count", cameras.size)
        )
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                pairProbe.probeAll(
                    cameras = cameras,
                    onProgress = { progress ->
                        diagnostics.log(
                            "probe_attempt_started",
                            JSONObject().apply {
                                put("pair_index", progress.pairIndex)
                                put("pair_count", progress.pairCount)
                                put("left_physical_camera_id", progress.left.physicalCameraId)
                                put("right_physical_camera_id", progress.right.physicalCameraId)
                                put("resolution_index", progress.resolutionIndex)
                                put("resolution_count", progress.resolutionCount)
                                put(
                                    "resolution",
                                    "${progress.resolution.width}x${progress.resolution.height}"
                                )
                            }
                        )
                        scope.launch { probeProgress = progress }
                    },
                    onPairFinished = { result ->
                        diagnostics.log("probe_pair_finished", result.toJson())
                        scope.launch {
                            probeResults = probeResults
                                .filterNot {
                                    it.leftPhysicalCameraId == result.leftPhysicalCameraId &&
                                        it.rightPhysicalCameraId == result.rightPhysicalCameraId
                                } + result
                        }
                    }
                )
            }
            probeResults = results
            probing = false
            probeProgress = null
            if (cancelRequested) {
                diagnostics.log(
                    "probe_cancelled",
                    JSONObject().put("tested_pair_count", results.size)
                )
                statusMessage = "Probe cancelled. Tested ${results.size} pair(s); manual streaming remains available."
                statusIsError = false
                return@launch
            }

            val firstWorking = results.firstOrNull { it.success }
            diagnostics.log(
                "probe_finished",
                JSONObject().apply {
                    put("tested_pair_count", results.size)
                    put("working_pair_count", results.count { it.success })
                    put("first_working_pair", firstWorking?.toJson() ?: JSONObject.NULL)
                }
            )
            if (firstWorking != null) {
                firstWorking.resolution?.let { resolution ->
                    val workingConfig = StereoWorkingConfig(
                        leftPhysicalCameraId = firstWorking.leftPhysicalCameraId,
                        rightPhysicalCameraId = firstWorking.rightPhysicalCameraId,
                        resolution = resolution
                    )
                    workingConfigStore.save(workingConfig)
                    cachedWorkingConfig = workingConfig
                }
                pairChoices.firstOrNull { choice ->
                    choice.left.physicalCameraId == firstWorking.leftPhysicalCameraId &&
                        choice.right.physicalCameraId == firstWorking.rightPhysicalCameraId
                }?.let(::selectPair)
                statusMessage = "Probe complete: ${results.count { it.success }} working pair(s)."
                statusIsError = false
            } else {
                val report = runCatching {
                    withContext(Dispatchers.IO) {
                        StereoProbeReportExporter.export(
                            context = context,
                            logicalCameraId = enumeration?.logicalCameraId ?: "0",
                            physicalCameras = cameras,
                            probedPairs = results,
                            notes = "No tested pair produced synchronized dual physical streams."
                        )
                    }
                }.getOrNull()
                diagnostics.log(
                    "probe_no_compatible_pair",
                    JSONObject().put("report_path", report?.absolutePath ?: JSONObject.NULL)
                )
                statusMessage = buildString {
                    append("No compatible dual stream was found. You can still try a selected pair manually.")
                    report?.let { append(" Diagnostic report: ${it.absolutePath}") }
                }
                statusIsError = true
            }
        }
    }

    fun exportProbeReport() {
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    StereoProbeReportExporter.export(
                        context = context,
                        logicalCameraId = enumeration?.logicalCameraId ?: "0",
                        physicalCameras = cameras,
                        probedPairs = probeResults
                    )
                }
            }
            result.fold(
                onSuccess = {
                    diagnostics.log(
                        "probe_report_exported",
                        JSONObject().put("path", it.absolutePath)
                    )
                    statusMessage = "Probe report exported: ${it.absolutePath}"
                    statusIsError = false
                },
                onFailure = {
                    diagnostics.log(
                        "probe_report_export_failed",
                        JSONObject().put("error", it.message ?: it.javaClass.simpleName)
                    )
                    statusMessage = it.message ?: "Failed to export the probe report."
                    statusIsError = true
                }
            )
        }
    }

    fun exportSupportBundle() {
        if (supportBundleBusy) return
        supportBundleBusy = true
        diagnostics.log("support_bundle_export_requested")
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    StereoSupportBundleExporter.export(context)
                }
            }
            supportBundleBusy = false
            result.fold(
                onSuccess = {
                    diagnostics.log(
                        "support_bundle_exported",
                        JSONObject().apply {
                            put("path", it.absolutePath)
                            put("size_bytes", it.length())
                        }
                    )
                    statusMessage = "Support bundle exported: ${it.absolutePath}"
                    statusIsError = false
                },
                onFailure = {
                    diagnostics.log(
                        "support_bundle_export_failed",
                        JSONObject().put("error", it.message ?: it.javaClass.simpleName)
                    )
                    statusMessage = it.message ?: "Failed to export the support bundle."
                    statusIsError = true
                }
            )
        }
    }

    fun requestSynchronizedPair(
        onPair: (StereoFrameSnapshot, StereoFrameSnapshot) -> Unit
    ) {
        if (captureBusy) return
        captureBusy = true
        diagnostics.log("synchronized_capture_requested")
        statusMessage = "Capturing the next synchronized left/right frame pair…"
        statusIsError = false
        val accepted = controller.captureNextPair { result ->
            result.fold(
                onSuccess = { pair ->
                    diagnostics.log(
                        "synchronized_capture_received",
                        JSONObject().apply {
                            put("left_timestamp_ns", pair.first.sensorTimestampNs)
                            put("right_timestamp_ns", pair.second.sensorTimestampNs)
                            put(
                                "timestamp_delta_ns",
                                abs(pair.first.sensorTimestampNs - pair.second.sensorTimestampNs)
                            )
                        }
                    )
                    onPair(pair.first, pair.second)
                },
                onFailure = { error ->
                    diagnostics.log(
                        "synchronized_capture_failed",
                        JSONObject().put("error", error.message ?: error.javaClass.simpleName)
                    )
                    captureBusy = false
                    statusMessage = error.message ?: "Failed to capture a synchronized frame pair."
                    statusIsError = true
                }
            )
        }
        if (!accepted) {
            diagnostics.log("synchronized_capture_rejected_not_ready")
            captureBusy = false
            statusMessage = "Stereo streams are not ready for capture."
            statusIsError = true
        }
    }

    fun saveStereoPair() {
        val logicalCameraId = enumeration?.logicalCameraId ?: "0"
        val leftPhysicalCameraId = liveState.leftPhysicalId
        val rightPhysicalCameraId = liveState.rightPhysicalId
        val pairLabel = liveState.pairLabel
        val oisDisabled = liveState.oisDisabled
        val afPolicy = liveState.afPolicy
        if (leftPhysicalCameraId == null || rightPhysicalCameraId == null || pairLabel == null) {
            statusMessage = "Physical camera stream metadata is unavailable."
            statusIsError = true
            return
        }
        requestSynchronizedPair { left, right ->
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    StereoPairExporter.export(
                        context = context,
                        leftFrame = left,
                        rightFrame = right,
                        logicalCameraId = logicalCameraId,
                        leftPhysicalCameraId = leftPhysicalCameraId,
                        rightPhysicalCameraId = rightPhysicalCameraId,
                        pairLabel = pairLabel,
                        oisDisabled = oisDisabled,
                        afPolicy = afPolicy
                    )
                }
                captureBusy = false
                result.fold(
                    onSuccess = { export ->
                        diagnostics.log(
                            "stereo_pair_saved",
                            JSONObject().put("directory", export.directory.absolutePath)
                        )
                        latestPairDirectory = export.directory
                        statusMessage = "Stereo pair saved: ${export.directory.absolutePath}"
                        statusIsError = false
                    },
                    onFailure = { error ->
                        diagnostics.log(
                            "stereo_pair_save_failed",
                            JSONObject().put("error", error.message ?: error.javaClass.simpleName)
                        )
                        statusMessage = error.message ?: "Failed to save the stereo pair."
                        statusIsError = true
                    }
                )
            }
        }
    }

    fun saveBoardPair() {
        val sessionKey = selectedCalibrationSession
        if (sessionKey == null ||
            liveState.leftPhysicalId != sessionKey.leftPhysicalCameraId ||
            liveState.rightPhysicalId != sessionKey.rightPhysicalCameraId
        ) {
            statusMessage = "The active stream does not match the selected calibration session."
            statusIsError = true
            return
        }
        requestSynchronizedPair { left, right ->
            scope.launch {
                val (result, savedPairCount) = withContext(Dispatchers.IO) {
                    val saveResult = boardPairStore.savePair(left, right, sessionKey)
                    saveResult to saveResult.getOrNull()?.let {
                        runCatching { boardPairStore.count(sessionKey) }.getOrNull()
                    }
                }
                captureBusy = false
                result.fold(
                    onSuccess = { record ->
                        diagnostics.log(
                            "board_pair_saved",
                            JSONObject().apply {
                                put("index", record.index)
                                put("directory", record.directory.absolutePath)
                                put("left_corner_count", record.leftCornerCount)
                                put("right_corner_count", record.rightCornerCount)
                                put("timestamp_delta_ns", record.timestampDeltaNs ?: JSONObject.NULL)
                            }
                        )
                        calibrationPairCount = savedPairCount ?: calibrationPairCount
                        statusMessage = "Board pair ${record.index} saved: " +
                            "left ${record.leftCornerCount}, right ${record.rightCornerCount} corners."
                        statusIsError = false
                    },
                    onFailure = { error ->
                        diagnostics.log(
                            "board_pair_rejected",
                            JSONObject().put("error", error.message ?: error.javaClass.simpleName)
                        )
                        statusMessage = error.message ?: "Board pair rejected."
                        statusIsError = true
                    }
                )
            }
        }
    }

    val displayState = if (probing) StereoStreamState.PROBING else liveState.streamState
    val streamBusy = displayState == StereoStreamState.OPENING ||
        displayState == StereoStreamState.STREAMING ||
        displayState == StereoStreamState.PROBING
    val matchedPairReady = liveState.timestampDeltaNs != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        StereoTopBar(onBack)
        val visibleStatus = liveState.halError?.takeIf {
            liveState.streamState == StereoStreamState.FAILED
        } ?: statusMessage
        visibleStatus?.let {
            StereoStatusBanner(
                message = it,
                isError = liveState.streamState == StereoStreamState.FAILED || statusIsError
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(
                stringResource(R.string.stereo_quick_guide),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            StereoSetupPanel(
                cameras = cameras,
                enumerationError = enumeration?.error,
                probing = probing,
                progress = probeProgress,
                canRunProbe = cameras.size >= 2 &&
                    liveState.streamState != StereoStreamState.STREAMING &&
                    liveState.streamState != StereoStreamState.OPENING,
                canExportReport = probeResults.isNotEmpty(),
                supportBundleBusy = supportBundleBusy,
                onRunProbe = ::runProbe,
                onCancelProbe = {
                    cancelRequested = true
                    diagnostics.log("probe_cancel_requested")
                    pairProbe.cancel()
                    statusMessage = "Cancelling probe and closing the active camera session…"
                    statusIsError = false
                },
                onExportReport = ::exportProbeReport,
                onExportSupportBundle = ::exportSupportBundle,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            StereoPairSelectionPanel(
                choices = pairChoices,
                selectedKey = selectedPairKey,
                probeResults = probeResults,
                cachedWorkingConfig = supportedCachedConfig,
                selectionEnabled = !streamBusy,
                onSelect = ::selectPair,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            StereoStreamPanel(
                liveState = liveState,
                displayState = displayState,
                selectedResolution = selectedResolution,
                canStart = selectedChoice != null && selectedResolution != null && !streamBusy,
                onStart = ::startSelectedPair,
                onStop = {
                    diagnostics.log("stream_stop_requested")
                    controller.stop()
                    statusMessage = "Streams stopped."
                    statusIsError = false
                },
                modifier = Modifier.padding(bottom = 12.dp)
            )

            StereoPreviewPanel(
                enabled = showPreviews,
                toggleEnabled = !streamBusy,
                leftPreview = leftPreview,
                rightPreview = rightPreview,
                onEnabledChange = {
                    showPreviews = it
                    diagnostics.log(
                        "preview_setting_changed",
                        JSONObject().put("enabled", it)
                    )
                },
                modifier = Modifier.padding(bottom = 12.dp)
            )

            StereoCapturePanel(
                streaming = liveState.streamState == StereoStreamState.STREAMING,
                matchedPairReady = matchedPairReady,
                calibrationPairCount = calibrationPairCount,
                busy = captureBusy,
                onSaveStereoPair = ::saveStereoPair,
                onSaveBoardPair = ::saveBoardPair,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            StereoCalibrationPanel(
                pairCount = calibrationPairCount,
                minimumPairs = AcceptanceConfig.MIN_FRAMES_FOR_CALIBRATION,
                result = calibrationResult,
                busy = calibrationBusy,
                onCalibrate = {
                    val sessionKey = selectedCalibrationSession
                    if (sessionKey == null) {
                        statusMessage = "Select the physical pair and resolution to calibrate."
                        statusIsError = true
                    } else {
                        val logicalCameraId = enumeration?.logicalCameraId ?: "0"
                        calibrationBusy = true
                        diagnostics.log(
                            "stereo_calibration_started",
                            JSONObject().apply {
                                put("left_physical_camera_id", sessionKey.leftPhysicalCameraId)
                                put("right_physical_camera_id", sessionKey.rightPhysicalCameraId)
                                put(
                                    "resolution",
                                    "${sessionKey.resolution.width}x${sessionKey.resolution.height}"
                                )
                                put("pair_count", calibrationPairCount)
                            }
                        )
                        scope.launch {
                            try {
                                val pairs = withContext(Dispatchers.IO) {
                                    boardPairStore.listPairs(sessionKey)
                                }
                                val result = withContext(Dispatchers.Default) {
                                    calibrationEngine.calibrate(
                                        pairs = pairs,
                                        leftPhysicalCameraId = sessionKey.leftPhysicalCameraId,
                                        rightPhysicalCameraId = sessionKey.rightPhysicalCameraId,
                                        logicalCameraId = logicalCameraId
                                    )
                                }
                                calibrationResult = result
                                diagnostics.log(
                                    "stereo_calibration_finished",
                                    JSONObject().apply {
                                        put("success", result.success)
                                        put("status", result.statusMessage)
                                        put("pair_count", result.pairCount)
                                        put("stereo_rms", result.stereoRms ?: JSONObject.NULL)
                                        put("baseline_m", result.baselineM ?: JSONObject.NULL)
                                    }
                                )
                                if (result.success) {
                                    val output = checkNotNull(withContext(Dispatchers.IO) {
                                        calibrationEngine.exportResult(context, result)
                                    }) {
                                        "External files directory unavailable"
                                    }
                                    diagnostics.log(
                                        "stereo_calibration_exported",
                                        JSONObject().put("path", output.absolutePath)
                                    )
                                    statusMessage = "Stereo calibration exported: RMS " +
                                        "${"%.4f".format(result.stereoRms)}, baseline " +
                                        "${"%.4f".format(result.baselineM)} m."
                                    statusIsError = false
                                } else {
                                    statusMessage = result.statusMessage
                                    statusIsError = true
                                }
                            } catch (error: Exception) {
                                diagnostics.log(
                                    "stereo_calibration_failed",
                                    JSONObject().put(
                                        "error",
                                        error.message ?: error.javaClass.simpleName
                                    )
                                )
                                statusMessage = error.message ?: "Stereo calibration failed."
                                statusIsError = true
                            } finally {
                                calibrationBusy = false
                            }
                        }
                    }
                },
                onClear = {
                    selectedCalibrationSession?.let { sessionKey ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                boardPairStore.clear(sessionKey)
                            }
                            calibrationPairCount = 0
                            calibrationResult = null
                            diagnostics.log(
                                "board_pairs_cleared",
                                JSONObject().apply {
                                    put(
                                        "left_physical_camera_id",
                                        sessionKey.leftPhysicalCameraId
                                    )
                                    put(
                                        "right_physical_camera_id",
                                        sessionKey.rightPhysicalCameraId
                                    )
                                    put(
                                        "resolution",
                                        "${sessionKey.resolution.width}x${sessionKey.resolution.height}"
                                    )
                                }
                            )
                            statusMessage = "Board captures cleared for the selected pair and resolution."
                            statusIsError = false
                        }
                    }
                },
                modifier = Modifier.padding(bottom = 12.dp)
            )

            StereoDisparityPanel(
                calibrationReady = calibrationResult?.success == true,
                stereoPairReady = latestPairDirectory != null,
                busy = disparityBusy,
                disclaimer = stringResource(R.string.stereo_depth_disclaimer),
                onCompute = {
                    val calibration = calibrationResult
                    val leftFile = latestPairDirectory?.resolve("left.jpg")
                    val rightFile = latestPairDirectory?.resolve("right.jpg")
                    if (calibration?.success != true ||
                        leftFile?.isFile != true || rightFile?.isFile != true
                    ) {
                        statusMessage = "Calibration and a saved stereo pair are both required."
                        statusIsError = true
                    } else {
                        disparityBusy = true
                        diagnostics.log(
                            "disparity_started",
                            JSONObject().apply {
                                put("left_image", leftFile.absolutePath)
                                put("right_image", rightFile.absolutePath)
                            }
                        )
                        scope.launch {
                            val result = withContext(Dispatchers.Default) {
                                disparityEngine.computeAndExport(
                                    context = context,
                                    leftImageFile = leftFile,
                                    rightImageFile = rightFile,
                                    calibration = calibration
                                )
                            }
                            disparityBusy = false
                            diagnostics.log(
                                "disparity_finished",
                                JSONObject().apply {
                                    put("success", result.success)
                                    put("status", result.statusMessage)
                                    put(
                                        "png_path",
                                        result.pngFile?.absolutePath ?: JSONObject.NULL
                                    )
                                    put(
                                        "json_path",
                                        result.jsonFile?.absolutePath ?: JSONObject.NULL
                                    )
                                }
                            )
                            statusMessage = if (result.success) {
                                statusIsError = false
                                "Disparity exported: ${result.pngFile?.absolutePath}"
                            } else {
                                statusIsError = true
                                result.statusMessage
                            }
                        }
                    }
                },
                modifier = Modifier.padding(bottom = 20.dp)
            )
        }
    }
}

@Composable
private fun StereoTopBar(onBack: () -> Unit) {
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
}

@Composable
private fun StereoUnavailable(
    message: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        StereoTopBar(onBack)
        Text(message, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun StereoPermissionPrompt(
    onBack: () -> Unit,
    onRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        StereoTopBar(onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.stereo_camera_permission_explanation))
            Button(onClick = onRequest, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.stereo_grant_camera_permission))
            }
        }
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private const val ERROR_STATUS_DURATION_MS = 10_000L
private const val STREAMING_STATUS_MESSAGE =
    "Streams are active. Confirm the timestamp delta, then capture."
