package com.example.charucocalibrator.stereo.model

import com.example.charucocalibrator.Dimensions
import org.json.JSONArray
import org.json.JSONObject

enum class LensType(val label: String) {
    ULTRAWIDE("ultrawide"),
    WIDE("wide"),
    TELE("tele"),
    UNKNOWN("unknown")
}

data class StereoPhysicalCameraInfo(
    val physicalCameraId: String,
    val focalLengthMm: Float,
    val lensType: LensType,
    val yuvSizes: List<Dimensions>,
    val jpegSizes: List<Dimensions>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("physical_camera_id", physicalCameraId)
        put("focal_length_mm", focalLengthMm.toDouble())
        put("lens_type", lensType.label)
        put("yuv_420_888_output_sizes", yuvSizes.toJsonArray())
        put("jpeg_output_sizes", jpegSizes.toJsonArray())
    }

    companion object {
        fun fromJson(json: JSONObject): StereoPhysicalCameraInfo = StereoPhysicalCameraInfo(
            physicalCameraId = json.getString("physical_camera_id"),
            focalLengthMm = json.getDouble("focal_length_mm").toFloat(),
            lensType = LensType.entries.firstOrNull {
                it.label == json.optString("lens_type")
            } ?: LensType.UNKNOWN,
            yuvSizes = json.optJSONArray("yuv_420_888_output_sizes")?.toDimensionsList().orEmpty(),
            jpegSizes = json.optJSONArray("jpeg_output_sizes")?.toDimensionsList().orEmpty()
        )
    }
}

private fun List<Dimensions>.toJsonArray(): JSONArray =
    JSONArray().also { array ->
        forEach { size ->
            array.put(JSONArray().put(size.width).put(size.height))
        }
    }

private fun JSONArray.toDimensionsList(): List<Dimensions> = buildList {
    for (index in 0 until length()) {
        val entry = getJSONArray(index)
        add(Dimensions(width = entry.getInt(0), height = entry.getInt(1)))
    }
}
