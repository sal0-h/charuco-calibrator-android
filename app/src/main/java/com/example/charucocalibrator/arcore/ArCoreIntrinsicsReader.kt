package com.example.charucocalibrator.arcore

import com.example.charucocalibrator.arcore.model.CameraIntrinsicsSnapshot
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Frame

object ArCoreIntrinsicsReader {

    fun readImageIntrinsics(frame: Frame): CameraIntrinsicsSnapshot =
        intrinsicsToSnapshot(frame.camera.imageIntrinsics)

    fun readTextureIntrinsics(frame: Frame): CameraIntrinsicsSnapshot =
        intrinsicsToSnapshot(frame.camera.textureIntrinsics)

    private fun intrinsicsToSnapshot(intrinsics: CameraIntrinsics): CameraIntrinsicsSnapshot {
        val focalLength = intrinsics.focalLength
        val principalPoint = intrinsics.principalPoint
        val dimensions = intrinsics.imageDimensions
        return CameraIntrinsicsSnapshot(
            fx = focalLength[0],
            fy = focalLength[1],
            cx = principalPoint[0],
            cy = principalPoint[1],
            width = dimensions[0],
            height = dimensions[1],
        )
    }
}
