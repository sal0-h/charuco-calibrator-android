package com.example.charucocalibrator

import android.util.Log
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.objdetect.CharucoBoard

object OpenCvMatAccess {
    fun isAlive(mat: Mat): Boolean =
        mat.getNativeObjAddr() != 0L && !mat.empty()

    fun readBoardCorners(board: CharucoBoard): Array<Point3>? =
        runCatching {
            val corners = board.chessboardCorners
            if (!isAlive(corners)) return null
            corners.toArray().takeIf { it.isNotEmpty() }
        }.getOrElse { error ->
            Log.w(TAG, "Failed to read board chessboard corners", error)
            null
        }

    fun readPoint2fRows(corners: Mat): List<Point>? {
        if (!isAlive(corners)) return null
        val rows = corners.rows()
        if (rows <= 0) return null

        val points = ArrayList<Point>(rows)
        for (row in 0 until rows) {
            val values = readRow(corners, row, minValues = 2) ?: return null
            points.add(Point(values[0], values[1]))
        }
        return points
    }

    fun readIntRows(ids: Mat): List<Int>? {
        if (!isAlive(ids)) return null
        val rows = ids.rows()
        if (rows <= 0) return null

        val values = ArrayList<Int>(rows)
        for (row in 0 until rows) {
            val rowValues = readRow(ids, row, minValues = 1) ?: return null
            values.add(rowValues[0].toInt())
        }
        return values
    }

    fun toObjectPointsMat(points: List<Point3>): Mat? {
        if (points.isEmpty()) return null
        val mat = Mat(points.size, 1, CvType.CV_32FC3)
        for (index in points.indices) {
            val point = points[index]
            mat.put(index, 0, point.x, point.y, point.z)
        }
        return mat
    }

    fun toImagePointsMat(points: List<Point>): Mat? {
        if (points.isEmpty()) return null
        val mat = Mat(points.size, 1, CvType.CV_32FC2)
        for (index in points.indices) {
            val point = points[index]
            mat.put(index, 0, point.x, point.y)
        }
        return mat
    }

    fun readMatrixValue(matrix: Mat, row: Int, column: Int, default: Double = 0.0): Double =
        runCatching {
            matrix.get(row, column)?.firstOrNull() ?: default
        }.getOrDefault(default)

    /**
     * Reads a length-[count] coefficient vector from a Mat that may be stored as either a
     * 1×N row vector or an N×1 column vector, using [get] to fetch a cell (null when out of
     * range). A row Mat is read across columns; otherwise it is read down rows. Missing cells
     * default to 0.0.
     *
     * OpenCV's `calibrateCamera` returns distortion as a 1×5 row vector, so reading it down
     * column 0 silently yields only the first coefficient — this helper reads the correct axis.
     */
    fun readCoefficientVector(
        rows: Int,
        cols: Int,
        count: Int,
        get: (row: Int, col: Int) -> Double?
    ): DoubleArray {
        val readAcrossColumns = rows == 1 && cols != 1
        return DoubleArray(count) { index ->
            val value = if (readAcrossColumns) get(0, index) else get(index, 0)
            value ?: 0.0
        }
    }

    private fun readRow(mat: Mat, row: Int, minValues: Int): DoubleArray? {
        val channels = mat.channels().coerceAtLeast(1)
        val values = when (channels) {
            1 -> mat.get(row, 0)
            2 -> mat.get(row, 0)
            else -> mat.get(row, 0)
        } ?: return null

        if (values.size < minValues) return null
        return values
    }

    private const val TAG = "OpenCvMatAccess"
}
