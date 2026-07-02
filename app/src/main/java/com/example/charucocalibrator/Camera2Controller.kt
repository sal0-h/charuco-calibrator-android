package com.example.charucocalibrator

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

data class CameraStreamConfiguration(
    val cameraId: String,
    val analysisSize: Dimensions,
    val previewSize: Dimensions
)

data class SavedFrameFiles(
    val imageFile: File,
    val metadataFile: File,
    val savedAtUtc: String,
    val imageWidth: Int,
    val imageHeight: Int
)

class Camera2Controller(
    context: Context,
    private val cameraId: String = DEFAULT_CAMERA_ID,
    private val onStreamConfigured: (CameraStreamConfiguration) -> Unit,
    private val onFrameCountChanged: (Long) -> Unit,
    private val onStatusChanged: (String) -> Unit,
    private val onFrameSaveResult: (Result<SavedFrameFiles>) -> Unit,
    private val onAnalysisSnapshot: (FrameAnalysisSnapshot) -> Unit = {}
) {
    private val applicationContext = context.applicationContext
    private val cameraManager = applicationContext.getSystemService(CameraManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraThread = HandlerThread("Camera2Controller").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val imageThread = HandlerThread("Camera2Images").apply { start() }
    private val imageHandler = Handler(imageThread.looper)
    private val saveExecutor = Executors.newSingleThreadExecutor()
    private val frameAnalysisPipeline = FrameAnalysisPipeline(
        context = applicationContext,
        cameraId = cameraId,
        onSnapshot = onAnalysisSnapshot
    )

    private val frameCounter = AtomicLong(0)
    private val streamActive = AtomicBoolean(false)
    private val saveRequested = AtomicBoolean(false)
    private val saveBusy = AtomicBoolean(false)
    private val metadataLock = Any()
    private val captureMetadataByTimestamp = LinkedHashMap<Long, FrameMetadata>()

    @Volatile
    private var shouldRun = false

    @Volatile
    private var released = false

    @Volatile
    private var selectedPreviewSize: Size? = null

    @Volatile
    private var sensorOrientationDegrees: Int? = null

    @Volatile
    private var displayRotationDegrees: Int? = null

    private var textureView: TextureView? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var analysisCandidates: List<Size> = emptyList()
    private var analysisCandidateIndex = 0
    private var sessionGeneration = 0
    private var openRequested = false

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(
            surface: SurfaceTexture,
            width: Int,
            height: Int
        ) {
            if (shouldRun) openCamera(surface, width, height)
        }

        override fun onSurfaceTextureSizeChanged(
            surface: SurfaceTexture,
            width: Int,
            height: Int
        ) {
            displayRotationDegrees = textureView?.display?.rotation?.toDegrees()
            selectedPreviewSize?.let { configureTransform(textureView, it, width, height) }
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            cameraHandler.post(::closeCameraResources)
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    fun start(view: TextureView) {
        if (released) return
        shouldRun = true
        textureView = view
        displayRotationDegrees = view.display?.rotation?.toDegrees()
        view.surfaceTextureListener = surfaceTextureListener

        if (view.isAvailable) {
            view.surfaceTexture?.let { openCamera(it, view.width, view.height) }
        }
    }

    fun stop() {
        shouldRun = false
        if (saveRequested.getAndSet(false)) saveBusy.set(false)
        cameraHandler.post(::closeCameraResources)
    }

    fun release() {
        if (released) return
        released = true
        shouldRun = false
        if (saveRequested.getAndSet(false)) saveBusy.set(false)
        textureView?.surfaceTextureListener = null
        textureView = null

        cameraHandler.post {
            closeCameraResources()
            cameraThread.quitSafely()
        }
        imageThread.quitSafely()
        saveExecutor.shutdown()
        frameAnalysisPipeline.release()
    }

    fun requestSaveNextFrame(): Boolean {
        if (
            !shouldRun ||
            released ||
            !streamActive.get() ||
            !saveBusy.compareAndSet(false, true)
        ) return false
        saveRequested.set(true)
        return true
    }

    fun startAutoCapture() {
        frameAnalysisPipeline.startAutoCapture()
    }

    fun stopAutoCapture() {
        frameAnalysisPipeline.stopAutoCapture()
    }

    fun clearAcceptedFrames() {
        frameAnalysisPipeline.clearAcceptedFrames()
    }

    fun runCalibration() {
        frameAnalysisPipeline.runCalibration()
    }

    private fun openCamera(surfaceTexture: SurfaceTexture, viewWidth: Int, viewHeight: Int) {
        cameraHandler.post {
            if (!shouldRun || released || cameraDevice != null || openRequested) return@post

            try {
                if (cameraId !in cameraManager.cameraIdList) {
                    notifyStatus("Camera $cameraId is not exposed by Camera2")
                    return@post
                }

                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                sensorOrientationDegrees =
                    characteristics[CameraCharacteristics.SENSOR_ORIENTATION]
                val streamMap = characteristics[
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                ] ?: error("Camera $cameraId has no stream configuration map")
                val yuvSizes = streamMap.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
                val previewSizes = streamMap.getOutputSizes(SurfaceTexture::class.java).orEmpty()

                analysisCandidates = chooseAnalysisCandidates(yuvSizes)
                check(analysisCandidates.isNotEmpty()) {
                    "Camera $cameraId exposes no YUV_420_888 output sizes"
                }
                analysisCandidateIndex = 0

                val previewSize = choosePreviewSize(previewSizes)
                selectedPreviewSize = previewSize
                surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
                previewSurface = Surface(surfaceTexture)
                mainHandler.post {
                    configureTransform(textureView, previewSize, viewWidth, viewHeight)
                }

                frameCounter.set(0)
                notifyFrameCount(0)
                notifyStatus("Opening Camera2 camera $cameraId...")
                openRequested = true
                openCameraDevice()
            } catch (exception: Exception) {
                openRequested = false
                notifyStatus("Camera2 open failed: ${exception.message ?: exception.javaClass.simpleName}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraDevice() {
        if (
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            openRequested = false
            notifyStatus("Camera permission is not granted")
            return
        }

        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    openRequested = false
                    if (!shouldRun || released) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    configureCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                    openRequested = false
                    notifyStatus("Camera $cameraId disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                    openRequested = false
                    notifyStatus("Camera2 error: ${cameraErrorName(error)} ($error)")
                }
            },
            cameraHandler
        )
    }

    @Suppress("DEPRECATION")
    private fun configureCaptureSession() {
        val camera = cameraDevice ?: return
        val preview = previewSurface ?: return
        val analysisSize = analysisCandidates.getOrNull(analysisCandidateIndex) ?: run {
            notifyStatus("No usable analysis stream size")
            return
        }

        imageReader?.close()
        val reader = ImageReader.newInstance(
            analysisSize.width,
            analysisSize.height,
            ImageFormat.YUV_420_888,
            MAX_IMAGES
        ).also {
            it.setOnImageAvailableListener(::onImageAvailable, imageHandler)
        }
        imageReader = reader

        val generation = ++sessionGeneration
        notifyStatus("Configuring ${analysisSize.width}x${analysisSize.height} YUV stream...")

        try {
            camera.createCaptureSession(
                listOf(preview, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!shouldRun || released || generation != sessionGeneration) {
                            session.close()
                            return
                        }

                        captureSession = session
                        try {
                            val request = camera.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW
                            ).apply {
                                addTarget(preview)
                                addTarget(reader.surface)
                                set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                )
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            }.build()

                            session.setRepeatingRequest(request, captureCallback, cameraHandler)
                            streamActive.set(true)
                            val previewSize = checkNotNull(selectedPreviewSize)
                            notifyStreamConfigured(analysisSize, previewSize)
                            notifyStatus("Camera2 preview running")
                            monitorInitialFrameRate(session, generation, analysisSize)
                        } catch (exception: Exception) {
                            retryWithNextAnalysisSize(
                                "Repeating request failed: " +
                                    (exception.message ?: exception.javaClass.simpleName)
                            )
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        if (generation == sessionGeneration) {
                            retryWithNextAnalysisSize("Session configuration failed")
                        }
                    }
                },
                cameraHandler
            )
        } catch (exception: Exception) {
            retryWithNextAnalysisSize(
                "Session creation failed: ${exception.message ?: exception.javaClass.simpleName}"
            )
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val timestamp = result[CaptureResult.SENSOR_TIMESTAMP] ?: return
            val metadata = FrameMetadata(
                exposureTimeNs = result[CaptureResult.SENSOR_EXPOSURE_TIME],
                isoSensitivity = result[CaptureResult.SENSOR_SENSITIVITY],
                focalLengthMm = result[CaptureResult.LENS_FOCAL_LENGTH]
            )
            synchronized(metadataLock) {
                captureMetadataByTimestamp[timestamp] = metadata
                while (captureMetadataByTimestamp.size > MAX_METADATA_ENTRIES) {
                    captureMetadataByTimestamp.entries.iterator().run {
                        next()
                        remove()
                    }
                }
            }
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return

        image.use {
            val count = frameCounter.incrementAndGet()
            notifyFrameCount(count)
            frameAnalysisPipeline.submitFrame(it, count, it.timestamp.takeIf { ts -> ts > 0L })

            if (saveRequested.compareAndSet(true, false)) {
                try {
                    val conversionStartedNs = System.nanoTime()
                    Log.i(
                        TAG,
                        "Converting YUV_420_888 frame ${it.cropRect.width()}x" +
                            "${it.cropRect.height()} at sensor timestamp ${it.timestamp} to NV21"
                    )
                    val nv21 = yuv420888ToNv21(it)
                    check(nv21.isNotEmpty()) { "YUV-to-NV21 conversion produced no data" }
                    Log.i(
                        TAG,
                        "YUV-to-NV21 conversion completed: ${nv21.size} bytes in " +
                            "${(System.nanoTime() - conversionStartedNs) / 1_000_000} ms"
                    )
                    val frame = PendingFrame(
                        width = it.cropRect.width(),
                        height = it.cropRect.height(),
                        capturedAtUtc = Instant.now().toString(),
                        sensorTimestampNs = it.timestamp.takeIf { timestamp -> timestamp > 0L },
                        sensorOrientationDegrees = sensorOrientationDegrees,
                        displayRotationDegrees = displayRotationDegrees,
                        nv21 = nv21
                    )
                    saveExecutor.execute { saveFrame(frame) }
                } catch (exception: Exception) {
                    saveBusy.set(false)
                    notifyFrameSaveResult(Result.failure(exception))
                }
            }
        }
    }

    private fun saveFrame(frame: PendingFrame) {
        val result = runCatching {
            val metadata = awaitCaptureMetadata(frame.sensorTimestampNs)
            val directory = checkNotNull(applicationContext.getExternalFilesDir(null)) {
                "App-specific external files directory is unavailable"
            }
            val baseName = "test_frame_${System.currentTimeMillis()}"
            val imageFile = File(directory, "$baseName.jpg")
            val metadataFile = File(directory, "$baseName.json")

            Log.i(
                TAG,
                "Encoding ${frame.width}x${frame.height} NV21 test frame as JPEG: " +
                    imageFile.absolutePath
            )
            FileOutputStream(imageFile).use { output ->
                val compressed = YuvImage(
                    frame.nv21,
                    ImageFormat.NV21,
                    frame.width,
                    frame.height,
                    null
                ).compressToJpeg(
                    Rect(0, 0, frame.width, frame.height),
                    JPEG_QUALITY,
                    output
                )
                check(compressed) { "YUV-to-JPEG conversion failed" }
            }
            check(imageFile.isFile && imageFile.length() > 0L) {
                "JPEG output is empty: ${imageFile.absolutePath}"
            }
            Log.i(TAG, "JPEG test frame saved: ${imageFile.length()} bytes")

            metadataFile.writeText(
                JSONObject().apply {
                    put("camera_id", cameraId)
                    put("image_width", frame.width)
                    put("image_height", frame.height)
                    put("timestamp", frame.capturedAtUtc)
                    put("sensor_timestamp_ns", frame.sensorTimestampNs ?: JSONObject.NULL)
                    put(
                        "sensor_exposure_time_ns",
                        metadata?.exposureTimeNs ?: JSONObject.NULL
                    )
                    put("iso_sensitivity", metadata?.isoSensitivity ?: JSONObject.NULL)
                    put("focal_length_mm", metadata?.focalLengthMm ?: JSONObject.NULL)
                    put(
                        "sensor_orientation",
                        frame.sensorOrientationDegrees ?: JSONObject.NULL
                    )
                    put(
                        "display_rotation",
                        frame.displayRotationDegrees ?: JSONObject.NULL
                    )
                    put("orientation_note", ORIENTATION_NOTE)
                }.toString(JSON_INDENT_SPACES)
            )
            check(metadataFile.isFile && metadataFile.length() > 0L) {
                "Metadata output is empty: ${metadataFile.absolutePath}"
            }
            Log.i(TAG, "Test-frame metadata saved: ${metadataFile.absolutePath}")

            SavedFrameFiles(
                imageFile = imageFile,
                metadataFile = metadataFile,
                savedAtUtc = Instant.now().toString(),
                imageWidth = frame.width,
                imageHeight = frame.height
            )
        }.onFailure {
            Log.e(TAG, "Test-frame YUV/JPEG save failed", it)
        }

        saveBusy.set(false)
        notifyFrameSaveResult(result)
    }

    private fun awaitCaptureMetadata(timestampNs: Long?): FrameMetadata? {
        if (timestampNs == null) return null
        repeat(METADATA_WAIT_ATTEMPTS) {
            synchronized(metadataLock) {
                captureMetadataByTimestamp.remove(timestampNs)?.let { return it }
            }
            Thread.sleep(METADATA_WAIT_MILLIS)
        }
        return synchronized(metadataLock) { captureMetadataByTimestamp.remove(timestampNs) }
    }

    private fun monitorInitialFrameRate(
        session: CameraCaptureSession,
        generation: Int,
        analysisSize: Size
    ) {
        if (analysisSize != PREFERRED_ANALYSIS_SIZE) return
        val initialFrameCount = frameCounter.get()
        cameraHandler.postDelayed(
            {
                if (
                    shouldRun &&
                    captureSession === session &&
                    generation == sessionGeneration &&
                    frameCounter.get() - initialFrameCount < MIN_INITIAL_FRAMES
                ) {
                    retryWithNextAnalysisSize(
                        "${analysisSize.width}x${analysisSize.height} YUV stream stalled"
                    )
                }
            },
            INITIAL_FRAME_WINDOW_MILLIS
        )
    }

    private fun retryWithNextAnalysisSize(reason: String) {
        streamActive.set(false)
        captureSession?.close()
        captureSession = null
        imageReader?.close()
        imageReader = null

        if (analysisCandidateIndex + 1 >= analysisCandidates.size) {
            notifyStatus("$reason; no fallback analysis size is available")
            return
        }

        analysisCandidateIndex += 1
        val fallback = analysisCandidates[analysisCandidateIndex]
        notifyStatus("$reason; retrying ${fallback.width}x${fallback.height}")
        configureCaptureSession()
    }

    private fun closeCameraResources() {
        sessionGeneration += 1
        streamActive.set(false)
        openRequested = false
        captureSession?.close()
        captureSession = null
        imageReader?.close()
        imageReader = null
        cameraDevice?.close()
        cameraDevice = null
        previewSurface?.release()
        previewSurface = null
        selectedPreviewSize = null
        if (saveRequested.getAndSet(false)) saveBusy.set(false)
        synchronized(metadataLock) { captureMetadataByTimestamp.clear() }
    }

    private fun notifyStreamConfigured(analysisSize: Size, previewSize: Size) {
        postToMain {
            onStreamConfigured(
                CameraStreamConfiguration(
                    cameraId = cameraId,
                    analysisSize = analysisSize.toDimensions(),
                    previewSize = previewSize.toDimensions()
                )
            )
        }
    }

    private fun notifyFrameCount(count: Long) = postToMain {
        onFrameCountChanged(count)
    }

    private fun notifyStatus(status: String) = postToMain {
        onStatusChanged(status)
    }

    private fun notifyFrameSaveResult(result: Result<SavedFrameFiles>) = postToMain {
        onFrameSaveResult(result)
    }

    private fun postToMain(action: () -> Unit) {
        if (!released) mainHandler.post { if (!released) action() }
    }
}

private data class PendingFrame(
    val width: Int,
    val height: Int,
    val capturedAtUtc: String,
    val sensorTimestampNs: Long?,
    val sensorOrientationDegrees: Int?,
    val displayRotationDegrees: Int?,
    val nv21: ByteArray
)

private data class FrameMetadata(
    val exposureTimeNs: Long?,
    val isoSensitivity: Int?,
    val focalLengthMm: Float?
)

private fun chooseAnalysisCandidates(availableSizes: Array<out Size>): List<Size> {
    if (availableSizes.isEmpty()) return emptyList()
    val candidates = mutableListOf<Size>()

    availableSizes.findExact(PREFERRED_ANALYSIS_SIZE)?.let(candidates::add)
    availableSizes.findExact(FALLBACK_ANALYSIS_SIZE)?.let(candidates::add)

    if (candidates.none { it == FALLBACK_ANALYSIS_SIZE }) {
        availableSizes
            .filter { it.hasAspectRatio(4, 3) && it.area <= FALLBACK_ANALYSIS_SIZE.area }
            .maxByOrNull(Size::area)
            ?.let(candidates::add)
    }

    if (candidates.isEmpty()) {
        availableSizes.minByOrNull {
            abs(it.width.toDouble() / it.height - 4.0 / 3.0)
        }?.let(candidates::add)
    }

    return candidates.distinct()
}

private fun choosePreviewSize(availableSizes: Array<out Size>): Size {
    check(availableSizes.isNotEmpty()) { "Camera exposes no SurfaceTexture preview sizes" }
    availableSizes.findExact(PREFERRED_PREVIEW_SIZE)?.let { return it }

    return availableSizes
        .filter { it.hasAspectRatio(4, 3) && it.area <= PREFERRED_PREVIEW_SIZE.area }
        .maxByOrNull(Size::area)
        ?: availableSizes.minByOrNull {
            abs(it.width.toDouble() / it.height - 4.0 / 3.0)
        }
        ?: availableSizes.first()
}

private fun Array<out Size>.findExact(target: Size): Size? =
    firstOrNull { it.width == target.width && it.height == target.height }

private fun Size.hasAspectRatio(widthRatio: Int, heightRatio: Int): Boolean =
    width.toLong() * heightRatio == height.toLong() * widthRatio

private val Size.area: Long
    get() = width.toLong() * height

private fun Size.toDimensions(): Dimensions = Dimensions(width = width, height = height)

private fun configureTransform(
    textureView: TextureView?,
    previewSize: Size,
    viewWidth: Int,
    viewHeight: Int
) {
    val view = textureView ?: return
    if (viewWidth == 0 || viewHeight == 0) return
    val rotation = view.display?.rotation ?: Surface.ROTATION_0
    val matrix = Matrix()
    val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
    val centerX = viewRect.centerX()
    val centerY = viewRect.centerY()

    if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
        val bufferRect = RectF(
            0f,
            0f,
            previewSize.height.toFloat(),
            previewSize.width.toFloat()
        )
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = max(
            viewHeight.toFloat() / previewSize.height,
            viewWidth.toFloat() / previewSize.width
        )
        matrix.postScale(scale, scale, centerX, centerY)
        matrix.postRotate(90f * (rotation - 2), centerX, centerY)
    } else if (rotation == Surface.ROTATION_180) {
        matrix.postRotate(180f, centerX, centerY)
    }

    view.setTransform(matrix)
}

private fun yuv420888ToNv21(image: Image): ByteArray {
    val crop = image.cropRect
    val width = crop.width()
    val height = crop.height()
    require(width % 2 == 0 && height % 2 == 0) {
        "YUV crop must have even dimensions, got ${width}x$height"
    }

    val output = ByteArray(width * height * 3 / 2)
    val yPlane = image.planes[0]
    val yBuffer = yPlane.buffer.duplicate()
    val yBufferStart = yBuffer.position()
    var outputOffset = 0

    for (row in 0 until height) {
        val rowStart = yBufferStart +
            (crop.top + row) * yPlane.rowStride +
            crop.left * yPlane.pixelStride
        if (yPlane.pixelStride == 1) {
            yBuffer.position(rowStart)
            yBuffer.get(output, outputOffset, width)
            outputOffset += width
        } else {
            for (column in 0 until width) {
                output[outputOffset++] = yBuffer.get(rowStart + column * yPlane.pixelStride)
            }
        }
    }

    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val uBuffer = uPlane.buffer.duplicate()
    val vBuffer = vPlane.buffer.duplicate()
    val uBufferStart = uBuffer.position()
    val vBufferStart = vBuffer.position()
    val chromaWidth = width / 2
    val chromaHeight = height / 2
    val cropLeft = crop.left / 2
    val cropTop = crop.top / 2

    for (row in 0 until chromaHeight) {
        val uRowStart = uBufferStart +
            (cropTop + row) * uPlane.rowStride +
            cropLeft * uPlane.pixelStride
        val vRowStart = vBufferStart +
            (cropTop + row) * vPlane.rowStride +
            cropLeft * vPlane.pixelStride
        for (column in 0 until chromaWidth) {
            output[outputOffset++] = vBuffer.get(vRowStart + column * vPlane.pixelStride)
            output[outputOffset++] = uBuffer.get(uRowStart + column * uPlane.pixelStride)
        }
    }

    return output
}

private fun cameraErrorName(error: Int): String = when (error) {
    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
    CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
    CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE"
    CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE"
    else -> "UNKNOWN"
}

private fun Int.toDegrees(): Int = when (this) {
    Surface.ROTATION_0 -> 0
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}

const val DEFAULT_CAMERA_ID = "0"
const val ORIENTATION_NOTE = "Camera2 sensor-native landscape grid; not display-rotated"

private val PREFERRED_ANALYSIS_SIZE = Size(4000, 3000)
private val FALLBACK_ANALYSIS_SIZE = Size(1920, 1440)
private val PREFERRED_PREVIEW_SIZE = Size(1920, 1440)
private const val MAX_IMAGES = 3
private const val MAX_METADATA_ENTRIES = 64
private const val MIN_INITIAL_FRAMES = 4
private const val INITIAL_FRAME_WINDOW_MILLIS = 4_000L
private const val METADATA_WAIT_ATTEMPTS = 20
private const val METADATA_WAIT_MILLIS = 10L
private const val JPEG_QUALITY = 95
private const val JSON_INDENT_SPACES = 2
private const val TAG = "Camera2Controller"
