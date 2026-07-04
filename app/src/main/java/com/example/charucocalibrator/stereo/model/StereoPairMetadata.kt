package com.example.charucocalibrator.stereo.model

import com.example.charucocalibrator.Dimensions
import com.example.charucocalibrator.ORIENTATION_NOTE
import org.json.JSONArray
import org.json.JSONObject

data class StereoPairMetadata(
    val logicalCameraId: String,
    val leftPhysicalCameraId: String,
    val rightPhysicalCameraId: String,
    val pairLabel: String,
    val leftTimestampNs: Long,
    val rightTimestampNs: Long,
    val timestampDeltaNs: Long,
    val leftResolution: Dimensions,
    val rightResolution: Dimensions,
    val leftFocalLengthMm: Double?,
    val rightFocalLengthMm: Double?,
    val leftIso: Int?,
    val rightIso: Int?,
    val leftExposureTimeNs: Long?,
    val rightExposureTimeNs: Long?,
    val leftLensFocusDistance: Float?,
    val rightLensFocusDistance: Float?,
    val leftAfState: Int?,
    val rightAfState: Int?,
    val oisDisabled: Boolean,
    val orientationNote: String = ORIENTATION_NOTE,
    val notes: String? = null,
    val afPolicy: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("logical_camera_id", logicalCameraId)
        put("left_physical_camera_id", leftPhysicalCameraId)
        put("right_physical_camera_id", rightPhysicalCameraId)
        put("pair_label", pairLabel)
        put("left_timestamp_ns", leftTimestampNs)
        put("right_timestamp_ns", rightTimestampNs)
        put("timestamp_delta_ns", timestampDeltaNs)
        put("left_resolution", leftResolution.toJsonArray())
        put("right_resolution", rightResolution.toJsonArray())
        put("left_focal_length_mm", leftFocalLengthMm ?: JSONObject.NULL)
        put("right_focal_length_mm", rightFocalLengthMm ?: JSONObject.NULL)
        put("left_iso", leftIso ?: JSONObject.NULL)
        put("right_iso", rightIso ?: JSONObject.NULL)
        put("left_exposure_time_ns", leftExposureTimeNs ?: JSONObject.NULL)
        put("right_exposure_time_ns", rightExposureTimeNs ?: JSONObject.NULL)
        put("left_lens_focus_distance", leftLensFocusDistance?.toDouble() ?: JSONObject.NULL)
        put("right_lens_focus_distance", rightLensFocusDistance?.toDouble() ?: JSONObject.NULL)
        put("left_af_state", leftAfState ?: JSONObject.NULL)
        put("right_af_state", rightAfState ?: JSONObject.NULL)
        put("ois_disabled", oisDisabled)
        put("orientation_note", orientationNote)
        put("notes", notes ?: JSONObject.NULL)
        put("af_policy", afPolicy ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): StereoPairMetadata = StereoPairMetadata(
            logicalCameraId = json.getString("logical_camera_id"),
            leftPhysicalCameraId = json.getString("left_physical_camera_id"),
            rightPhysicalCameraId = json.getString("right_physical_camera_id"),
            pairLabel = json.getString("pair_label"),
            leftTimestampNs = json.getLong("left_timestamp_ns"),
            rightTimestampNs = json.getLong("right_timestamp_ns"),
            timestampDeltaNs = json.getLong("timestamp_delta_ns"),
            leftResolution = json.getJSONArray("left_resolution").toDimensions(),
            rightResolution = json.getJSONArray("right_resolution").toDimensions(),
            leftFocalLengthMm = json.optDouble("left_focal_length_mm").takeIf {
                json.has("left_focal_length_mm") && !json.isNull("left_focal_length_mm")
            },
            rightFocalLengthMm = json.optDouble("right_focal_length_mm").takeIf {
                json.has("right_focal_length_mm") && !json.isNull("right_focal_length_mm")
            },
            leftIso = json.optInt("left_iso").takeIf {
                json.has("left_iso") && !json.isNull("left_iso")
            },
            rightIso = json.optInt("right_iso").takeIf {
                json.has("right_iso") && !json.isNull("right_iso")
            },
            leftExposureTimeNs = json.optLong("left_exposure_time_ns").takeIf {
                json.has("left_exposure_time_ns") && !json.isNull("left_exposure_time_ns")
            },
            rightExposureTimeNs = json.optLong("right_exposure_time_ns").takeIf {
                json.has("right_exposure_time_ns") && !json.isNull("right_exposure_time_ns")
            },
            leftLensFocusDistance = json.optDouble("left_lens_focus_distance").toFloat().takeIf {
                json.has("left_lens_focus_distance") && !json.isNull("left_lens_focus_distance")
            },
            rightLensFocusDistance = json.optDouble("right_lens_focus_distance").toFloat().takeIf {
                json.has("right_lens_focus_distance") && !json.isNull("right_lens_focus_distance")
            },
            leftAfState = json.optInt("left_af_state").takeIf {
                json.has("left_af_state") && !json.isNull("left_af_state")
            },
            rightAfState = json.optInt("right_af_state").takeIf {
                json.has("right_af_state") && !json.isNull("right_af_state")
            },
            oisDisabled = json.optBoolean("ois_disabled", true),
            orientationNote = json.optString(
                "orientation_note",
                ORIENTATION_NOTE
            ),
            notes = json.optString("notes").takeIf { json.has("notes") && !json.isNull("notes") },
            afPolicy = json.optString("af_policy").takeIf {
                json.has("af_policy") && !json.isNull("af_policy")
            }
        )
    }
}

fun Dimensions.toJsonArray(): JSONArray = JSONArray().put(width).put(height)

private fun JSONArray.toDimensions(): Dimensions =
    Dimensions(width = getInt(0), height = getInt(1))
