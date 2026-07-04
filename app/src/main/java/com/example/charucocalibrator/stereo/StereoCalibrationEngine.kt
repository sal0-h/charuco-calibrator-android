package com.example.charucocalibrator.stereo

import android.content.Context
import android.util.Log
import com.example.charucocalibrator.AcceptanceConfig
import com.example.charucocalibrator.DEFAULT_CAMERA_ID
import com.example.charucocalibrator.Dimensions
import com.example.charucocalibrator.OpenCvInitializer
import com.example.charucocalibrator.OpenCvMatAccess
import com.example.charucocalibrator.stereo.model.StereoCalibrationResult
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import java.io.File
import java.time.Instant
import kotlin.math.sqrt

class StereoCalibrationEngine {
    fun calibrate(
        pairs: List<StereoBoardPairRecord>,
        leftPhysicalCameraId: String,
        rightPhysicalCameraId: String,
        logicalCameraId: String = DEFAULT_CAMERA_ID
    ): StereoCalibrationResult {
        if (pairs.size < AcceptanceConfig.MIN_FRAMES_FOR_CALIBRATION) {
            return StereoCalibrationResult(
                success = false,
                statusMessage =
                    "Need at least ${AcceptanceConfig.MIN_FRAMES_FOR_CALIBRATION} board pairs, have ${pairs.size}"
            )
        }
        if (!OpenCvInitializer.isInitialized()) {
            return StereoCalibrationResult(
                success = false,
                statusMessage = "OpenCV is not initialized"
            )
        }

        return OpenCvInitializer.withLock {
            val correspondences = pairs.mapNotNull(StereoCorrespondenceMatcher::matchPair)
            if (correspondences.size < AcceptanceConfig.MIN_FRAMES_FOR_CALIBRATION) {
                correspondences.forEach(StereoCorrespondenceMatcher::release)
                return@withLock StereoCalibrationResult(
                    success = false,
                    statusMessage =
                        "Only ${correspondences.size}/${pairs.size} pairs produced >= " +
                            "${AcceptanceConfig.MIN_CHARUCO_CORNERS} common corners"
                )
            }

            val leftSize = imageSize(pairs.first().leftImageFile)
            val rightSize = imageSize(pairs.first().rightImageFile)
            val timestampDeltas = pairs.mapNotNull { it.timestampDeltaNs }

            val leftObjectSets = correspondences.map { it.objectPoints }
            val leftImageSets = correspondences.map { it.leftImagePoints }
            val rightImageSets = correspondences.map { it.rightImagePoints }

            val k1 = Mat.eye(3, 3, CvType.CV_64F)
            val d1 = Mat.zeros(1, 5, CvType.CV_64F)
            val k2 = Mat.eye(3, 3, CvType.CV_64F)
            val d2 = Mat.zeros(1, 5, CvType.CV_64F)
            val leftRvecs = ArrayList<Mat>()
            val leftTvecs = ArrayList<Mat>()
            val rightRvecs = ArrayList<Mat>()
            val rightTvecs = ArrayList<Mat>()
            val leftStdIntrinsics = Mat()
            val leftStdExtrinsics = Mat()
            val rightStdIntrinsics = Mat()
            val rightStdExtrinsics = Mat()
            val leftPerView = Mat()
            val rightPerView = Mat()

            try {
                val leftRms = Calib3d.calibrateCameraExtended(
                    leftObjectSets,
                    leftImageSets,
                    leftSize,
                    k1,
                    d1,
                    leftRvecs,
                    leftTvecs,
                    leftStdIntrinsics,
                    leftStdExtrinsics,
                    leftPerView,
                    0,
                    TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 100, 1e-7)
                )
                val rightRms = Calib3d.calibrateCameraExtended(
                    leftObjectSets,
                    rightImageSets,
                    rightSize,
                    k2,
                    d2,
                    rightRvecs,
                    rightTvecs,
                    rightStdIntrinsics,
                    rightStdExtrinsics,
                    rightPerView,
                    0,
                    TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 100, 1e-7)
                )

                val rotation = Mat()
                val translation = Mat()
                val essential = Mat()
                val fundamental = Mat()
                val stereoPerView = Mat()
                val stereoRvecs = ArrayList<Mat>()
                val stereoTvecs = ArrayList<Mat>()
                val stereoFlags = Calib3d.CALIB_FIX_INTRINSIC
                val stereoRms = Calib3d.stereoCalibrateExtended(
                    leftObjectSets,
                    leftImageSets,
                    rightImageSets,
                    k1,
                    d1,
                    k2,
                    d2,
                    leftSize,
                    rotation,
                    translation,
                    essential,
                    fundamental,
                    stereoRvecs,
                    stereoTvecs,
                    stereoPerView,
                    stereoFlags,
                    TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 100, 1e-7)
                )

                val baseline = translationToBaselineMeters(translation)
                val perViewErrors = DoubleArray(stereoPerView.rows()) { index ->
                    OpenCvMatAccess.readMatrixValue(stereoPerView, index, 0)
                }.toList()
                val result = StereoCalibrationResult(
                    success = true,
                    statusMessage = "Stereo calibration complete",
                    logicalCameraId = logicalCameraId,
                    leftPhysicalCameraId = leftPhysicalCameraId,
                    rightPhysicalCameraId = rightPhysicalCameraId,
                    pairCount = correspondences.size,
                    leftImageSize = Dimensions(leftSize.width.toInt(), leftSize.height.toInt()),
                    rightImageSize = Dimensions(rightSize.width.toInt(), rightSize.height.toInt()),
                    k1 = k1.toMatrix3x3(),
                    d1 = d1.toCoefficients(),
                    k2 = k2.toMatrix3x3(),
                    d2 = d2.toCoefficients(),
                    rotation = rotation.toMatrix3x3(),
                    translation = translation.toVector3(),
                    baselineM = baseline,
                    stereoRms = stereoRms,
                    perViewErrors = perViewErrors,
                    solverFlags = 0,
                    solverNote =
                        "intrinsics from per-camera calibrateCamera (left_rms=$leftRms, right_rms=$rightRms); " +
                            "stereoCalibrate with CALIB_FIX_INTRINSIC",
                    medianTimestampDeltaNs = StereoTimestampUtils.medianDeltaNs(timestampDeltas)
                )

                stereoRvecs.forEach(Mat::release)
                stereoTvecs.forEach(Mat::release)
                rotation.release()
                translation.release()
                essential.release()
                fundamental.release()
                stereoPerView.release()
                result
            } catch (exception: Exception) {
                Log.e(TAG, "Stereo calibration failed", exception)
                StereoCalibrationResult(
                    success = false,
                    statusMessage = exception.message ?: "Stereo calibration failed"
                )
            } finally {
                correspondences.forEach(StereoCorrespondenceMatcher::release)
                k1.release()
                d1.release()
                k2.release()
                d2.release()
                leftRvecs.forEach(Mat::release)
                leftTvecs.forEach(Mat::release)
                rightRvecs.forEach(Mat::release)
                rightTvecs.forEach(Mat::release)
                leftStdIntrinsics.release()
                leftStdExtrinsics.release()
                rightStdIntrinsics.release()
                rightStdExtrinsics.release()
                leftPerView.release()
                rightPerView.release()
            }
        }
    }

    fun exportResult(
        context: Context,
        result: StereoCalibrationResult
    ): File? {
        if (!result.success) return null
        val directory = context.getExternalFilesDir(null) ?: return null
        val output = File(directory, "stereo_calibration.json")
        output.writeText(result.toJson().apply {
            put("generated_at_utc", Instant.now().toString())
        }.toString(2))
        return output
    }

    private fun imageSize(imageFile: File): Size {
        val image = org.opencv.imgcodecs.Imgcodecs.imread(imageFile.absolutePath, org.opencv.imgcodecs.Imgcodecs.IMREAD_GRAYSCALE)
        return try {
            Size(image.cols().toDouble(), image.rows().toDouble())
        } finally {
            image.release()
        }
    }

    private fun translationToBaselineMeters(translation: Mat): Double {
        val values = DoubleArray(3) { index ->
            OpenCvMatAccess.readMatrixValue(translation, index, 0)
        }
        return sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
    }

    private fun Mat.toMatrix3x3(): Array<DoubleArray> =
        Array(3) { row ->
            DoubleArray(3) { column ->
                OpenCvMatAccess.readMatrixValue(this, row, column)
            }
        }

    private fun Mat.toCoefficients(): DoubleArray =
        DoubleArray(5) { index ->
            OpenCvMatAccess.readMatrixValue(this, 0, index)
        }

    private fun Mat.toVector3(): DoubleArray =
        DoubleArray(3) { index ->
            OpenCvMatAccess.readMatrixValue(this, index, 0)
        }

    companion object {
        private const val TAG = "StereoCalibrationEngine"
    }
}
