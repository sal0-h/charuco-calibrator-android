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
