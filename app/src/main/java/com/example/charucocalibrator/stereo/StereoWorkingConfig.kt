package com.example.charucocalibrator.stereo

import android.content.Context
import com.example.charucocalibrator.Dimensions
import com.example.charucocalibrator.stereo.model.StereoPhysicalCameraInfo

data class StereoWorkingConfig(
    val leftPhysicalCameraId: String,
    val rightPhysicalCameraId: String,
    val resolution: Dimensions
) {
    val pairKey: String
        get() = "$leftPhysicalCameraId:$rightPhysicalCameraId"

    fun isSupportedBy(cameras: List<StereoPhysicalCameraInfo>): Boolean {
        val left = cameras.firstOrNull { it.physicalCameraId == leftPhysicalCameraId }
            ?: return false
        val right = cameras.firstOrNull { it.physicalCameraId == rightPhysicalCameraId }
            ?: return false
        return leftPhysicalCameraId != rightPhysicalCameraId &&
            resolution in left.yuvSizes && resolution in right.yuvSizes
    }
}

class StereoWorkingConfigStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): StereoWorkingConfig? {
        val leftId = preferences.getString(KEY_LEFT_ID, null) ?: return null
        val rightId = preferences.getString(KEY_RIGHT_ID, null) ?: return null
        val width = preferences.getInt(KEY_WIDTH, 0)
        val height = preferences.getInt(KEY_HEIGHT, 0)
        if (width <= 0 || height <= 0 || leftId == rightId) return null
        return StereoWorkingConfig(
            leftPhysicalCameraId = leftId,
            rightPhysicalCameraId = rightId,
            resolution = Dimensions(width, height)
        )
    }

    fun save(config: StereoWorkingConfig) {
        preferences.edit()
            .putString(KEY_LEFT_ID, config.leftPhysicalCameraId)
            .putString(KEY_RIGHT_ID, config.rightPhysicalCameraId)
            .putInt(KEY_WIDTH, config.resolution.width)
            .putInt(KEY_HEIGHT, config.resolution.height)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "stereo_working_config"
        private const val KEY_LEFT_ID = "left_physical_camera_id"
        private const val KEY_RIGHT_ID = "right_physical_camera_id"
        private const val KEY_WIDTH = "resolution_width"
        private const val KEY_HEIGHT = "resolution_height"
    }
}
