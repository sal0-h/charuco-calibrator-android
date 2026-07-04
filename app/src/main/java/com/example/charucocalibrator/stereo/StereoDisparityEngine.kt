package com.example.charucocalibrator.stereo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.example.charucocalibrator.OpenCvInitializer
import com.example.charucocalibrator.OpenCvMatAccess
import com.example.charucocalibrator.stereo.model.StereoCalibrationResult
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.calib3d.Calib3d
import org.opencv.calib3d.StereoSGBM
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import kotlin.math.max

data class StereoSgbmParams(
    val minDisparity: Int = 0,
    val numDisparities: Int = 128,
    val blockSize: Int = 5,
    val p1: Int = 8 * 3 * 5 * 5,
    val p2: Int = 32 * 3 * 5 * 5,
    val disp12MaxDiff: Int = 1,
    val uniquenessRatio: Int = 10,
    val speckleWindowSize: Int = 100,
    val speckleRange: Int = 32
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("min_disparity", minDisparity)
        put("num_disparities", numDisparities)
        put("block_size", blockSize)
        put("p1", p1)
        put("p2", p2)
        put("disp12_max_diff", disp12MaxDiff)
        put("uniqueness_ratio", uniquenessRatio)
        put("speckle_window_size", speckleWindowSize)
        put("speckle_range", speckleRange)
    }
}

data class StereoDisparityResult(
    val success: Boolean,
    val statusMessage: String,
    val pngFile: File? = null,
    val jsonFile: File? = null
)

class StereoDisparityEngine {
    fun computeAndExport(
        context: Context,
        leftImageFile: File,
        rightImageFile: File,
        calibration: StereoCalibrationResult,
        params: StereoSgbmParams = StereoSgbmParams()
    ): StereoDisparityResult {
        if (!calibration.success) {
            return StereoDisparityResult(false, "Calibration result is not successful")
        }
        if (!OpenCvInitializer.isInitialized()) {
            return StereoDisparityResult(false, "OpenCV is not initialized")
        }

        return OpenCvInitializer.withLock {
            val k1Values = calibration.k1 ?: return@withLock missingMatrix("K1")
            val d1Values = calibration.d1 ?: return@withLock missingMatrix("D1")
            val k2Values = calibration.k2 ?: return@withLock missingMatrix("K2")
            val d2Values = calibration.d2 ?: return@withLock missingMatrix("D2")
            val rotationValues = calibration.rotation ?: return@withLock missingMatrix("R")
            val translationValues = calibration.translation ?: return@withLock missingMatrix("T")
            val leftGray = Imgcodecs.imread(leftImageFile.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
            val rightGray = Imgcodecs.imread(rightImageFile.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
            if (!OpenCvMatAccess.isAlive(leftGray) || !OpenCvMatAccess.isAlive(rightGray)) {
                leftGray.release()
                rightGray.release()
                return@withLock StereoDisparityResult(false, "Could not read left/right images")
            }
            if (leftGray.size() != rightGray.size()) {
                leftGray.release()
                rightGray.release()
                return@withLock StereoDisparityResult(false, "Left/right image sizes do not match")
            }

            val k1 = k1Values.toMat3x3()
            val d1 = d1Values.toMatDistortion()
            val k2 = k2Values.toMat3x3()
            val d2 = d2Values.toMatDistortion()
            val rotation = rotationValues.toMat3x3()
            val translation = translationValues.toMatVector()

            val imageSize = Size(leftGray.cols().toDouble(), leftGray.rows().toDouble())
            val r1 = Mat()
            val r2 = Mat()
            val p1 = Mat()
            val p2 = Mat()
            val q = Mat()
            val map1Left = Mat()
            val map2Left = Mat()
            val map1Right = Mat()
            val map2Right = Mat()
            val rectLeft = Mat()
            val rectRight = Mat()
            val disparity = Mat()
            var sgbm: StereoSGBM? = null

            try {
                Calib3d.stereoRectify(
                    k1,
                    d1,
                    k2,
                    d2,
                    imageSize,
                    rotation,
                    translation,
                    r1,
                    r2,
                    p1,
                    p2,
                    q,
                    Calib3d.CALIB_ZERO_DISPARITY,
                    -1.0,
                    imageSize
                )

                Calib3d.initUndistortRectifyMap(
                    k1, d1, r1, p1, imageSize, CvType.CV_32FC1, map1Left, map2Left
                )
                Calib3d.initUndistortRectifyMap(
                    k2, d2, r2, p2, imageSize, CvType.CV_32FC1, map1Right, map2Right
                )
                Imgproc.remap(leftGray, rectLeft, map1Left, map2Left, Imgproc.INTER_LINEAR)
                Imgproc.remap(rightGray, rectRight, map1Right, map2Right, Imgproc.INTER_LINEAR)

                sgbm = StereoSGBM.create(
                    params.minDisparity,
                    params.numDisparities,
                    params.blockSize,
                    params.p1,
                    params.p2,
                    params.disp12MaxDiff,
                    0,
                    params.uniquenessRatio,
                    params.speckleWindowSize,
                    params.speckleRange,
                    StereoSGBM.MODE_SGBM
                )
                sgbm.compute(rectLeft, rectRight, disparity)

                val rawDisparities = readRawDisparities(disparity)
                val stats = StereoDisparityAnalysis.analyze(rawDisparities)
                val epochMs = System.currentTimeMillis()
                val directory = context.getExternalFilesDir(null) ?: return@withLock StereoDisparityResult(
                    false,
                    "External files directory unavailable"
                )
                val pngFile = File(directory, "disparity_$epochMs.png")
                val jsonFile = File(directory, "disparity_$epochMs.json")

                writeColormapPng(
                    rawDisparities = rawDisparities,
                    rows = disparity.rows(),
                    cols = disparity.cols(),
                    summary = stats,
                    output = pngFile
                )
                jsonFile.writeText(
                    JSONObject().apply {
                        put("generated_at_utc", Instant.now().toString())
                        put("min_disparity", stats.min)
                        put("max_disparity", stats.max)
                        put("valid_pixel_percent", stats.validPercent)
                        put("sgbm_params", params.toJson())
                        put("baseline_m", calibration.baselineM ?: JSONObject.NULL)
                        put("stereo_rms", calibration.stereoRms ?: JSONObject.NULL)
                        put("Q", q.toJsonMatrix())
                        put(
                            "depth_range_note",
                            "Phone baseline is small; depth is most reliable at 0.3-1.5 m and degrades beyond ~2 m."
                        )
                    }.toString(2)
                )
                StereoDisparityResult(
                    success = true,
                    statusMessage = "Disparity exported",
                    pngFile = pngFile,
                    jsonFile = jsonFile
                )
            } catch (exception: Exception) {
                Log.e(TAG, "Disparity computation failed", exception)
                StereoDisparityResult(
                    success = false,
                    statusMessage = exception.message ?: "Disparity computation failed"
                )
            } finally {
                leftGray.release()
                rightGray.release()
                k1.release()
                d1.release()
                k2.release()
                d2.release()
                rotation.release()
                translation.release()
                r1.release()
                r2.release()
                p1.release()
                p2.release()
                q.release()
                map1Left.release()
                map2Left.release()
                map1Right.release()
                map2Right.release()
                rectLeft.release()
                rectRight.release()
                disparity.release()
                sgbm?.clear()
            }
        }
    }

    private fun missingMatrix(name: String): StereoDisparityResult =
        StereoDisparityResult(false, "Calibration missing $name")

    private fun readRawDisparities(disparity: Mat): ShortArray {
        check(disparity.type() == CvType.CV_16SC1) {
            "Expected CV_16SC1 disparity, got type ${disparity.type()}"
        }
        val values = ShortArray(disparity.rows() * disparity.cols())
        val read = disparity.get(0, 0, values)
        check(read == values.size) {
            "Read $read/${values.size} disparity values"
        }
        return values
    }

    private fun writeColormapPng(
        rawDisparities: ShortArray,
        rows: Int,
        cols: Int,
        summary: StereoDisparitySummary,
        output: File
    ) {
        val low = summary.lowPercentileRaw
        val range = max(summary.highPercentileRaw - low, 1)
        val bitmap = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(cols * rows)
        for (index in rawDisparities.indices) {
            val raw = rawDisparities[index].toInt()
            pixels[index] = if (raw <= 0) {
                Color.BLACK
            } else {
                val normalized = ((raw - low).toFloat() / range).coerceIn(0f, 1f)
                heatmapColor(normalized)
            }
        }
        bitmap.setPixels(pixels, 0, cols, 0, 0, cols, rows)
        try {
            FileOutputStream(output).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "PNG compression failed"
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun heatmapColor(normalized: Float): Int = when {
        normalized < 0.25f -> {
            val t = normalized / 0.25f
            Color.rgb(0, (t * 255).toInt(), 255)
        }
        normalized < 0.5f -> {
            val t = (normalized - 0.25f) / 0.25f
            Color.rgb(0, 255, ((1f - t) * 255).toInt())
        }
        normalized < 0.75f -> {
            val t = (normalized - 0.5f) / 0.25f
            Color.rgb((t * 255).toInt(), 255, 0)
        }
        else -> {
            val t = (normalized - 0.75f) / 0.25f
            Color.rgb(255, ((1f - t) * 255).toInt(), 0)
        }
    }

    private fun Array<DoubleArray>.toMat3x3(): Mat {
        val mat = Mat(3, 3, CvType.CV_64F)
        for (row in 0 until 3) {
            for (column in 0 until 3) {
                mat.put(row, column, this[row][column])
            }
        }
        return mat
    }

    private fun DoubleArray.toMatDistortion(): Mat {
        val mat = Mat(1, 5, CvType.CV_64F)
        for (index in indices) {
            mat.put(0, index, this[index])
        }
        return mat
    }

    private fun DoubleArray.toMatVector(): Mat {
        val mat = Mat(3, 1, CvType.CV_64F)
        for (index in indices) {
            mat.put(index, 0, this[index])
        }
        return mat
    }

    private fun Mat.toJsonMatrix(): JSONArray {
        val rows = rows()
        val cols = cols()
        return JSONArray().apply {
            for (row in 0 until rows) {
                put(JSONArray().apply {
                    for (column in 0 until cols) {
                        put(OpenCvMatAccess.readMatrixValue(this@toJsonMatrix, row, column))
                    }
                })
            }
        }
    }

    companion object {
        private const val TAG = "StereoDisparityEngine"
    }
}
