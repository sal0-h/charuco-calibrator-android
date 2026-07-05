package com.example.charucocalibrator.stereo

import com.example.charucocalibrator.Dimensions

data class StereoCalibrationSessionKey(
    val leftPhysicalCameraId: String,
    val rightPhysicalCameraId: String,
    val resolution: Dimensions
) {
    fun matches(
        leftPhysicalCameraId: String,
        rightPhysicalCameraId: String,
        leftResolution: Dimensions?,
        rightResolution: Dimensions?
    ): Boolean =
        this.leftPhysicalCameraId == leftPhysicalCameraId &&
            this.rightPhysicalCameraId == rightPhysicalCameraId &&
            resolution == leftResolution &&
            resolution == rightResolution
}
