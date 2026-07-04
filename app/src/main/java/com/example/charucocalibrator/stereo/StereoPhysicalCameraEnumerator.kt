package com.example.charucocalibrator.stereo

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.example.charucocalibrator.DEFAULT_CAMERA_ID
import com.example.charucocalibrator.Dimensions
import com.example.charucocalibrator.stereo.model.LensType
import com.example.charucocalibrator.stereo.model.StereoPhysicalCameraInfo

object StereoPhysicalCameraEnumerator {
    private const val LOGICAL_MULTI_CAMERA = 11

    fun enumerate(context: Context, logicalCameraId: String = DEFAULT_CAMERA_ID): EnumerationResult {
        val cameraManager = context.getSystemService(CameraManager::class.java)
            ?: return EnumerationResult(
                logicalCameraId = logicalCameraId,
                cameras = emptyList(),
                error = "CameraManager unavailable"
            )

        if (logicalCameraId !in cameraManager.cameraIdList) {
            return EnumerationResult(
                logicalCameraId = logicalCameraId,
                cameras = emptyList(),
                error = "Logical camera $logicalCameraId is not exposed by Camera2"
            )
        }

        val characteristics = cameraManager.getCameraCharacteristics(logicalCameraId)
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet()
            .orEmpty()
        if (LOGICAL_MULTI_CAMERA !in capabilities) {
            return EnumerationResult(
                logicalCameraId = logicalCameraId,
                cameras = emptyList(),
                error = "Camera $logicalCameraId does not expose LOGICAL_MULTI_CAMERA"
            )
        }

        val physicalIds = characteristics.physicalCameraIds.sorted()
        if (physicalIds.size < 2) {
            return EnumerationResult(
                logicalCameraId = logicalCameraId,
                cameras = emptyList(),
                error = "Logical camera $logicalCameraId exposes fewer than two physical cameras"
            )
        }

        val cameras = physicalIds.map { physicalId ->
            val physicalCharacteristics = resolvePhysicalCharacteristics(
                cameraManager = cameraManager,
                logicalCharacteristics = characteristics,
                physicalId = physicalId
            )
            val focalLengths = physicalCharacteristics
                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.toList()
                .orEmpty()
            val focalLength = focalLengths.minOrNull() ?: 0f
            val streamMap = physicalCharacteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
            StereoPhysicalCameraInfo(
                physicalCameraId = physicalId,
                focalLengthMm = focalLength,
                lensType = LensType.UNKNOWN,
                yuvSizes = streamMap?.getOutputSizes(ImageFormat.YUV_420_888)
                    ?.toDimensions()
                    .orEmpty(),
                jpegSizes = streamMap?.getOutputSizes(ImageFormat.JPEG)
                    ?.toDimensions()
                    .orEmpty()
            )
        }

        return EnumerationResult(
            logicalCameraId = logicalCameraId,
            cameras = classifyLensTypes(cameras),
            error = null
        )
    }

    fun pairLabel(left: StereoPhysicalCameraInfo, right: StereoPhysicalCameraInfo): String {
        val leftType = left.lensType
        val rightType = right.lensType
        if (leftType != LensType.UNKNOWN && rightType != LensType.UNKNOWN) {
            val types = listOf(leftType, rightType).sortedBy { it.ordinal }
            return "${types[0].label}_${types[1].label}"
        }
        return "physical_${left.physicalCameraId}_${right.physicalCameraId}"
    }

    fun prioritizedPairs(cameras: List<StereoPhysicalCameraInfo>): List<Pair<StereoPhysicalCameraInfo, StereoPhysicalCameraInfo>> {
        if (cameras.size < 2) return emptyList()

        val wide = cameras.firstOrNull { it.lensType == LensType.WIDE }
        val ultrawide = cameras.firstOrNull { it.lensType == LensType.ULTRAWIDE }
        val tele = cameras.firstOrNull { it.lensType == LensType.TELE }

        val ordered = linkedSetOf<Pair<StereoPhysicalCameraInfo, StereoPhysicalCameraInfo>>()
        if (wide != null && ultrawide != null) {
            ordered.add(orderPair(wide, ultrawide))
        }
        if (wide != null && tele != null) {
            ordered.add(orderPair(wide, tele))
        }

        for (leftIndex in cameras.indices) {
            for (rightIndex in leftIndex + 1 until cameras.size) {
                ordered.add(orderPair(cameras[leftIndex], cameras[rightIndex]))
            }
        }
        return ordered.toList()
    }

    private fun orderPair(
        first: StereoPhysicalCameraInfo,
        second: StereoPhysicalCameraInfo
    ): Pair<StereoPhysicalCameraInfo, StereoPhysicalCameraInfo> =
        if (first.physicalCameraId <= second.physicalCameraId) {
            first to second
        } else {
            second to first
        }

    private fun classifyLensTypes(
        cameras: List<StereoPhysicalCameraInfo>
    ): List<StereoPhysicalCameraInfo> {
        if (cameras.isEmpty()) return emptyList()
        val sorted = cameras.sortedBy { it.focalLengthMm }
        return when (sorted.size) {
            1 -> listOf(sorted.first().copy(lensType = LensType.WIDE))
            2 -> listOf(
                sorted[0].copy(lensType = LensType.ULTRAWIDE),
                sorted[1].copy(lensType = LensType.WIDE)
            )
            else -> {
                val ultrawide = sorted.first().copy(lensType = LensType.ULTRAWIDE)
                val tele = sorted.last().copy(lensType = LensType.TELE)
                val middle = sorted.drop(1).dropLast(1).map { camera ->
                    camera.copy(lensType = LensType.WIDE)
                }
                listOf(ultrawide) + middle + listOf(tele)
            }
        }
    }

    private fun resolvePhysicalCharacteristics(
        cameraManager: CameraManager,
        logicalCharacteristics: CameraCharacteristics,
        physicalId: String
    ): CameraCharacteristics =
        if (physicalId in cameraManager.cameraIdList) {
            cameraManager.getCameraCharacteristics(physicalId)
        } else {
            logicalCharacteristics
        }

    private fun Array<android.util.Size>.toDimensions(): List<Dimensions> =
        map { Dimensions(width = it.width, height = it.height) }
            .sortedByDescending { it.width.toLong() * it.height }
}

data class EnumerationResult(
    val logicalCameraId: String,
    val cameras: List<StereoPhysicalCameraInfo>,
    val error: String?
)
