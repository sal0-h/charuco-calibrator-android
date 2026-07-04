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
import kotlin.math.min

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
            val leftGray = Imgcodecs.imread(leftImageFile.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
            val rightGray = Imgcodecs.imread(rightImageFile.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
            if (!OpenCvMatAccess.isAlive(leftGray) || !OpenCvMatAccess.isAlive(rightGray)) {
                leftGray.release()
                rightGray.release()
                return@withLock StereoDisparityResult(false, "Could not read left/right images")
            }

            val k1 = calibration.k1?.toMat3x3() ?: return@withLock missingMatrix("K1")
            val d1 = calibration.d1?.toMatDistortion() ?: return@withLock missingMatrix("D1")
            val k2 = calibration.k2?.toMat3x3() ?: return@withLock missingMatrix("K2")
            val d2 = calibration.d2?.toMatDistortion() ?: return@withLock missingMatrix("D2")
            val rotation = calibration.rotation?.toMat3x3() ?: return@withLock missingMatrix("R")
            val translation = calibration.translation?.toMatVector() ?: return@withLock missingMatrix("T")

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

                val disparity = Mat()
                val sgbm = StereoSGBM.create(
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

                val stats = computeDisparityStats(disparity)
                val epochMs = System.currentTimeMillis()
                val directory = context.getExternalFilesDir(null) ?: return@withLock StereoDisparityResult(
                    false,
                    "External files directory unavailable"
                )
                val pngFile = File(directory, "disparity_$epochMs.png")
                val jsonFile = File(directory, "disparity_$epochMs.json")

                writeColormapPng(disparity, pngFile)
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

                disparity.release()
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
            }
        }
    }

    private fun missingMatrix(name: String): StereoDisparityResult =
        StereoDisparityResult(false, "Calibration missing $name")

    private data class DisparityStats(
        val min: Double,
        val max: Double,
        val validPercent: Double
    )

    private fun computeDisparityStats(disparity: Mat): DisparityStats {
        val values = mutableListOf<Double>()
        val rows = disparity.rows()
        val cols = disparity.cols()
        for (row in 0 until rows) {
            for (column in 0 until cols) {
                val raw = disparity.get(row, column)?.firstOrNull() ?: continue
                if (raw <= 0.0) continue
                values.add(raw / 16.0)
            }
        }
        if (values.isEmpty()) {
            return DisparityStats(min = 0.0, max = 0.0, validPercent = 0.0)
        }
        val validPercent = values.size * 100.0 / (rows * cols)
        return DisparityStats(
            min = values.minOrNull() ?: 0.0,
            max = values.maxOrNull() ?: 0.0,
            validPercent = validPercent
        )
    }

    private fun writeColormapPng(disparity: Mat, output: File) {
        val rows = disparity.rows()
        val cols = disparity.cols()
        val values = FloatArray(rows * cols)
        for (row in 0 until rows) {
            for (column in 0 until cols) {
                val raw = disparity.get(row, column)?.firstOrNull()?.toFloat() ?: 0f
                values[row * cols + column] = if (raw > 0f) raw / 16f else 0f
            }
        }
        val valid = values.filter { it > 0f }.sorted()
        val low = valid.getOrElse((valid.size * 0.02f).toInt().coerceAtMost(valid.lastIndex)) { 0f }
        val high = valid.getOrElse((valid.size * 0.98f).toInt().coerceAtMost(valid.lastIndex)) { 1f }
        val range = max(high - low, 1e-3f)

        val bitmap = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(cols * rows)
        for (index in values.indices) {
            val value = values[index]
            pixels[index] = if (value <= 0f) {
                Color.BLACK
            } else {
                val normalized = ((value - low) / range).coerceIn(0f, 1f)
                heatmapColor(normalized)
            }
        }
        bitmap.setPixels(pixels, 0, cols, 0, 0, cols, rows)
        FileOutputStream(output).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        bitmap.recycle()
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
