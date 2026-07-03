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

data class CalibrationCaptureHints(
    val focalLengthMm: Float? = null,
    val sensorPhysicalSize: SizeF? = null
)

data class CharucoCalibrationResult(
    val success: Boolean,
    val statusMessage: String,
    val reprojectionErrorPx: Double? = null,
    val fx: Double? = null,
    val fy: Double? = null,
    val cx: Double? = null,
    val cy: Double? = null,
    val cameraMatrix: Mat? = null,
    val distortionCoefficients: Mat? = null,
    val usedFrames: Int = 0
)

private data class CalibrationSolveResult(
    val success: Boolean,
    val message: String,
    val reprojectionErrorPx: Double? = null,
    val cameraMatrix: Mat? = null,
    val distortion: Mat? = null,
    val perViewErrors: DoubleArray? = null
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

        val correspondences = buildCorrespondences(frames, onProgress)
        if (correspondences.size < AcceptanceConfig.MIN_FRAMES_FOR_CALIBRATION) {
            releaseCorrespondences(correspondences)
            return CharucoCalibrationResult(
                success = false,
                statusMessage =
                    "Only ${correspondences.size}/${frames.size} frames produced valid correspondences"
            )
        }

        val firstPass = solveCalibration(
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

        val filtered = filterOutlierViews(
            correspondences = correspondences,
            perViewErrors = firstPass.perViewErrors
        )
        val droppedViews = correspondences.size - filtered.size
        val finalPass = if (
            droppedViews > 0 &&
            filtered.size >= AcceptanceConfig.MIN_FRAMES_FOR_CALIBRATION
        ) {
            Log.i(TAG, "Dropping $droppedViews high-error views before final solve")
            firstPass.cameraMatrix?.release()
            firstPass.distortion?.release()
            solveCalibration(
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

        return CharucoCalibrationResult(
            success = true,
            statusMessage =
                "Calibration succeeded with ${finalPass.perViewErrors?.size ?: filtered.size} views",
            reprojectionErrorPx = finalPass.reprojectionErrorPx,
            fx = OpenCvMatAccess.readMatrixValue(cameraMatrix, 0, 0),
            fy = OpenCvMatAccess.readMatrixValue(cameraMatrix, 1, 1),
            cx = OpenCvMatAccess.readMatrixValue(cameraMatrix, 0, 2),
            cy = OpenCvMatAccess.readMatrixValue(cameraMatrix, 1, 2),
            cameraMatrix = cameraMatrix,
            distortionCoefficients = distortion,
            usedFrames = finalPass.perViewErrors?.size ?: filtered.size
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

    private fun filterOutlierViews(
        correspondences: List<FrameCorrespondence>,
        perViewErrors: DoubleArray?
    ): List<FrameCorrespondence> {
        if (perViewErrors == null || perViewErrors.size != correspondences.size) {
            return correspondences
        }
        return correspondences.filterIndexed { index, _ ->
            perViewErrors[index] <= AcceptanceConfig.MAX_PER_VIEW_REPROJECTION_ERROR_PX
        }
    }

    private fun solveCalibration(
        correspondences: List<FrameCorrespondence>,
        imageSize: Size,
        hints: CalibrationCaptureHints
    ): CalibrationSolveResult {
        val objectPointSets = correspondences.map { it.set.objectPoints }
        val imagePointSets = correspondences.map { it.set.imagePoints }

        val cameraMatrix = CameraMatrixSeeder.seed(
            imageWidth = imageSize.width.toInt(),
            imageHeight = imageSize.height.toInt(),
            focalLengthMm = hints.focalLengthMm,
            sensorPhysicalSize = hints.sensorPhysicalSize
        )
        val distortion = Mat.zeros(1, 5, CvType.CV_64F)
        val rvecs = ArrayList<Mat>()
        val tvecs = ArrayList<Mat>()
        val stdIntrinsics = Mat()
        val stdExtrinsics = Mat()
        val perViewErrors = Mat()

        val flags = Calib3d.CALIB_USE_INTRINSIC_GUESS or
            Calib3d.CALIB_FIX_ASPECT_RATIO or
            Calib3d.CALIB_FIX_K3

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
                flags,
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
                message = "Calibration failed: ${exception.message}"
            )
        }
    }

    private fun releaseCorrespondences(correspondences: List<FrameCorrespondence>) {
        correspondences.forEach {
            it.set.objectPoints.release()
            it.set.imagePoints.release()
        }
    }

    fun exportResult(
        context: Context,
        result: CharucoCalibrationResult,
        cameraId: String,
        imageWidth: Int,
        imageHeight: Int,
        acceptedFrames: Int
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
                    put("accepted_frames", acceptedFrames)
                    put("used_frames", result.usedFrames)
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

    companion object {
        const val CALIBRATION_OUTPUT_FILE = "charuco_calibration_result.json"
        private const val JSON_INDENT_SPACES = 2
        private const val TAG = "CharucoCalibrationEngine"
    }
}
