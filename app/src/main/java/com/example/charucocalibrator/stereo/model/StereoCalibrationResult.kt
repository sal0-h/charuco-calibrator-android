package com.example.charucocalibrator.stereo.model

import com.example.charucocalibrator.Dimensions
import org.json.JSONArray
import org.json.JSONObject

data class StereoCalibrationResult(
    val success: Boolean,
    val statusMessage: String,
    val logicalCameraId: String? = null,
    val leftPhysicalCameraId: String? = null,
    val rightPhysicalCameraId: String? = null,
    val pairCount: Int = 0,
    val leftImageSize: Dimensions? = null,
    val rightImageSize: Dimensions? = null,
    val k1: Array<DoubleArray>? = null,
    val d1: DoubleArray? = null,
    val k2: Array<DoubleArray>? = null,
    val d2: DoubleArray? = null,
    val rotation: Array<DoubleArray>? = null,
    val translation: DoubleArray? = null,
    val baselineM: Double? = null,
    val stereoRms: Double? = null,
    val perViewErrors: List<Double>? = null,
    val solverFlags: Int = 0,
    val solverNote: String? = null,
    val medianTimestampDeltaNs: Long? = null,
    val exportFilePath: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("success", success)
        put("status_message", statusMessage)
        put("logical_camera_id", logicalCameraId ?: JSONObject.NULL)
        put("left_physical_camera_id", leftPhysicalCameraId ?: JSONObject.NULL)
        put("right_physical_camera_id", rightPhysicalCameraId ?: JSONObject.NULL)
        put("pair_count", pairCount)
        put(
            "left_image_size",
            leftImageSize?.let { JSONArray().put(it.width).put(it.height) } ?: JSONObject.NULL
        )
        put(
            "right_image_size",
            rightImageSize?.let { JSONArray().put(it.width).put(it.height) } ?: JSONObject.NULL
        )
        put("K1", k1?.toJsonArray() ?: JSONObject.NULL)
        put("D1", d1?.toJsonArray() ?: JSONObject.NULL)
        put("K2", k2?.toJsonArray() ?: JSONObject.NULL)
        put("D2", d2?.toJsonArray() ?: JSONObject.NULL)
        put("R", rotation?.toJsonArray() ?: JSONObject.NULL)
        put("T", translation?.toJsonArray() ?: JSONObject.NULL)
        put("baseline_m", baselineM ?: JSONObject.NULL)
        put("stereo_rms", stereoRms ?: JSONObject.NULL)
        put(
            "per_view_errors",
            perViewErrors?.let { errors ->
                JSONArray().apply { errors.forEach { put(it) } }
            } ?: JSONObject.NULL
        )
        put("solver_flags", solverFlags)
        put("solver_note", solverNote ?: JSONObject.NULL)
        put("median_timestamp_delta_ns", medianTimestampDeltaNs ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): StereoCalibrationResult = StereoCalibrationResult(
            success = json.optBoolean("success", false),
            statusMessage = json.optString("status_message", ""),
            logicalCameraId = json.optString("logical_camera_id").takeIf {
                json.has("logical_camera_id") && !json.isNull("logical_camera_id")
            },
            leftPhysicalCameraId = json.optString("left_physical_camera_id").takeIf {
                json.has("left_physical_camera_id") && !json.isNull("left_physical_camera_id")
            },
            rightPhysicalCameraId = json.optString("right_physical_camera_id").takeIf {
                json.has("right_physical_camera_id") && !json.isNull("right_physical_camera_id")
            },
            pairCount = json.optInt("pair_count"),
            leftImageSize = json.optJSONArray("left_image_size")?.let {
                Dimensions(width = it.getInt(0), height = it.getInt(1))
            },
            rightImageSize = json.optJSONArray("right_image_size")?.let {
                Dimensions(width = it.getInt(0), height = it.getInt(1))
            },
            k1 = json.optJSONArray("K1")?.toMatrix3x3(),
            d1 = json.optJSONArray("D1")?.toDoubleList()?.toDoubleArray(),
            k2 = json.optJSONArray("K2")?.toMatrix3x3(),
            d2 = json.optJSONArray("D2")?.toDoubleList()?.toDoubleArray(),
            rotation = json.optJSONArray("R")?.toMatrix3x3(),
            translation = json.optJSONArray("T")?.toDoubleList()?.toDoubleArray(),
            baselineM = json.optDouble("baseline_m").takeIf {
                json.has("baseline_m") && !json.isNull("baseline_m")
            },
            stereoRms = json.optDouble("stereo_rms").takeIf {
                json.has("stereo_rms") && !json.isNull("stereo_rms")
            },
            perViewErrors = json.optJSONArray("per_view_errors")?.toDoubleList(),
            solverFlags = json.optInt("solver_flags"),
            solverNote = json.optString("solver_note").takeIf {
                json.has("solver_note") && !json.isNull("solver_note")
            },
            medianTimestampDeltaNs = json.optLong("median_timestamp_delta_ns").takeIf {
                json.has("median_timestamp_delta_ns") && !json.isNull("median_timestamp_delta_ns")
            }
        )
    }
}

private fun Array<DoubleArray>.toJsonArray(): JSONArray = JSONArray().also { array ->
    forEach { row -> array.put(JSONArray().apply { row.forEach { put(it) } }) }
}

private fun DoubleArray.toJsonArray(): JSONArray = JSONArray().also { array ->
    forEach { array.put(it) }
}

private fun JSONArray.toMatrix3x3(): Array<DoubleArray> = Array(length()) { row ->
    val rowArray = getJSONArray(row)
    DoubleArray(rowArray.length()) { column -> rowArray.getDouble(column) }
}

private fun JSONArray.toDoubleList(): List<Double> = buildList {
    for (index in 0 until length()) {
        add(getDouble(index))
    }
}
