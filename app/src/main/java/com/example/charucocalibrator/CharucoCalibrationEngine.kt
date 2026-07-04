package com.example.charucocalibrator

import android.content.Context
import android.util.Log
import android.util.SizeF
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import java.io.File
import java.time.Instant
import kotlin.math.ceil

data class CalibrationCaptureHints(
    val focalLengthMm: Float? = null,
    val sensorPhysicalSize: SizeF? = null
)

data class CaptureSummary(
    val medianIso: Int?,
    val medianExposureTimeNs: Long?,
    val focusDistanceRange: Pair<Float, Float>?
)

data class CharucoCalibrationResult(
    val success: Boolean,
    val statusMessage: String,
    val reprojectionErrorPx: Double? = null,
    val perViewErrorsPx: List<Double>? = null,
    val medianPerViewErrorPx: Double? = null,
    val p90PerViewErrorPx: Double? = null,
    val solverVariant: String? = null,
    val solverFlags: String? = null,
    val fx: Double? = null,
    val fy: Double? = null,
    val cx: Double? = null,
    val cy: Double? = null,
    val cameraMatrix: Mat? = null,
    val distortionCoefficients: Mat? = null,
    val acceptedFrames: Int = 0,
    val usedFrames: Int = 0,
    val droppedFrames: Int = 0,
    val outlierThresholdPx: Double? = null,
    val captureSummary: CaptureSummary? = null
)

private data class CalibrationSolveResult(
    val success: Boolean,
    val message: String,
    val variantName: String,
    val flagsLabel: String,
    val reprojectionErrorPx: Double? = null,
    val cameraMatrix: Mat? = null,
    val distortion: Mat? = null,
    val perViewErrors: DoubleArray? = null
)

private data class SolverVariant(
    val name: String,
    val flags: Int,
    val flagsLabel: String
)

class CharucoCalibrationEngine {
    private val board by lazy { BoardConfig.createBoard() }
    private val detector by lazy { CharucoDetectorFactory.create(board) }

    fun calibrate(
        frames: List<AcceptedFrameRecord>,
        hints: CalibrationCaptureHints = CalibrationCaptureHints(),
        onProgress: ((processed: Int, total: Int) -> Unit)? = null
    ): CharucoCalibrationResult {
        if (frames.size < AcceptanceConfig.MIN_FRAMES_FOR_CALIBRATION) {
            return CharucoCalibrationResult(
                success = false,
                statusMessage =
                    "Need at least ${AcceptanceConfig.MIN_FRAMES_FOR_CALIBRATION} accepted frames, have ${frames.size}"
            )
        }

        if (!OpenCvInitializer.isInitialized()) {
            return CharucoCalibrationResult(
                success = false,
                statusMessage = "OpenCV is not initialized"
            )
        }

        val imageSize = Size(
            frames.first().imageWidth.toDouble(),
            frames.first().imageHeight.toDouble()
        )
        val captureSummary = buildCaptureSummary(frames)

        val correspondences = buildCorrespondences(frames, onProgress)
        if (correspondences.size < AcceptanceConfig.MIN_FRAMES_FOR_CALIBRATION) {
            releaseCorrespondences(correspondences)
            return CharucoCalibrationResult(
                success = false,
                statusMessage =
                    "Only ${correspondences.size}/${frames.size} frames produced valid correspondences"
            )
        }

        val firstPass = solveBestVariant(
            correspondences = correspondences,
            imageSize = imageSize,
            hints = hints
        )
        if (!firstPass.success) {
            releaseCorrespondences(correspondences)
            return CharucoCalibrationResult(
                success = false,
                statusMessage = firstPass.message
            )
        }

        val outlierThreshold = computeOutlierThreshold(firstPass.perViewErrors)
        val filtered = filterOutlierViews(
            correspondences = correspondences,
            perViewErrors = firstPass.perViewErrors,
            threshold = outlierThreshold
        )
        val droppedViews = correspondences.size - filtered.size
        val finalPass = if (
            droppedViews > 0 &&
            filtered.size >= AcceptanceConfig.MIN_FRAMES_FOR_CALIBRATION
        ) {
            Log.i(
                TAG,
                "Dropping $droppedViews/${correspondences.size} high-error views before final solve"
            )
            firstPass.cameraMatrix?.release()
            firstPass.distortion?.release()
            solveBestVariant(
                correspondences = filtered,
                imageSize = imageSize,
                hints = hints
            )
        } else {
            firstPass
        }

        releaseCorrespondences(correspondences)

        if (!finalPass.success) {
            return CharucoCalibrationResult(
                success = false,
                statusMessage = finalPass.message
            )
        }

        val cameraMatrix = finalPass.cameraMatrix ?: return CharucoCalibrationResult(
            success = false,
            statusMessage = "Calibration produced no camera matrix"
        )
        val distortion = finalPass.distortion ?: run {
            cameraMatrix.release()
            return CharucoCalibrationResult(success = false, statusMessage = "Calibration produced no distortion")
        }

        val perViewErrors = finalPass.perViewErrors?.toList()
        val usedViews = perViewErrors?.size ?: filtered.size
        val medianViewError = perViewErrors?.median()
        val p90ViewError = perViewErrors?.percentile90()
        return CharucoCalibrationResult(
            success = true,
            statusMessage = buildString {
                append("Calibration succeeded")
                append(" ($usedViews views used")
                if (droppedViews > 0) {
                    append(", $droppedViews dropped")
                }
                append(")")
            },
            reprojectionErrorPx = finalPass.reprojectionErrorPx,
            perViewErrorsPx = perViewErrors,
            medianPerViewErrorPx = medianViewError,
            p90PerViewErrorPx = p90ViewError,
            solverVariant = finalPass.variantName,
            solverFlags = finalPass.flagsLabel,
            fx = OpenCvMatAccess.readMatrixValue(cameraMatrix, 0, 0),
            fy = OpenCvMatAccess.readMatrixValue(cameraMatrix, 1, 1),
            cx = OpenCvMatAccess.readMatrixValue(cameraMatrix, 0, 2),
            cy = OpenCvMatAccess.readMatrixValue(cameraMatrix, 1, 2),
            cameraMatrix = cameraMatrix,
            distortionCoefficients = distortion,
            acceptedFrames = frames.size,
            usedFrames = usedViews,
            droppedFrames = droppedViews,
            outlierThresholdPx = outlierThreshold,
            captureSummary = captureSummary
        )
    }

    private data class FrameCorrespondence(
        val frame: AcceptedFrameRecord,
        val set: CorrespondenceSet
    )

    private fun buildCorrespondences(
        frames: List<AcceptedFrameRecord>,
        onProgress: ((processed: Int, total: Int) -> Unit)?
    ): List<FrameCorrespondence> {
        val correspondences = ArrayList<FrameCorrespondence>()
        frames.forEachIndexed { index, frame ->
            onProgress?.invoke(index + 1, frames.size)
            val set = CharucoCorrespondenceBuilder.build(frame, board, detector) ?: return@forEachIndexed
            correspondences += FrameCorrespondence(frame, set)
        }
        return correspondences
    }

    private fun solverVariants(viewCount: Int): List<SolverVariant> {
        return listOf(
            SolverVariant(
                name = "flags_zero",
                flags = 0,
                flagsLabel = "0"
            )
        )
    }

    private fun solveBestVariant(
        correspondences: List<FrameCorrespondence>,
        imageSize: Size,
        hints: CalibrationCaptureHints
    ): CalibrationSolveResult {
        val candidates = solverVariants(correspondences.size).mapNotNull { variant ->
            val solved = solveCalibration(
                correspondences = correspondences,
                imageSize = imageSize,
                hints = hints,
                variant = variant
            )
            if (solved.success) solved else {
                solved.cameraMatrix?.release()
                solved.distortion?.release()
                null
            }
        }

        if (candidates.isEmpty()) {
            return CalibrationSolveResult(
                success = false,
                message = "All solver variants failed",
                variantName = "none",
                flagsLabel = "none"
            )
        }

        return candidates.minWith(
            compareBy<CalibrationSolveResult> { it.reprojectionErrorPx ?: Double.MAX_VALUE }
                .thenBy { it.perViewErrors?.median() ?: Double.MAX_VALUE }
                .thenBy { it.perViewErrors?.percentile90() ?: Double.MAX_VALUE }
        )
    }

    private fun computeOutlierThreshold(perViewErrors: DoubleArray?): Double {
        if (perViewErrors == null || perViewErrors.isEmpty()) {
            return AcceptanceConfig.MAX_PER_VIEW_REPROJECTION_ERROR_PX
        }
        val median = perViewErrors.sorted().let { sorted ->
            sorted[sorted.size / 2]
        }
        return maxOf(
            AcceptanceConfig.MAX_PER_VIEW_REPROJECTION_ERROR_PX,
            median * 2.0
        )
    }

    private fun filterOutlierViews(
        correspondences: List<FrameCorrespondence>,
        perViewErrors: DoubleArray?,
        threshold: Double
    ): List<FrameCorrespondence> {
        if (perViewErrors == null || perViewErrors.size != correspondences.size) {
            return correspondences
        }
        return correspondences.filterIndexed { index, _ ->
            perViewErrors[index] <= threshold
        }
    }

    private fun solveCalibration(
        correspondences: List<FrameCorrespondence>,
        imageSize: Size,
        hints: CalibrationCaptureHints,
        variant: SolverVariant
    ): CalibrationSolveResult {
        val objectPointSets = correspondences.map { it.set.objectPoints }
        val imagePointSets = correspondences.map { it.set.imagePoints }

        val cameraMatrix = Mat.eye(3, 3, CvType.CV_64F)
        val distortion = Mat.zeros(1, 5, CvType.CV_64F)
        val rvecs = ArrayList<Mat>()
        val tvecs = ArrayList<Mat>()
        val stdIntrinsics = Mat()
        val stdExtrinsics = Mat()
        val perViewErrors = Mat()

        return try {
            val reprojectionError = Calib3d.calibrateCameraExtended(
                objectPointSets,
                imagePointSets,
                imageSize,
                cameraMatrix,
                distortion,
                rvecs,
                tvecs,
                stdIntrinsics,
                stdExtrinsics,
                perViewErrors,
                variant.flags,
                TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 100, 1e-7)
            )

            rvecs.forEach(Mat::release)
            tvecs.forEach(Mat::release)
            stdIntrinsics.release()
            stdExtrinsics.release()

            val perViewArray = DoubleArray(perViewErrors.rows()) { index ->
                OpenCvMatAccess.readMatrixValue(perViewErrors, index, 0)
            }
            perViewErrors.release()

            CalibrationSolveResult(
                success = true,
                message = "ok",
                variantName = variant.name,
                flagsLabel = variant.flagsLabel,
                reprojectionErrorPx = reprojectionError,
                cameraMatrix = cameraMatrix,
                distortion = distortion,
                perViewErrors = perViewArray
            )
        } catch (exception: Exception) {
            rvecs.forEach(Mat::release)
            tvecs.forEach(Mat::release)
            stdIntrinsics.release()
            stdExtrinsics.release()
            perViewErrors.release()
            cameraMatrix.release()
            distortion.release()
            CalibrationSolveResult(
                success = false,
                message = "Calibration failed (${variant.name}): ${exception.message}",
                variantName = variant.name,
                flagsLabel = variant.flagsLabel
            )
        }
    }

    private fun releaseCorrespondences(correspondences: List<FrameCorrespondence>) {
        correspondences.forEach {
            it.set.objectPoints.release()
            it.set.imagePoints.release()
        }
    }

    private fun buildCaptureSummary(frames: List<AcceptedFrameRecord>): CaptureSummary {
        val metadata = frames.mapNotNull { frame ->
            runCatching {
                FrameMetadata.fromJson(JSONObject(frame.metadataFile.readText()))
            }.getOrNull()
        }
        val isoValues = metadata.mapNotNull { it.isoSensitivity }.sorted()
        val exposureValues = metadata.mapNotNull { it.exposureTimeNs }.sorted()
        val focusValues = metadata.mapNotNull { it.lensFocusDistance }.filter { it > 0f }
        return CaptureSummary(
            medianIso = isoValues.medianInt(),
            medianExposureTimeNs = exposureValues.medianLong(),
            focusDistanceRange = focusValues.takeIf { it.isNotEmpty() }?.let {
                it.min() to it.max()
            }
        )
    }

    fun exportResult(
        context: Context,
        result: CharucoCalibrationResult,
        cameraId: String,
        imageWidth: Int,
        imageHeight: Int,
        acceptedFrames: Int,
        captureSessionId: String? = null
    ): File? {
        val cameraMatrix = result.cameraMatrix ?: return null
        val distortion = result.distortionCoefficients ?: return null
        val directory = context.getExternalFilesDir(null) ?: return null
        val output = File(directory, CALIBRATION_OUTPUT_FILE)

        val distortionValues = MatOfDouble()
        return try {
            distortion.convertTo(distortionValues, CvType.CV_64F)
            val coeffs = DoubleArray(5) { index ->
                if (index < distortionValues.total().toInt()) {
                    OpenCvMatAccess.readMatrixValue(distortionValues, index, 0)
                } else {
                    0.0
                }
            }

            val matrixValues = Array(3) { row ->
                DoubleArray(3) { column ->
                    OpenCvMatAccess.readMatrixValue(cameraMatrix, row, column)
                }
            }

            output.writeText(
                JSONObject().apply {
                    put("source", "android_camera2_charuco_live")
                    put("device_hint", "Samsung Galaxy S23 Ultra")
                    put("camera_id", cameraId)
                    put("image_width", imageWidth)
                    put("image_height", imageHeight)
                    put("orientation_note", ORIENTATION_NOTE)
                    put(
                        "board",
                        JSONObject().apply {
                            put("type", "charuco")
                            put("squares_x", BoardConfig.SQUARES_X)
                            put("squares_y", BoardConfig.SQUARES_Y)
                            put("square_length_m", BoardConfig.SQUARE_LENGTH_M)
                            put("marker_length_m", BoardConfig.MARKER_LENGTH_M)
                            put("dictionary", BoardConfig.DICT_NAME)
                        }
                    )
                    put("camera_matrix", matrixValues.toJsonArray())
                    put("fx", result.fx)
                    put("fy", result.fy)
                    put("cx", result.cx)
                    put("cy", result.cy)
                    put("distortion_model", "opencv_pinhole_5")
                    put("distortion_coefficients", coeffs.toJsonArray())
                    put("reprojection_error_px", result.reprojectionErrorPx)
                    result.perViewErrorsPx?.let { errors ->
                        put("per_view_errors_px", errors.toJsonArray())
                    }
                    put("median_per_view_error_px", result.medianPerViewErrorPx)
                    put("p90_per_view_error_px", result.p90PerViewErrorPx)
                    put("solver_variant", result.solverVariant)
                    put("solver_flags", result.solverFlags)
                    put("accepted_frames", acceptedFrames)
                    put("used_frames", result.usedFrames)
                    put("dropped_frames", result.droppedFrames)
                    captureSessionId?.let { put(CaptureSessionManager.METADATA_KEY, it) }
                    put("outlier_threshold_px", result.outlierThresholdPx)
                    result.captureSummary?.let { summary ->
                        put(
                            "capture_summary",
                            JSONObject().apply {
                                put("median_iso", summary.medianIso ?: JSONObject.NULL)
                                put(
                                    "median_exposure_time_ns",
                                    summary.medianExposureTimeNs ?: JSONObject.NULL
                                )
                                summary.focusDistanceRange?.let { (min, max) ->
                                    put("focus_distance_range", JSONArray().put(min).put(max))
                                }
                            }
                        )
                    }
                    put("generated_at_utc", Instant.now().toString())
                    put("opencv_version", OpenCVLoader.OPENCV_VERSION)
                }.toString(JSON_INDENT_SPACES)
            )
            output
        } finally {
            distortionValues.release()
        }
    }

    private fun Array<DoubleArray>.toJsonArray(): JSONArray =
        JSONArray().also { array ->
            forEach { row ->
                array.put(JSONArray().also { inner -> row.forEach(inner::put) })
            }
        }

    private fun DoubleArray.toJsonArray(): JSONArray =
        JSONArray().also { array -> forEach(array::put) }

    private fun List<Double>.toJsonArray(): JSONArray =
        JSONArray().also { array -> forEach(array::put) }

    private fun List<Double>.median(): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        return sorted[sorted.size / 2]
    }

    private fun DoubleArray.median(): Double = toList().median()

    private fun List<Double>.percentile90(): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val index = ceil(0.9 * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[index]
    }

    private fun DoubleArray.percentile90(): Double = toList().percentile90()

    private fun List<Int>.medianInt(): Int? {
        if (isEmpty()) return null
        return sorted()[size / 2]
    }

    private fun List<Long>.medianLong(): Long? {
        if (isEmpty()) return null
        return sorted()[size / 2]
    }

    companion object {
        const val CALIBRATION_OUTPUT_FILE = "charuco_calibration_result.json"
        private const val JSON_INDENT_SPACES = 2
        private const val TAG = "CharucoCalibrationEngine"
    }
}
