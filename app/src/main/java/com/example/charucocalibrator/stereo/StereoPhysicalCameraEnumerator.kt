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
    private const val ULTRAWIDE_MAX_FOCAL_LENGTH_MM = 3.5f
    private const val TELE_MIN_FOCAL_LENGTH_MM = 7.0f
    private val S23_ULTRA_PHYSICAL_ID_HINTS = mapOf(
        "2" to LensType.ULTRAWIDE,
        "5" to LensType.WIDE,
        "6" to LensType.WIDE,
        "7" to LensType.TELE
    )

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
        return "${left.lensType.label}_${left.physicalCameraId}+" +
            "${right.lensType.label}_${right.physicalCameraId}"
    }

    fun prioritizedPairs(cameras: List<StereoPhysicalCameraInfo>): List<Pair<StereoPhysicalCameraInfo, StereoPhysicalCameraInfo>> {
        if (cameras.size < 2) return emptyList()

        val wides = cameras.filter { it.lensType == LensType.WIDE }
        val ultrawide = cameras.firstOrNull { it.lensType == LensType.ULTRAWIDE }
        val teles = cameras.filter { it.lensType == LensType.TELE }

        val ordered = linkedSetOf<Pair<StereoPhysicalCameraInfo, StereoPhysicalCameraInfo>>()
        if (ultrawide != null) {
            wides.forEach { wide -> ordered.add(orderPair(wide, ultrawide)) }
        }
        wides.forEach { wide ->
            teles.forEach { tele -> ordered.add(orderPair(wide, tele)) }
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

    internal fun classifyLensTypes(
        cameras: List<StereoPhysicalCameraInfo>
    ): List<StereoPhysicalCameraInfo> {
        if (cameras.isEmpty()) return emptyList()
        val physicalIds = cameras.map { it.physicalCameraId }.toSet()
        val useS23UltraHints = physicalIds.containsAll(S23_ULTRA_PHYSICAL_ID_HINTS.keys)
        return cameras.map { camera ->
            val hintedType = S23_ULTRA_PHYSICAL_ID_HINTS[camera.physicalCameraId]
                .takeIf { useS23UltraHints }
            camera.copy(lensType = hintedType ?: classifyFocalLength(camera.focalLengthMm))
        }
    }

    private fun classifyFocalLength(focalLengthMm: Float): LensType = when {
        focalLengthMm <= 0f -> LensType.UNKNOWN
        focalLengthMm <= ULTRAWIDE_MAX_FOCAL_LENGTH_MM -> LensType.ULTRAWIDE
        focalLengthMm >= TELE_MIN_FOCAL_LENGTH_MM -> LensType.TELE
        else -> LensType.WIDE
    }

    private fun resolvePhysicalCharacteristics(
        cameraManager: CameraManager,
        logicalCharacteristics: CameraCharacteristics,
        physicalId: String
    ): CameraCharacteristics =
        try {
            cameraManager.getCameraCharacteristics(physicalId)
        } catch (_: Exception) {
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
