package com.example.charucocalibrator.stereo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.example.charucocalibrator.CaptureMetadataStore
import com.example.charucocalibrator.DEFAULT_CAMERA_ID
import com.example.charucocalibrator.Dimensions
import com.example.charucocalibrator.FrameMetadata
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class StereoStreamState {
    IDLE,
    PROBING,
    OPENING,
    STREAMING,
    FAILED
}

data class StereoFrameSnapshot(
    val width: Int,
    val height: Int,
    val sensorTimestampNs: Long,
    val nv21: ByteArray,
    val metadata: FrameMetadata?
)

private data class StereoFrameSample(
    val sensorTimestampNs: Long,
    val metadata: FrameMetadata?
)

data class StereoLiveState(
    val streamState: StereoStreamState = StereoStreamState.IDLE,
    val leftPhysicalId: String? = null,
    val rightPhysicalId: String? = null,
    val pairLabel: String? = null,
    val resolution: Dimensions? = null,
    val leftFps: Float = 0f,
    val rightFps: Float = 0f,
    val timestampDeltaNs: Long? = null,
    val leftMetadata: FrameMetadata? = null,
    val rightMetadata: FrameMetadata? = null,
    val fallbackReason: String? = null,
    val halError: String? = null,
    val leftFrameCount: Long = 0,
    val rightFrameCount: Long = 0,
    val warningMessage: String? = null,
    val afPolicy: String = "continuous_then_fixed",
    val oisDisabled: Boolean = true
)

data class StereoProbeSessionResult(
    val success: Boolean,
    val medianTimestampDeltaNs: Long?,
    val halError: String?,
    val leftFrameCount: Long,
    val rightFrameCount: Long
)

class StereoDualStreamController(
    context: Context,
    private val logicalCameraId: String = DEFAULT_CAMERA_ID,
    private val onStateChanged: (StereoLiveState) -> Unit
) {
    private val applicationContext = context.applicationContext
    private val cameraManager = applicationContext.getSystemService(CameraManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraThread = HandlerThread("StereoDualStream").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val imageThread = HandlerThread("StereoDualImages").apply { start() }
    private val imageHandler = Handler(imageThread.looper)
    private val sessionExecutor: Executor = Executors.newSingleThreadExecutor()

    private val leftMetadataStore = CaptureMetadataStore()
    private val rightMetadataStore = CaptureMetadataStore()

    private val streamActive = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val operationId = AtomicLong(0L)

    @Volatile
    private var shouldRun = false

    @Volatile
    private var currentState = StereoLiveState()

    @Volatile
    private var leftPhysicalId: String? = null

    @Volatile
    private var rightPhysicalId: String? = null

    @Volatile
    private var pairLabel: String? = null

    @Volatile
    private var selectedResolution: Dimensions? = null

    @Volatile
    private var fallbackReason: String? = null

    @Volatile
    private var previewsEnabled = false

    @Volatile
    private var fixedFocusDistance: Float? = null

    @Volatile
    private var focusLocked = false

    @Volatile
    private var streamStartedAtMs = 0L

    private val warmupFocusSamples = ArrayDeque<Float>()

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var leftImageReader: ImageReader? = null
    private var rightImageReader: ImageReader? = null
    private var leftPreviewSurface: Surface? = null
    private var rightPreviewSurface: Surface? = null
    private var leftTextureView: TextureView? = null
    private var rightTextureView: TextureView? = null

    private val latestLeftSample = AtomicReference<StereoFrameSample?>(null)
    private val latestRightSample = AtomicReference<StereoFrameSample?>(null)
    private val timestampPairer = StereoTimestampPairer<StereoFrameSample>(
        timestampNs = { it.sensorTimestampNs }
    )
    private val capturePairer = StereoTimestampPairer<StereoFrameSnapshot>(
        timestampNs = { it.sensorTimestampNs },
        maximumQueuedFrames = 2
    )
    private val pendingPairCapture = AtomicReference<
        ((Result<Pair<StereoFrameSnapshot, StereoFrameSnapshot>>) -> Unit)?
        >(null)
    private val captureArmedOperationId = AtomicLong(NO_OPERATION_ID)

    private var leftFrameCount = 0L
    private var rightFrameCount = 0L
    private var leftFpsWindowStartMs = 0L
    private var rightFpsWindowStartMs = 0L
    private var leftFpsCount = 0
    private var rightFpsCount = 0
    private var lastLiveStateDispatchAtMs = 0L

    private val timestampDeltas = ArrayDeque<Long>()
    private val timestampDeltaLock = Any()

    fun start(
        leftId: String,
        rightId: String,
        resolution: Dimensions,
        label: String,
        enablePreviews: Boolean = false,
        leftPreview: TextureView? = null,
        rightPreview: TextureView? = null,
        reason: String? = null
    ) {
        if (released.get()) return
        cancelPendingPairCapture("Stream restarted before capture completed")
        val requestedOperationId = operationId.incrementAndGet()
        shouldRun = true
        leftPhysicalId = leftId
        rightPhysicalId = rightId
        pairLabel = label
        selectedResolution = resolution
        fallbackReason = reason
        previewsEnabled = enablePreviews
        leftTextureView = leftPreview
        rightTextureView = rightPreview
        focusLocked = false
        fixedFocusDistance = null
        warmupFocusSamples.clear()
        synchronized(timestampDeltaLock) { timestampDeltas.clear() }
        leftFrameCount = 0
        rightFrameCount = 0
        leftFpsWindowStartMs = 0L
        rightFpsWindowStartMs = 0L
        leftFpsCount = 0
        rightFpsCount = 0
        lastLiveStateDispatchAtMs = 0L
        latestLeftSample.set(null)
        latestRightSample.set(null)
        timestampPairer.clear()
        capturePairer.clear()
        leftMetadataStore.clear()
        rightMetadataStore.clear()
        updateState(
            currentState.copy(
                streamState = StereoStreamState.OPENING,
                leftPhysicalId = leftId,
                rightPhysicalId = rightId,
                pairLabel = label,
                resolution = resolution,
                leftFps = 0f,
                rightFps = 0f,
                timestampDeltaNs = null,
                leftMetadata = null,
                rightMetadata = null,
                fallbackReason = reason,
                halError = null,
                leftFrameCount = 0,
                rightFrameCount = 0,
                warningMessage = null,
                afPolicy = "continuous_then_fixed"
            )
        )
        cameraHandler.post {
            closeCameraResources()
            if (isCurrent(requestedOperationId)) {
                openCamera(requestedOperationId)
            }
        }
        cameraHandler.postDelayed({
            if (isCurrent(requestedOperationId) &&
                currentState.streamState == StereoStreamState.OPENING
            ) {
                fail(requestedOperationId, "Camera/session opening timed out")
            }
        }, OPEN_TIMEOUT_MS)
    }

    fun stop(onStopped: (() -> Unit)? = null) {
        val stoppedOperationId = operationId.incrementAndGet()
        shouldRun = false
        streamActive.set(false)
        cancelPendingPairCapture("Streams stopped before capture completed")
        cameraHandler.post {
            closeCameraResources()
            if (!released.get() && operationId.get() == stoppedOperationId) {
                updateState(
                    currentState.copy(
                        streamState = StereoStreamState.IDLE,
                        leftFps = 0f,
                        rightFps = 0f,
                        timestampDeltaNs = null
                    )
                )
            }
            onStopped?.let(::postToMain)
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        operationId.incrementAndGet()
        shouldRun = false
        streamActive.set(false)
        captureArmedOperationId.set(NO_OPERATION_ID)
        pendingPairCapture.set(null)
        capturePairer.clear()
        cameraHandler.post {
            closeCameraResources()
            cameraThread.quitSafely()
        }
        imageThread.quitSafely()
        (sessionExecutor as? java.util.concurrent.ExecutorService)?.shutdown()
    }

    fun captureNextPair(
        onComplete: (Result<Pair<StereoFrameSnapshot, StereoFrameSnapshot>>) -> Unit
    ): Boolean {
        val requestedOperationId = operationId.get()
        if (!isCurrent(requestedOperationId) || !streamActive.get()) return false
        if (!pendingPairCapture.compareAndSet(null, onComplete)) return false

        imageHandler.post {
            if (!isCurrent(requestedOperationId) || !streamActive.get()) {
                completePendingPairCapture(
                    Result.failure(IllegalStateException("Stereo streams are not active"))
                )
                return@post
            }
            capturePairer.clear()
            captureArmedOperationId.set(requestedOperationId)
            imageHandler.postDelayed({
                if (captureArmedOperationId.compareAndSet(
                        requestedOperationId,
                        NO_OPERATION_ID
                    )
                ) {
                    capturePairer.clear()
                    completePendingPairCapture(
                        Result.failure(
                            IllegalStateException("Timed out waiting for a synchronized frame pair")
                        )
                    )
                }
            }, CAPTURE_PAIR_TIMEOUT_MS)
        }
        return true
    }

    fun probePair(
        leftId: String,
        rightId: String,
        resolution: Dimensions,
        durationMs: Long = PROBE_DURATION_MS,
        onComplete: (StereoProbeSessionResult) -> Unit
    ) {
        if (released.get()) {
            onComplete(
                StereoProbeSessionResult(
                    success = false,
                    medianTimestampDeltaNs = null,
                    halError = "Controller released",
                    leftFrameCount = 0,
                    rightFrameCount = 0
                )
            )
            return
        }

        start(
            leftId = leftId,
            rightId = rightId,
            resolution = resolution,
            label = "probe",
            enablePreviews = false
        )

        val probeStartedAtMs = System.currentTimeMillis()
        var streamingAtMs: Long? = null
        val completed = AtomicBoolean(false)
        lateinit var poll: Runnable
        poll = Runnable {
            if (released.get() || completed.get()) return@Runnable
            val state = currentState
            val now = System.currentTimeMillis()
            if (state.streamState == StereoStreamState.STREAMING && streamingAtMs == null) {
                streamingAtMs = now
            }
            val collectionComplete = streamingAtMs?.let { now - it >= durationMs } == true
            val openingTimedOut = now - probeStartedAtMs >= PROBE_TOTAL_TIMEOUT_MS
            val result = StereoProbeEvaluator.terminalResult(
                StereoProbeSnapshot(
                    streamState = state.streamState,
                    leftFrameCount = state.leftFrameCount,
                    rightFrameCount = state.rightFrameCount,
                    timestampDeltasNs = timestampDeltaSnapshot(),
                    halError = state.halError,
                    collectionComplete = collectionComplete,
                    timedOut = openingTimedOut
                )
            )
            if (result != null) {
                if (!completed.compareAndSet(false, true)) return@Runnable
                stop {
                    onComplete(result)
                    release()
                }
            } else {
                mainHandler.postDelayed(poll, PROBE_POLL_INTERVAL_MS)
            }
        }
        mainHandler.post(poll)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(requestedOperationId: Long) {
        if (!isCurrent(requestedOperationId)) return
        val manager = cameraManager ?: run {
            fail(requestedOperationId, "CameraManager unavailable")
            return
        }
        if (logicalCameraId !in manager.cameraIdList) {
            fail(requestedOperationId, "Logical camera $logicalCameraId is not exposed by Camera2")
            return
        }

        try {
            manager.openCamera(
                logicalCameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (!isCurrent(requestedOperationId)) {
                            camera.close()
                            return
                        }
                        cameraDevice = camera
                        configureSession(camera, requestedOperationId)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                        fail(requestedOperationId, "Camera disconnected")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                        fail(requestedOperationId, "Camera open error: $error")
                    }
                },
                cameraHandler
            )
        } catch (exception: Exception) {
            fail(requestedOperationId, "Camera open failed: ${exception.message}")
        }
    }

    private fun configureSession(camera: CameraDevice, requestedOperationId: Long) {
        val leftId = leftPhysicalId ?: return fail(requestedOperationId, "Missing left physical camera id")
        val rightId = rightPhysicalId ?: return fail(requestedOperationId, "Missing right physical camera id")
        val resolution = selectedResolution ?: return fail(requestedOperationId, "Missing resolution")

        closeReadersAndSurfaces()

        val size = Size(resolution.width, resolution.height)
        val leftReader = ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.YUV_420_888,
            MAX_IMAGES
        ).also {
            it.setOnImageAvailableListener(
                { reader -> onImageAvailable(reader, isLeft = true, requestedOperationId) },
                imageHandler
            )
        }
        val rightReader = ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.YUV_420_888,
            MAX_IMAGES
        ).also {
            it.setOnImageAvailableListener(
                { reader -> onImageAvailable(reader, isLeft = false, requestedOperationId) },
                imageHandler
            )
        }
        leftImageReader = leftReader
        rightImageReader = rightReader

        val outputs = mutableListOf<OutputConfiguration>()
        OutputConfiguration(leftReader.surface).apply {
            setPhysicalCameraId(leftId)
            outputs.add(this)
        }
        OutputConfiguration(rightReader.surface).apply {
            setPhysicalCameraId(rightId)
            outputs.add(this)
        }

        if (previewsEnabled) {
            createPreviewSurface(leftTextureView)?.let { surface ->
                leftPreviewSurface = surface
                outputs.add(
                    OutputConfiguration(surface).apply { setPhysicalCameraId(leftId) }
                )
            }
            createPreviewSurface(rightTextureView)?.let { surface ->
                rightPreviewSurface = surface
                outputs.add(
                    OutputConfiguration(surface).apply { setPhysicalCameraId(rightId) }
                )
            }
        }

        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            sessionExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!isCurrent(requestedOperationId)) {
                        session.close()
                        return
                    }
                    captureSession = session
                    try {
                        session.setRepeatingRequest(
                            buildCaptureRequest(camera, outputs),
                            captureCallback,
                            cameraHandler
                        )
                        streamActive.set(true)
                        streamStartedAtMs = System.currentTimeMillis()
                        scheduleFocusLock(requestedOperationId)
                        updateState(
                            currentState.copy(
                                streamState = StereoStreamState.STREAMING,
                                halError = null
                            )
                        )
                    } catch (exception: Exception) {
                        fail(requestedOperationId, "Repeating request failed: ${exception.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    fail(requestedOperationId, "Session configuration failed")
                }
            }
        )

        try {
            camera.createCaptureSession(sessionConfig)
        } catch (exception: Exception) {
            fail(requestedOperationId, "Session creation failed: ${exception.message}")
        }
    }

    private fun buildCaptureRequest(
        camera: CameraDevice,
        outputs: List<OutputConfiguration>
    ): CaptureRequest {
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        outputs.forEach { output ->
            output.surface?.let(builder::addTarget)
        }
        builder.setTag(operationId.get())
        builder.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )
        builder.set(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
        )
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        if (focusLocked) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            fixedFocusDistance?.let { distance ->
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
            }
        } else {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }
        listOfNotNull(leftPhysicalId, rightPhysicalId).forEach { physicalId ->
            builder.setPhysicalIfSupported(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                physicalId
            )
            builder.setPhysicalIfSupported(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
                physicalId
            )
            builder.setPhysicalIfSupported(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON,
                physicalId
            )
            builder.setPhysicalIfSupported(
                CaptureRequest.CONTROL_AF_MODE,
                if (focusLocked) {
                    CaptureRequest.CONTROL_AF_MODE_OFF
                } else {
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                },
                physicalId
            )
            if (focusLocked) {
                fixedFocusDistance?.let { distance ->
                    builder.setPhysicalIfSupported(
                        CaptureRequest.LENS_FOCUS_DISTANCE,
                        distance,
                        physicalId
                    )
                }
            }
        }
        return builder.build()
    }

    private fun <T> CaptureRequest.Builder.setPhysicalIfSupported(
        key: CaptureRequest.Key<T>,
        value: T,
        physicalCameraId: String
    ) {
        try {
            setPhysicalCameraKey(key, value, physicalCameraId)
        } catch (exception: IllegalArgumentException) {
            Log.d(TAG, "Physical request key ${key.name} unavailable for $physicalCameraId")
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        @Suppress("DEPRECATION")
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (request.tag != operationId.get()) return
            val timestamp = result[CaptureResult.SENSOR_TIMESTAMP] ?: return
            val logicalMetadata = FrameMetadata.fromCaptureResult(timestamp, result)
            val physicalResults = result.physicalCameraResults
            storePhysicalMetadata(
                physicalCameraId = leftPhysicalId,
                physicalResults = physicalResults,
                fallback = logicalMetadata,
                store = leftMetadataStore
            )
            storePhysicalMetadata(
                physicalCameraId = rightPhysicalId,
                physicalResults = physicalResults,
                fallback = logicalMetadata,
                store = rightMetadataStore
            )
            logicalMetadata.lensFocusDistance?.let { distance ->
                if (!focusLocked && System.currentTimeMillis() - streamStartedAtMs < WARMUP_MS) {
                    warmupFocusSamples.add(distance)
                }
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            if (request.tag != operationId.get()) return
            Log.w(TAG, "Capture failed: ${failure.reason}")
        }
    }

    private fun storePhysicalMetadata(
        physicalCameraId: String?,
        physicalResults: Map<String, CaptureResult>,
        fallback: FrameMetadata,
        store: CaptureMetadataStore
    ) {
        val physicalResult = physicalCameraId?.let(physicalResults::get)
        if (physicalResult == null) {
            store.store(fallback)
            return
        }
        val timestamp = physicalResult[CaptureResult.SENSOR_TIMESTAMP]
            ?: fallback.sensorTimestampNs
            ?: return
        store.store(FrameMetadata.fromCaptureResult(timestamp, physicalResult))
    }

    private fun onImageAvailable(
        reader: ImageReader,
        isLeft: Boolean,
        requestedOperationId: Long
    ) {
        if (!isCurrent(requestedOperationId)) return
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return

        image.use {
            try {
                if (!isCurrent(requestedOperationId)) return@use
                val timestamp = it.timestamp
                val metadataStore = if (isLeft) leftMetadataStore else rightMetadataStore
                val metadata = metadataStore.lookup(timestamp, consume = false)
                val sample = StereoFrameSample(
                    sensorTimestampNs = timestamp,
                    metadata = metadata
                )
                if (isLeft) {
                    latestLeftSample.set(sample)
                    leftFrameCount++
                    leftFpsCount++
                } else {
                    latestRightSample.set(sample)
                    rightFrameCount++
                    rightFpsCount++
                }

                val matchedPair = if (isLeft) {
                    timestampPairer.offerLeft(sample)
                } else {
                    timestampPairer.offerRight(sample)
                }
                val delta = matchedPair?.let { (left, right) ->
                    val value = StereoTimestampUtils.deltaNs(
                        left.sensorTimestampNs,
                        right.sensorTimestampNs
                    )
                    synchronized(timestampDeltaLock) {
                        timestampDeltas.addLast(value)
                        while (timestampDeltas.size > MAX_DELTA_SAMPLES) {
                            timestampDeltas.removeFirst()
                        }
                    }
                    value
                } ?: currentState.timestampDeltaNs

                val now = System.currentTimeMillis()
                var leftFps = currentState.leftFps
                var rightFps = currentState.rightFps
                if (isLeft) {
                    if (leftFpsWindowStartMs == 0L) leftFpsWindowStartMs = now
                    if (now - leftFpsWindowStartMs >= FPS_WINDOW_MS) {
                        leftFps = leftFpsCount * 1000f / (now - leftFpsWindowStartMs)
                        leftFpsCount = 0
                        leftFpsWindowStartMs = now
                    }
                } else {
                    if (rightFpsWindowStartMs == 0L) rightFpsWindowStartMs = now
                    if (now - rightFpsWindowStartMs >= FPS_WINDOW_MS) {
                        rightFps = rightFpsCount * 1000f / (now - rightFpsWindowStartMs)
                        rightFpsCount = 0
                        rightFpsWindowStartMs = now
                    }
                }

                val warning = buildWarning(
                    delta = delta,
                    leftMetadata = latestLeftSample.get()?.metadata,
                    rightMetadata = latestRightSample.get()?.metadata
                )

                updateFrameState(
                    currentState.copy(
                        streamState = if (streamActive.get()) StereoStreamState.STREAMING else currentState.streamState,
                        leftFps = leftFps,
                        rightFps = rightFps,
                        timestampDeltaNs = delta,
                        leftMetadata = latestLeftSample.get()?.metadata,
                        rightMetadata = latestRightSample.get()?.metadata,
                        leftFrameCount = leftFrameCount,
                        rightFrameCount = rightFrameCount,
                        warningMessage = warning,
                        afPolicy = if (focusLocked) "fixed_after_warmup" else "continuous_then_fixed",
                        oisDisabled = true
                    )
                )

                if (captureArmedOperationId.get() == requestedOperationId &&
                    pendingPairCapture.get() != null
                ) {
                    val snapshot = StereoFrameSnapshot(
                        width = it.cropRect.width(),
                        height = it.cropRect.height(),
                        sensorTimestampNs = timestamp,
                        nv21 = StereoYuvUtil.yuv420888ToNv21(it),
                        metadata = metadata
                    )
                    val capturedPair = if (isLeft) {
                        capturePairer.offerLeft(snapshot)
                    } else {
                        capturePairer.offerRight(snapshot)
                    }
                    if (capturedPair != null &&
                        captureArmedOperationId.compareAndSet(
                            requestedOperationId,
                            NO_OPERATION_ID
                        )
                    ) {
                        capturePairer.clear()
                        completePendingPairCapture(Result.success(capturedPair))
                    }
                }
            } catch (exception: Exception) {
                Log.w(TAG, "Frame processing failed", exception)
                if (captureArmedOperationId.compareAndSet(
                        requestedOperationId,
                        NO_OPERATION_ID
                    )
                ) {
                    capturePairer.clear()
                    completePendingPairCapture(Result.failure(exception))
                }
            }
        }
    }

    private fun scheduleFocusLock(requestedOperationId: Long) {
        cameraHandler.postDelayed({
            if (!isCurrent(requestedOperationId) || !streamActive.get()) return@postDelayed
            fixedFocusDistance = warmupFocusSamples
                .sorted()
                .let { samples ->
                    if (samples.isEmpty()) null else samples[samples.size / 2]
                }
            focusLocked = true
            updateRepeatingRequest()
            updateState(currentState.copy(afPolicy = "fixed_after_warmup"))
        }, WARMUP_MS)
    }

    private fun updateRepeatingRequest() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val outputs = buildOutputConfigurations() ?: return
        try {
            session.setRepeatingRequest(
                buildCaptureRequest(camera, outputs),
                captureCallback,
                cameraHandler
            )
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to update capture request", exception)
        }
    }

    private fun buildOutputConfigurations(): List<OutputConfiguration>? {
        val leftId = leftPhysicalId ?: return null
        val rightId = rightPhysicalId ?: return null
        val leftReader = leftImageReader ?: return null
        val rightReader = rightImageReader ?: return null
        val outputs = mutableListOf<OutputConfiguration>()
        outputs.add(OutputConfiguration(leftReader.surface).apply { setPhysicalCameraId(leftId) })
        outputs.add(OutputConfiguration(rightReader.surface).apply { setPhysicalCameraId(rightId) })
        leftPreviewSurface?.let { surface ->
            outputs.add(OutputConfiguration(surface).apply { setPhysicalCameraId(leftId) })
        }
        rightPreviewSurface?.let { surface ->
            outputs.add(OutputConfiguration(surface).apply { setPhysicalCameraId(rightId) })
        }
        return outputs
    }

    private fun createPreviewSurface(textureView: TextureView?): Surface? {
        val view = textureView ?: return null
        val texture = view.surfaceTexture ?: return null
        val resolution = selectedResolution ?: return null
        texture.setDefaultBufferSize(resolution.width, resolution.height)
        return Surface(texture)
    }

    private fun buildWarning(
        delta: Long?,
        leftMetadata: FrameMetadata?,
        rightMetadata: FrameMetadata?
    ): String? {
        val warnings = mutableListOf<String>()
        if (delta != null && StereoTimestampUtils.isWarning(delta)) {
            warnings.add("timestamp_delta_ns above 5 ms")
        }
        val leftExposure = leftMetadata?.exposureTimeNs
        val rightExposure = rightMetadata?.exposureTimeNs
        if (leftExposure != null && rightExposure != null) {
            val ratio = leftExposure.toDouble() / rightExposure.toDouble()
            if (ratio > 2.0 || ratio < 0.5) {
                warnings.add("exposure mismatch between left and right")
            }
        }
        return warnings.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun closeCameraResources() {
        streamActive.set(false)
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        closeReadersAndSurfaces()
        focusLocked = false
        fixedFocusDistance = null
    }

    private fun closeReadersAndSurfaces() {
        leftImageReader?.close()
        rightImageReader?.close()
        leftImageReader = null
        rightImageReader = null
        leftPreviewSurface?.release()
        rightPreviewSurface?.release()
        leftPreviewSurface = null
        rightPreviewSurface = null
    }

    private fun timestampDeltaSnapshot(): List<Long> =
        synchronized(timestampDeltaLock) { timestampDeltas.toList() }

    private fun completePendingPairCapture(
        result: Result<Pair<StereoFrameSnapshot, StereoFrameSnapshot>>
    ) {
        captureArmedOperationId.set(NO_OPERATION_ID)
        capturePairer.clear()
        pendingPairCapture.getAndSet(null)?.let { callback ->
            postToMain { callback(result) }
        }
    }

    private fun cancelPendingPairCapture(message: String) {
        captureArmedOperationId.set(NO_OPERATION_ID)
        capturePairer.clear()
        pendingPairCapture.getAndSet(null)?.let { callback ->
            postToMain { callback(Result.failure(IllegalStateException(message))) }
        }
    }

    private fun fail(requestedOperationId: Long, message: String) {
        if (!isCurrent(requestedOperationId)) return
        Log.e(TAG, message)
        shouldRun = false
        streamActive.set(false)
        cancelPendingPairCapture(message)
        updateState(
            currentState.copy(
                streamState = StereoStreamState.FAILED,
                halError = message
            )
        )
        cameraHandler.post {
            if (operationId.get() == requestedOperationId) {
                closeCameraResources()
            }
        }
    }

    private fun isCurrent(requestedOperationId: Long): Boolean =
        shouldRun && !released.get() && operationId.get() == requestedOperationId

    private fun updateState(state: StereoLiveState) {
        currentState = state
        postToMain { if (!released.get()) onStateChanged(state) }
    }

    private fun updateFrameState(state: StereoLiveState) {
        currentState = state
        val now = System.nanoTime() / 1_000_000L
        if (now - lastLiveStateDispatchAtMs >= LIVE_STATE_DISPATCH_INTERVAL_MS) {
            lastLiveStateDispatchAtMs = now
            postToMain { if (!released.get()) onStateChanged(state) }
        }
    }

    private fun postToMain(action: () -> Unit) {
        if (!released.get()) {
            mainHandler.post { if (!released.get()) action() }
        }
    }

    companion object {
        private const val TAG = "StereoDualStream"
        private const val MAX_IMAGES = 3
        private const val OPEN_TIMEOUT_MS = 6_000L
        private const val WARMUP_MS = 2_500L
        private const val PROBE_DURATION_MS = 2_000L
        private const val PROBE_TOTAL_TIMEOUT_MS = OPEN_TIMEOUT_MS + PROBE_DURATION_MS + 1_000L
        private const val PROBE_POLL_INTERVAL_MS = 100L
        private const val CAPTURE_PAIR_TIMEOUT_MS = 3_000L
        private const val FPS_WINDOW_MS = 1_000L
        private const val LIVE_STATE_DISPATCH_INTERVAL_MS = 100L
        private const val MAX_DELTA_SAMPLES = 120
        private const val NO_OPERATION_ID = -1L
    }
}
