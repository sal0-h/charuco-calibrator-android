package com.example.charucocalibrator

import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.core.Point

data class PersistedCharucoCorners(
    val ids: IntArray,
    val imagePoints: Array<Point>
)

object CharucoCornerPersistence {
    fun appendToMetadata(metadata: JSONObject, charucoCorners: Mat, charucoIds: Mat) {
        val imagePoints = OpenCvMatAccess.readPoint2fRows(charucoCorners) ?: return
        val ids = OpenCvMatAccess.readIntRows(charucoIds) ?: return
        if (imagePoints.isEmpty() || ids.isEmpty()) return

        metadata.put("charuco_ids", ids.toJsonArray())
        metadata.put(
            "charuco_corners_xy",
            JSONArray().also { array ->
                imagePoints.forEach { point ->
                    array.put(JSONArray().also { xy -> xy.put(point.x); xy.put(point.y) })
                }
            }
        )
    }

    fun readFromMetadata(metadata: JSONObject): PersistedCharucoCorners? {
        val idsArray = metadata.optJSONArray("charuco_ids") ?: return null
        val cornersArray = metadata.optJSONArray("charuco_corners_xy") ?: return null
        if (idsArray.length() == 0 || cornersArray.length() == 0) return null

        val count = minOf(idsArray.length(), cornersArray.length())
        val ids = IntArray(count) { index -> idsArray.getInt(index) }
        val points = Array(count) { index ->
            val xy = cornersArray.getJSONArray(index)
            Point(xy.getDouble(0), xy.getDouble(1))
        }
        return PersistedCharucoCorners(ids = ids, imagePoints = points)
    }

    private fun IntArray.toJsonArray(): JSONArray =
        JSONArray().also { array -> forEach(array::put) }

    private fun List<Int>.toJsonArray(): JSONArray =
        JSONArray().also { array -> forEach(array::put) }
}
