package com.example.charucocalibrator

import android.content.Context
import android.media.Image
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class FrameAnalysisPipeline(
    context: Context,
    private val cameraId: String,
    private val onSnapshot: (FrameAnalysisSnapshot) -> Unit
) {
    private val applicationContext = context.applicationContext
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val calibrationExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val processedCounter = AtomicLong(0)
    private val charucoDetector by lazy { CharucoFrameDetector() }
    private val frameAcceptance = FrameAcceptanceController()
    private val acceptedFrameStore = AcceptedFrameStore(applicationContext)
    private val calibrationEngine by lazy { CharucoCalibrationEngine() }

    @Volatile
    private var isCalibrating = false

    @Volatile
    private var latestCalibrationStatus: String? = null

    @Volatile
    private var latestCalibrationPath: String? = null

    @Volatile
    private var latestCalibrationResult: CharucoCalibrationResult? = null

    @Volatile
    private var imageWidth = 0

    @Volatile
    private var imageHeight = 0

    @Volatile
    private var lastProcessTimeNs = 0L

    @Volatile
    private var rawFrameCount = 0L

    @Volatile
    private var latestSensorTimestampNs: Long? = null

    private val processTimestampsNs = ArrayDeque<Long>()

    @Volatile
    private var released = false

    @Volatile
    private var latestSharpness: Double? = null

    @Volatile
    private var latestDetection: DetectionResult = DetectionResult.idle()

    @Volatile
    private var autoCaptureEnabled = false

    @Volatile
    private var analysisPausedForCalibration = false

    fun submitFrame(image: Image, rawCount: Long, sensorTimestampNs: Long?) {
        if (released) return
        rawFrameCount = rawCount
        latestSensorTimestampNs = sensorTimestampNs

        val now = System.nanoTime()
        if (now - lastProcessTimeNs < MIN_PROCESS_INTERVAL_NS) {
            publishSnapshot(processedCounter.get(), latestSharpness, latestDetection)
            return
        }
        lastProcessTimeNs = now

        val gray = try {
            YuvToGrayMat.fromYuv420888(image)
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to convert YUV frame to grayscale", exception)
            publishSnapshot(
                processedCounter.get(),
                null,
                DetectionResult.failure("grayscale_conversion_failed")
            )
            return
        }

        analysisExecutor.execute {
            if (released) {
                gray.release()
                return@execute
            }
            processGrayFrame(gray)
        }
    }

    fun startAutoCapture() {
        autoCaptureEnabled = true
        frameAcceptance.markAutoCaptureStarted()
        publishSnapshot(processedCounter.get(), latestSharpness, latestDetection)
    }

    fun stopAutoCapture() {
        autoCaptureEnabled = false
        publishSnapshot(processedCounter.get(), latestSharpness, latestDetection)
    }

    fun clearAcceptedFrames() {
        acceptedFrameStore.clear()
        frameAcceptance.clearHistory()
        publishSnapshot(processedCounter.get(), latestSharpness, latestDetection)
    }

    fun runCalibration() {
        if (isCalibrating) return

        val frames = acceptedFrameStore.framesForCalibration()
        latestCalibrationResult = null
        latestCalibrationPath = null
        latestCalibrationStatus = when {
            frames.isEmpty() -> "No accepted frames to calibrate"
            else -> "Calibrating with ${frames.size} frames..."
        }
        publishSnapshot(processedCounter.get(), latestSharpness, latestDetection)

        if (frames.isEmpty()) return

        isCalibrating = true
        publishSnapshot(processedCounter.get(), latestSharpness, latestDetection)

        analysisPausedForCalibration = true
        calibrationExecutor.execute {
            try {
                imageWidth = frames.first().imageWidth
                imageHeight = frames.first().imageHeight
                Log.i(TAG, "Starting calibration with ${frames.size} accepted frames")
                val result = OpenCvInitializer.withLock {
                    calibrationEngine.calibrate(frames) { processed, total ->
                        latestCalibrationStatus = "Calibrating frame $processed/$total..."
                        publishSnapshot(processedCounter.get(), latestSharpness, latestDetection)
                    }
                }
                latestCalibrationResult = result
                latestCalibrationStatus = result.statusMessage
                latestCalibrationPath = if (result.success) {
                    calibrationEngine.exportResult(
                        context = applicationContext,
                        result = result,
                        cameraId = cameraId,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                        acceptedFrames = frames.size
                    )?.absolutePath
                } else {
                    null
                }
                Log.i(
                    TAG,
                    "Calibration finished: success=${result.success}, message=${result.statusMessage}"
                )
            } catch (exception: Exception) {
                Log.e(TAG, "Calibration crashed", exception)
                latestCalibrationStatus = "Calibration error: ${exception.message ?: exception.javaClass.simpleName}"
            } finally {
                analysisPausedForCalibration = false
                isCalibrating = false
                publishSnapshot(processedCounter.get(), latestSharpness, latestDetection)
            }
        }
    }

    fun release() {
        released = true
        analysisExecutor.shutdown()
        calibrationExecutor.shutdown()
        acceptedFrameStore.clear()
    }

    private fun processGrayFrame(gray: Mat) {
        try {
            if (analysisPausedForCalibration) {
                return
            }

            if (!OpenCvInitializer.isInitialized()) {
                publishSnapshot(
                    processedCounter.get(),
                    null,
                    DetectionResult.failure("opencv_not_initialized")
                )
                return
            }

            val frameResult = OpenCvInitializer.withLock {
                val sharpness = computeLaplacianVariance(gray)
                val detection = charucoDetector.detect(gray)
                sharpness to detection
            }
            val sharpness = frameResult.first
            val detection = frameResult.second
            imageWidth = gray.cols()
            imageHeight = gray.rows()
            latestSharpness = sharpness
            latestDetection = detection
            val processed = processedCounter.incrementAndGet()
            updateProcessingFps()

            if (autoCaptureEnabled) {
                val decision = frameAcceptance.evaluate(
                    detection = detection,
                    sharpness = sharpness,
                    frameWidth = gray.cols(),
                    frameHeight = gray.rows(),
                    acceptedCount = acceptedFrameStore.count
                )
                if (decision.accepted) {
                    acceptedFrameStore.saveFrame(
                        gray = gray,
                        cameraId = cameraId,
                        sharpness = sharpness,
                        detection = detection,
                        sensorTimestampNs = latestSensorTimestampNs,
                        reason = decision.message
                    )
                } else {
                    detection.releaseCorrespondences()
                }
            } else {
                detection.releaseCorrespondences()
            }

            publishSnapshot(processed, sharpness, detection)
        } finally {
            gray.release()
        }
    }

    private fun computeLaplacianVariance(gray: Mat): Double {
        val laplacian = Mat()
        val mean = MatOfDouble()
        val stddev = MatOfDouble()
        return try {
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
            Core.meanStdDev(laplacian, mean, stddev)
            val sigma = stddev.get(0, 0)[0]
            sigma * sigma
        } finally {
            laplacian.release()
            mean.release()
            stddev.release()
        }
    }

    private fun updateProcessingFps() {
        val now = System.nanoTime()
        processTimestampsNs.addLast(now)
        while (processTimestampsNs.size > FPS_WINDOW_SIZE) {
            processTimestampsNs.removeFirst()
        }
    }

    private fun currentProcessingFps(): Double {
        if (processTimestampsNs.size < 2) return 0.0
        val durationNs = processTimestampsNs.last() - processTimestampsNs.first()
        if (durationNs <= 0L) return 0.0
        return (processTimestampsNs.size - 1) * 1_000_000_000.0 / durationNs
    }

    private fun publishSnapshot(
        processedCount: Long,
        sharpness: Double?,
        detection: DetectionResult
    ) {
        val snapshot = FrameAnalysisSnapshot(
            rawFrameCount = rawFrameCount,
            processedFrameCount = processedCount,
            sharpness = sharpness,
            processingFps = currentProcessingFps(),
            markerCount = detection.markerCount,
            charucoCornerCount = detection.charucoCornerCount,
            detectionStatus = detection.status,
            rejectionReason = detection.rejectionReason,
            bboxAreaRatio = detection.bbox?.areaRatio,
            acceptedFrameCount = acceptedFrameStore.count,
            maxAcceptedFrames = AcceptanceConfig.MAX_ACCEPTED_FRAMES,
            lastAcceptanceReason = frameAcceptance.lastDecisionMessage,
            autoCaptureActive = autoCaptureEnabled,
            calibrationStatus = latestCalibrationStatus,
            calibrationReprojectionError = latestCalibrationResult?.reprojectionErrorPx,
            calibrationFx = latestCalibrationResult?.fx,
            calibrationFy = latestCalibrationResult?.fy,
            calibrationCx = latestCalibrationResult?.cx,
            calibrationCy = latestCalibrationResult?.cy,
            calibrationOutputPath = latestCalibrationPath,
            isCalibrating = isCalibrating
        )
        mainHandler.post {
            if (!released) onSnapshot(snapshot)
        }
    }

    companion object {
        private const val TAG = "FrameAnalysisPipeline"
        private const val MIN_PROCESS_INTERVAL_NS = 250_000_000L
        private const val FPS_WINDOW_SIZE = 8
    }
}
