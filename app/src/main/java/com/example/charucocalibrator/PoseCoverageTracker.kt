package com.example.charucocalibrator

class PoseCoverageTracker(
    private val gridSize: Int = 3
) {
    private val cellCounts = IntArray(gridSize * gridSize)

    fun shouldAccept(centerXRatio: Double, centerYRatio: Double): Boolean {
        val cell = cellIndex(centerXRatio, centerYRatio)
        val coveredCells = cellCounts.count { it > 0 }
        val countInCell = cellCounts[cell]
        return coveredCells < 6 || countInCell < 2
    }

    fun recordAccept(centerXRatio: Double, centerYRatio: Double) {
        cellCounts[cellIndex(centerXRatio, centerYRatio)] += 1
    }

    fun clear() {
        cellCounts.fill(0)
    }

    private fun cellIndex(centerXRatio: Double, centerYRatio: Double): Int {
        val x = (centerXRatio.coerceIn(0.0, 0.999) * gridSize).toInt().coerceIn(0, gridSize - 1)
        val y = (centerYRatio.coerceIn(0.0, 0.999) * gridSize).toInt().coerceIn(0, gridSize - 1)
        return y * gridSize + x
    }
}
