package com.example.charucocalibrator

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class OpenCvMatAccessTest {

    /** Simulates a Mat cell fetch: returns null when (row, col) is out of range. */
    private fun cell(rows: Int, cols: Int, values: DoubleArray): (Int, Int) -> Double? =
        { row, col ->
            if (row in 0 until rows && col in 0 until cols) values[row * cols + col] else null
        }

    @Test
    fun readsAllFiveCoefficientsFromRowVector() {
        // OpenCV calibrateCamera returns distortion as a 1x5 row vector.
        val values = doubleArrayOf(0.11, -0.22, 0.0033, -0.0044, 0.055)
        val coeffs = OpenCvMatAccess.readCoefficientVector(
            rows = 1,
            cols = 5,
            count = 5,
            get = cell(rows = 1, cols = 5, values = values)
        )
        assertArrayEquals(values, coeffs, 1e-12)
    }

    @Test
    fun readsAllFiveCoefficientsFromColumnVector() {
        val values = doubleArrayOf(0.11, -0.22, 0.0033, -0.0044, 0.055)
        val coeffs = OpenCvMatAccess.readCoefficientVector(
            rows = 5,
            cols = 1,
            count = 5,
            get = cell(rows = 5, cols = 1, values = values)
        )
        assertArrayEquals(values, coeffs, 1e-12)
    }

    @Test
    fun missingCellsDefaultToZero() {
        // A 1x2 vector should fill the remaining coefficients with 0.0 rather than throw.
        val coeffs = OpenCvMatAccess.readCoefficientVector(
            rows = 1,
            cols = 2,
            count = 5,
            get = cell(rows = 1, cols = 2, values = doubleArrayOf(0.11, -0.22))
        )
        assertArrayEquals(doubleArrayOf(0.11, -0.22, 0.0, 0.0, 0.0), coeffs, 1e-12)
    }
}
