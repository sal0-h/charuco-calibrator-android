package com.example.charucocalibrator.stereo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
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
import android.os.Build
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
import com.example.charucocalibrator.stereo.model.StereoPairProbeResult
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

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

    private val latestLeftFrame = AtomicReference<StereoFrameSnapshot?>(null)
    private val latestRightFrame = AtomicReference<StereoFrameSnapshot?>(null)

    private var leftFrameCount = 0L
    private var rightFrameCount = 0L
    private var leftFpsWindowStartMs = 0L
    private var rightFpsWindowStartMs = 0L
    private var leftFpsCount = 0
    private var rightFpsCount = 0

    private val timestampDeltas = ArrayDeque<Long>()

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
        timestampDeltas.clear()
        leftFrameCount = 0
        rightFrameCount = 0
        latestLeftFrame.set(null)
        latestRightFrame.set(null)
        leftMetadataStore.clear()
        rightMetadataStore.clear()
        updateState(
            currentState.copy(
                streamState = StereoStreamState.OPENING,
                leftPhysicalId = leftId,
                rightPhysicalId = rightId,
                pairLabel = label,
                resolution = resolution,
                fallbackReason = reason,
                halError = null,
                warningMessage = null
            )
        )
        cameraHandler.post { openCamera() }
    }

    fun stop() {
        shouldRun = false
        streamActive.set(false)
        cameraHandler.post {
            closeCameraResources()
            updateState(
                currentState.copy(
                    streamState = StereoStreamState.IDLE,
                    leftFps = 0f,
                    rightFps = 0f,
                    timestampDeltaNs = null
                )
            )
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        shouldRun = false
        streamActive.set(false)
        cameraHandler.post {
            closeCameraResources()
            cameraThread.quitSafely()
        }
        imageThread.quitSafely()
        (sessionExecutor as? java.util.concurrent.ExecutorService)?.shutdown()
    }

    fun getLatestFrames(): Pair<StereoFrameSnapshot?, StereoFrameSnapshot?> =
        latestLeftFrame.get() to latestRightFrame.get()

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

        val probeDeltas = mutableListOf<Long>()
        var probeLeftCount = 0L
        var probeRightCount = 0L
        var probeHalError: String? = null

        val probeController = StereoDualStreamController(applicationContext) { state ->
            if (state.streamState == StereoStreamState.FAILED) {
                probeHalError = state.halError
            }
            state.timestampDeltaNs?.let(probeDeltas::add)
            probeLeftCount = state.leftFrameCount
            probeRightCount = state.rightFrameCount
        }

        probeController.start(
            leftId = leftId,
            rightId = rightId,
            resolution = resolution,
            label = "probe",
            enablePreviews = false
        )

        mainHandler.postDelayed({
            val median = StereoTimestampUtils.medianDeltaNs(probeDeltas)
            val success = probeHalError == null &&
                probeLeftCount > 0 &&
                probeRightCount > 0 &&
                median != null &&
                StereoTimestampUtils.isWithinProbeTolerance(median)
            probeController.stop()
            probeController.release()
            onComplete(
                StereoProbeSessionResult(
                    success = success,
                    medianTimestampDeltaNs = median,
                    halError = probeHalError,
                    leftFrameCount = probeLeftCount,
                    rightFrameCount = probeRightCount
                )
            )
        }, durationMs)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!shouldRun || released.get()) return
        val manager = cameraManager ?: run {
            fail("CameraManager unavailable")
            return
        }
        if (logicalCameraId !in manager.cameraIdList) {
            fail("Logical camera $logicalCameraId is not exposed by Camera2")
            return
        }

        try {
            manager.openCamera(
                logicalCameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (!shouldRun || released.get()) {
                            camera.close()
                            return
                        }
                        cameraDevice = camera
                        configureSession(camera)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                        fail("Camera disconnected")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                        fail("Camera open error: $error")
                    }
                },
                cameraHandler
            )
        } catch (exception: Exception) {
            fail("Camera open failed: ${exception.message}")
        }
    }

    private fun configureSession(camera: CameraDevice) {
        val leftId = leftPhysicalId ?: return fail("Missing left physical camera id")
        val rightId = rightPhysicalId ?: return fail("Missing right physical camera id")
        val resolution = selectedResolution ?: return fail("Missing resolution")

        closeReadersAndSurfaces()

        val size = Size(resolution.width, resolution.height)
        val leftReader = ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.YUV_420_888,
            MAX_IMAGES
        ).also {
            it.setOnImageAvailableListener({ reader -> onImageAvailable(reader, isLeft = true) }, imageHandler)
        }
        val rightReader = ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.YUV_420_888,
            MAX_IMAGES
        ).also {
            it.setOnImageAvailableListener({ reader -> onImageAvailable(reader, isLeft = false) }, imageHandler)
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
                    if (!shouldRun || released.get()) {
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
                        scheduleFocusLock()
                        updateState(
                            currentState.copy(
                                streamState = StereoStreamState.STREAMING,
                                halError = null
                            )
                        )
                    } catch (exception: Exception) {
                        fail("Repeating request failed: ${exception.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    fail("Session configuration failed")
                }
            }
        )

        try {
            camera.createCaptureSession(sessionConfig)
        } catch (exception: Exception) {
            fail("Session creation failed: ${exception.message}")
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
        return builder.build()
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val timestamp = result[CaptureResult.SENSOR_TIMESTAMP] ?: return
            val metadata = FrameMetadata.fromCaptureResult(timestamp, result)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                when (result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)) {
                    leftPhysicalId -> leftMetadataStore.store(metadata)
                    rightPhysicalId -> rightMetadataStore.store(metadata)
                    else -> {
                        leftMetadataStore.store(metadata)
                        rightMetadataStore.store(metadata)
                    }
                }
            } else {
                leftMetadataStore.store(metadata)
                rightMetadataStore.store(metadata)
            }
            metadata.lensFocusDistance?.let { distance ->
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
            Log.w(TAG, "Capture failed: ${failure.reason}")
        }
    }

    private fun onImageAvailable(reader: ImageReader, isLeft: Boolean) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return

        image.use {
            try {
                val nv21 = StereoYuvUtil.yuv420888ToNv21(it)
                val timestamp = it.timestamp
                val metadataStore = if (isLeft) leftMetadataStore else rightMetadataStore
                val metadata = metadataStore.lookup(timestamp, consume = false)
                val snapshot = StereoFrameSnapshot(
                    width = it.cropRect.width(),
                    height = it.cropRect.height(),
                    sensorTimestampNs = timestamp,
                    nv21 = nv21,
                    metadata = metadata
                )
                if (isLeft) {
                    latestLeftFrame.set(snapshot)
                    leftFrameCount++
                    leftFpsCount++
                } else {
                    latestRightFrame.set(snapshot)
                    rightFrameCount++
                    rightFpsCount++
                }

                val leftTs = latestLeftFrame.get()?.sensorTimestampNs
                val rightTs = latestRightFrame.get()?.sensorTimestampNs
                val delta = if (leftTs != null && rightTs != null) {
                    val value = StereoTimestampUtils.deltaNs(leftTs, rightTs)
                    timestampDeltas.addLast(value)
                    while (timestampDeltas.size > MAX_DELTA_SAMPLES) {
                        timestampDeltas.removeFirst()
                    }
                    value
                } else {
                    null
                }

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
                    leftMetadata = latestLeftFrame.get()?.metadata,
                    rightMetadata = latestRightFrame.get()?.metadata
                )

                updateState(
                    currentState.copy(
                        streamState = if (streamActive.get()) StereoStreamState.STREAMING else currentState.streamState,
                        leftFps = leftFps,
                        rightFps = rightFps,
                        timestampDeltaNs = delta,
                        leftMetadata = latestLeftFrame.get()?.metadata,
                        rightMetadata = latestRightFrame.get()?.metadata,
                        leftFrameCount = leftFrameCount,
                        rightFrameCount = rightFrameCount,
                        warningMessage = warning,
                        afPolicy = if (focusLocked) "fixed_after_warmup" else "continuous_then_fixed",
                        oisDisabled = true
                    )
                )
            } catch (exception: Exception) {
                Log.w(TAG, "Frame processing failed", exception)
            }
        }
    }

    private fun scheduleFocusLock() {
        cameraHandler.postDelayed({
            if (!shouldRun || released.get() || !streamActive.get()) return@postDelayed
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

    private fun fail(message: String) {
        Log.e(TAG, message)
        streamActive.set(false)
        updateState(
            currentState.copy(
                streamState = StereoStreamState.FAILED,
                halError = message
            )
        )
    }

    private fun updateState(state: StereoLiveState) {
        currentState = state
        postToMain { if (!released.get()) onStateChanged(state) }
    }

    private fun postToMain(action: () -> Unit) {
        if (!released.get()) {
            mainHandler.post { if (!released.get()) action() }
        }
    }

    companion object {
        private const val TAG = "StereoDualStream"
        private const val MAX_IMAGES = 3
        private const val WARMUP_MS = 2_500L
        private const val PROBE_DURATION_MS = 2_000L
        private const val FPS_WINDOW_MS = 1_000L
        private const val MAX_DELTA_SAMPLES = 120
    }
}
