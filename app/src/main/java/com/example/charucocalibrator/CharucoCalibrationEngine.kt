package com.example.charucocalibrator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import java.io.File
import java.time.Instant

data class CharucoCalibrationResult(
    val success: Boolean,
    val statusMessage: String,
    val reprojectionErrorPx: Double? = null,
    val fx: Double? = null,
    val fy: Double? = null,
    val cx: Double? = null,
    val cy: Double? = null,
    val cameraMatrix: Mat? = null,
    val distortionCoefficients: Mat? = null
)

class CharucoCalibrationEngine {
    private val board by lazy { BoardConfig.createBoard() }

    fun calibrate(frames: List<AcceptedFrameRecord>): CharucoCalibrationResult {
        if (frames.size < 3) {
            return CharucoCalibrationResult(
                success = false,
                statusMessage = "Need at least 3 accepted frames, have ${frames.size}"
            )
        }

        if (!OpenCvInitializer.ensureInitialized()) {
            return CharucoCalibrationResult(
                success = false,
                statusMessage = "OpenCV is not initialized"
            )
        }

        val imageSize = Size(
            frames.first().imageWidth.toDouble(),
            frames.first().imageHeight.toDouble()
        )

        val objectPointSets = ArrayList<Mat>()
        val imagePointSets = ArrayList<Mat>()

        frames.forEach { frame ->
            val objPoints = Mat()
            val imgPoints = Mat()
            try {
                board.matchImagePoints(
                    listOf(frame.charucoCorners),
                    frame.charucoIds,
                    objPoints,
                    imgPoints
                )
                if (objPoints.rows() >= AcceptanceConfig.MIN_CHARUCO_CORNERS) {
                    objectPointSets.add(objPoints)
                    imagePointSets.add(imgPoints)
                } else {
                    objPoints.release()
                    imgPoints.release()
                }
            } catch (_: Exception) {
                objPoints.release()
                imgPoints.release()
            }
        }

        if (objectPointSets.size < 3) {
            return CharucoCalibrationResult(
                success = false,
                statusMessage = "Only ${objectPointSets.size} frames produced valid correspondences"
            )
        }

        val cameraMatrix = Mat.eye(3, 3, org.opencv.core.CvType.CV_64F)
        val distortion = Mat.zeros(1, 5, org.opencv.core.CvType.CV_64F)
        val rvecs = ArrayList<Mat>()
        val tvecs = ArrayList<Mat>()

        return try {
            val reprojectionError = Calib3d.calibrateCamera(
                objectPointSets,
                imagePointSets,
                imageSize,
                cameraMatrix,
                distortion,
                rvecs,
                tvecs,
                Calib3d.CALIB_FIX_K3,
                TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 100, 1e-6)
            )

            rvecs.forEach(Mat::release)
            tvecs.forEach(Mat::release)
            objectPointSets.forEach(Mat::release)
            imagePointSets.forEach(Mat::release)

            CharucoCalibrationResult(
                success = true,
                statusMessage = "Calibration succeeded with ${objectPointSets.size} views",
                reprojectionErrorPx = reprojectionError,
                fx = cameraMatrix.get(0, 0)[0],
                fy = cameraMatrix.get(1, 1)[0],
                cx = cameraMatrix.get(0, 2)[0],
                cy = cameraMatrix.get(1, 2)[0],
                cameraMatrix = cameraMatrix,
                distortionCoefficients = distortion
            )
        } catch (exception: Exception) {
            objectPointSets.forEach(Mat::release)
            imagePointSets.forEach(Mat::release)
            cameraMatrix.release()
            distortion.release()
            CharucoCalibrationResult(
                success = false,
                statusMessage = "Calibration failed: ${exception.message}"
            )
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
            distortion.convertTo(distortionValues, org.opencv.core.CvType.CV_64F)
            val coeffs = DoubleArray(5) { index ->
                if (index < distortionValues.total().toInt()) {
                    distortionValues.get(index, 0)[0]
                } else {
                    0.0
                }
            }

            val matrixValues = Array(3) { row ->
                DoubleArray(3) { column -> cameraMatrix.get(row, column)[0] }
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
    }
}
