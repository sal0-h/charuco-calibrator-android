package com.example.charucocalibrator.stereo.model

import com.example.charucocalibrator.Dimensions
import org.json.JSONArray
import org.json.JSONObject

data class StereoPairProbeResult(
    val leftPhysicalCameraId: String,
    val rightPhysicalCameraId: String,
    val pairLabel: String,
    val success: Boolean,
    val resolution: Dimensions?,
    val fallbackReason: String?,
    val medianTimestampDeltaNs: Long?,
    val halError: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("left_physical_camera_id", leftPhysicalCameraId)
        put("right_physical_camera_id", rightPhysicalCameraId)
        put("pair_label", pairLabel)
        put("success", success)
        put(
            "resolution",
            resolution?.let { JSONArray().put(it.width).put(it.height) } ?: JSONObject.NULL
        )
        put("fallback_reason", fallbackReason ?: JSONObject.NULL)
        put("median_timestamp_delta_ns", medianTimestampDeltaNs ?: JSONObject.NULL)
        put("hal_error", halError ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): StereoPairProbeResult = StereoPairProbeResult(
            leftPhysicalCameraId = json.getString("left_physical_camera_id"),
            rightPhysicalCameraId = json.getString("right_physical_camera_id"),
            pairLabel = json.getString("pair_label"),
            success = json.getBoolean("success"),
            resolution = json.optJSONArray("resolution")?.let { array ->
                Dimensions(width = array.getInt(0), height = array.getInt(1))
            },
            fallbackReason = json.optString("fallback_reason").takeIf {
                json.has("fallback_reason") && !json.isNull("fallback_reason")
            },
            medianTimestampDeltaNs = json.optLong("median_timestamp_delta_ns").takeIf {
                json.has("median_timestamp_delta_ns") && !json.isNull("median_timestamp_delta_ns")
            },
            halError = json.optString("hal_error").takeIf {
                json.has("hal_error") && !json.isNull("hal_error")
            }
        )
    }
}

data class StereoProbeReport(
    val generatedAtUtc: String,
    val logicalCameraId: String,
    val physicalCameras: List<StereoPhysicalCameraInfo>,
    val probedPairs: List<StereoPairProbeResult>,
    val workingPairs: List<StereoPairProbeResult>,
    val failedPairs: List<StereoPairProbeResult>,
    val notes: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("generated_at_utc", generatedAtUtc)
        put("logical_camera_id", logicalCameraId)
        put("physical_cameras", JSONArray().apply {
            physicalCameras.forEach { put(it.toJson()) }
        })
        put("probed_pairs", JSONArray().apply { probedPairs.forEach { put(it.toJson()) } })
        put("working_pairs", JSONArray().apply { workingPairs.forEach { put(it.toJson()) } })
        put("failed_pairs", JSONArray().apply { failedPairs.forEach { put(it.toJson()) } })
        put("notes", notes ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): StereoProbeReport = StereoProbeReport(
            generatedAtUtc = json.getString("generated_at_utc"),
            logicalCameraId = json.getString("logical_camera_id"),
            physicalCameras = json.getJSONArray("physical_cameras").toCameraList(),
            probedPairs = json.getJSONArray("probed_pairs").toProbeResultList(),
            workingPairs = json.getJSONArray("working_pairs").toProbeResultList(),
            failedPairs = json.getJSONArray("failed_pairs").toProbeResultList(),
            notes = json.optString("notes").takeIf { json.has("notes") && !json.isNull("notes") }
        )
    }
}

private fun JSONArray.toCameraList(): List<StereoPhysicalCameraInfo> = buildList {
    for (index in 0 until length()) {
        add(StereoPhysicalCameraInfo.fromJson(getJSONObject(index)))
    }
}

private fun JSONArray.toProbeResultList(): List<StereoPairProbeResult> = buildList {
    for (index in 0 until length()) {
        add(StereoPairProbeResult.fromJson(getJSONObject(index)))
    }
}
