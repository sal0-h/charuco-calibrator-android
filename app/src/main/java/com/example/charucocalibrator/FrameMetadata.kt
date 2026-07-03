package com.example.charucocalibrator

import android.hardware.camera2.CaptureResult
import org.json.JSONObject

data class FrameMetadata(
    val sensorTimestampNs: Long? = null,
    val exposureTimeNs: Long? = null,
    val isoSensitivity: Int? = null,
    val focalLengthMm: Float? = null,
    val lensFocusDistance: Float? = null,
    val afState: Int? = null,
    val aeState: Int? = null,
    val awbState: Int? = null
) {
    val exposureTimeMs: Double?
        get() = exposureTimeNs?.let { it / 1_000_000.0 }

    fun appendToJson(json: JSONObject) {
        json.put("sensor_exposure_time_ns", exposureTimeNs ?: JSONObject.NULL)
        json.put("iso_sensitivity", isoSensitivity ?: JSONObject.NULL)
        json.put("focal_length_mm", focalLengthMm ?: JSONObject.NULL)
        json.put("lens_focus_distance", lensFocusDistance ?: JSONObject.NULL)
        json.put("af_state", afState ?: JSONObject.NULL)
        json.put("ae_state", aeState ?: JSONObject.NULL)
        json.put("awb_state", awbState ?: JSONObject.NULL)
        json.put("af_state_name", afStateName(afState))
        json.put("ae_state_name", aeStateName(aeState))
        json.put("awb_state_name", awbStateName(awbState))
    }

    companion object {
        fun fromCaptureResult(timestampNs: Long, result: CaptureResult): FrameMetadata =
            FrameMetadata(
                sensorTimestampNs = timestampNs,
                exposureTimeNs = result[CaptureResult.SENSOR_EXPOSURE_TIME],
                isoSensitivity = result[CaptureResult.SENSOR_SENSITIVITY],
                focalLengthMm = result[CaptureResult.LENS_FOCAL_LENGTH],
                lensFocusDistance = result[CaptureResult.LENS_FOCUS_DISTANCE],
                afState = result[CaptureResult.CONTROL_AF_STATE],
                aeState = result[CaptureResult.CONTROL_AE_STATE],
                awbState = result[CaptureResult.CONTROL_AWB_STATE]
            )

        fun fromJson(json: JSONObject): FrameMetadata = FrameMetadata(
            sensorTimestampNs = json.optLong("sensor_timestamp_ns").takeIf { json.has("sensor_timestamp_ns") && !json.isNull("sensor_timestamp_ns") },
            exposureTimeNs = json.optLong("sensor_exposure_time_ns").takeIf { json.has("sensor_exposure_time_ns") && !json.isNull("sensor_exposure_time_ns") },
            isoSensitivity = json.optInt("iso_sensitivity").takeIf { json.has("iso_sensitivity") && !json.isNull("iso_sensitivity") },
            focalLengthMm = json.optDouble("focal_length_mm").toFloat().takeIf { json.has("focal_length_mm") && !json.isNull("focal_length_mm") },
            lensFocusDistance = json.optDouble("lens_focus_distance").toFloat().takeIf { json.has("lens_focus_distance") && !json.isNull("lens_focus_distance") },
            afState = json.optInt("af_state").takeIf { json.has("af_state") && !json.isNull("af_state") },
            aeState = json.optInt("ae_state").takeIf { json.has("ae_state") && !json.isNull("ae_state") },
            awbState = json.optInt("awb_state").takeIf { json.has("awb_state") && !json.isNull("awb_state") }
        )
    }
}

fun afStateName(state: Int?): String = when (state) {
    CaptureResult.CONTROL_AF_STATE_INACTIVE -> "inactive"
    CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "active_scan"
    CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "passive_focused"
    CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "passive_unfocused"
    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "focused_locked"
    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "not_focused_locked"
    CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "passive_scan"
    else -> "unknown"
}

fun aeStateName(state: Int?): String = when (state) {
    CaptureResult.CONTROL_AE_STATE_INACTIVE -> "inactive"
    CaptureResult.CONTROL_AE_STATE_SEARCHING -> "searching"
    CaptureResult.CONTROL_AE_STATE_CONVERGED -> "converged"
    CaptureResult.CONTROL_AE_STATE_LOCKED -> "locked"
    CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "flash_required"
    CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "precapture"
    else -> "unknown"
}

fun awbStateName(state: Int?): String = when (state) {
    CaptureResult.CONTROL_AWB_STATE_INACTIVE -> "inactive"
    CaptureResult.CONTROL_AWB_STATE_SEARCHING -> "searching"
    CaptureResult.CONTROL_AWB_STATE_CONVERGED -> "converged"
    CaptureResult.CONTROL_AWB_STATE_LOCKED -> "locked"
    else -> "unknown"
}
