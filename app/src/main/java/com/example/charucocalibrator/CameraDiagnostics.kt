package com.example.charucocalibrator

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.Locale

data class CameraReport(
    val generatedAtUtc: String,
    val cameras: List<CameraDiagnostic>
) {
    fun toPrettyJson(): String = JSONObject().apply {
        put("generated_at_utc", generatedAtUtc)
        put("camera_count", cameras.size)
        put("cameras", cameras.toJsonArray { it.toJson() })
    }.toString(JSON_INDENT_SPACES)

    fun toDisplayText(): String = buildString {
        appendLine("Generated: $generatedAtUtc")
        appendLine("Camera IDs: ${cameras.size}")

        cameras.forEachIndexed { index, camera ->
            if (index > 0) appendLine()
            appendLine("Camera ${camera.cameraId}")
            appendLine("  Lens facing: ${camera.lensFacing.displayName()}")
            appendLine("  Focal lengths: ${camera.focalLengthsMm.formatFocalLengths()}")
            appendLine(
                "  Sensor physical size: " +
                    (camera.sensorPhysicalSizeMm?.displayMillimeters() ?: "unavailable")
            )
            appendLine("  Active array: ${camera.activeArray?.display() ?: "unavailable"}")
            appendLine("  Pixel array: ${camera.pixelArray?.display() ?: "unavailable"}")
            appendLine("  Hardware level: ${camera.hardwareLevel.displayName()}")
            appendLine(
                "  Capabilities: " +
                    camera.capabilities.joinToStringOrNone { it.displayName() }
            )
            appendLine(
                "  Physical camera IDs: " +
                    camera.physicalCameraIds.joinToStringOrNone { it }
            )
            appendLine(
                "  YUV_420_888 sizes (${camera.yuv420888OutputSizes.size}): " +
                    camera.yuv420888OutputSizes.joinToStringOrNone { it.display() }
            )
            appendLine(
                "  JPEG sizes (${camera.jpegOutputSizes.size}): " +
                    camera.jpegOutputSizes.joinToStringOrNone { it.display() }
            )
        }
    }.trimEnd()
}

data class CameraDiagnostic(
    val cameraId: String,
    val lensFacing: NamedIntValue,
    val focalLengthsMm: List<Float>,
    val sensorPhysicalSizeMm: PhysicalSize?,
    val activeArray: ArrayBounds?,
    val pixelArray: Dimensions?,
    val hardwareLevel: NamedIntValue,
    val capabilities: List<NamedIntValue>,
    val physicalCameraIds: List<String>,
    val yuv420888OutputSizes: List<Dimensions>,
    val jpegOutputSizes: List<Dimensions>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("camera_id", cameraId)
        put("lens_facing", lensFacing.toJson())
        put("available_focal_lengths_mm", focalLengthsMm.toNumberJsonArray())
        put("sensor_physical_size_mm", sensorPhysicalSizeMm?.toJson() ?: JSONObject.NULL)
        put("active_array_size", activeArray?.toJson() ?: JSONObject.NULL)
        put("pixel_array_size", pixelArray?.toJson() ?: JSONObject.NULL)
        put("hardware_level", hardwareLevel.toJson())
        put("capabilities", capabilities.toJsonArray { it.toJson() })
        put("physical_camera_ids", physicalCameraIds.toJsonArray())
        put("yuv_420_888_output_sizes", yuv420888OutputSizes.toJsonArray { it.toJson() })
        put("jpeg_output_sizes", jpegOutputSizes.toJsonArray { it.toJson() })
    }
}

data class NamedIntValue(
    val name: String,
    val value: Int?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("value", value ?: JSONObject.NULL)
    }

    fun displayName(): String = value?.let { "$name ($it)" } ?: name
}

data class PhysicalSize(
    val widthMm: Float,
    val heightMm: Float
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("width_mm", widthMm)
        put("height_mm", heightMm)
    }

    fun displayMillimeters(): String =
        "${widthMm.formatDecimal()} x ${heightMm.formatDecimal()} mm"
}

data class Dimensions(
    val width: Int,
    val height: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("width", width)
        put("height", height)
    }

    fun display(): String = "${width}x$height"
}

data class ArrayBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("left", left)
        put("top", top)
        put("right", right)
        put("bottom", bottom)
        put("width", right - left)
        put("height", bottom - top)
    }

    fun display(): String =
        "[$left,$top]-[$right,$bottom] (${right - left}x${bottom - top})"
}

object CameraDiagnostics {
    fun collect(context: Context): CameraReport {
        val cameraManager = context.getSystemService(CameraManager::class.java)
        val cameras = cameraManager.cameraIdList
            .sorted()
            .map { cameraId -> collectCamera(cameraManager, cameraId) }

        return CameraReport(
            generatedAtUtc = Instant.now().toString(),
            cameras = cameras
        )
    }

    fun export(context: Context, report: CameraReport): File {
        val externalFilesDirectory = checkNotNull(context.getExternalFilesDir(null)) {
            "App-specific external files directory is unavailable"
        }
        return File(externalFilesDirectory, REPORT_FILE_NAME).apply {
            writeText(report.toPrettyJson())
        }
    }

    private fun collectCamera(
        cameraManager: CameraManager,
        cameraId: String
    ): CameraDiagnostic {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val streamConfigurationMap =
            characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        val physicalSize = characteristics[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]
        val activeArray = characteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
        val pixelArray = characteristics[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]

        return CameraDiagnostic(
            cameraId = cameraId,
            lensFacing = lensFacing(characteristics[CameraCharacteristics.LENS_FACING]),
            focalLengthsMm = characteristics[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]
                ?.toList()
                .orEmpty(),
            sensorPhysicalSizeMm = physicalSize?.let {
                PhysicalSize(widthMm = it.width, heightMm = it.height)
            },
            activeArray = activeArray?.let {
                ArrayBounds(left = it.left, top = it.top, right = it.right, bottom = it.bottom)
            },
            pixelArray = pixelArray?.let { Dimensions(width = it.width, height = it.height) },
            hardwareLevel = hardwareLevel(
                characteristics[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]
            ),
            capabilities = characteristics[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
                ?.map(::capability)
                .orEmpty(),
            physicalCameraIds = characteristics.physicalCameraIds.sorted(),
            yuv420888OutputSizes = streamConfigurationMap
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                .toDimensions(),
            jpegOutputSizes = streamConfigurationMap
                ?.getOutputSizes(ImageFormat.JPEG)
                .toDimensions()
        )
    }

    private fun lensFacing(value: Int?): NamedIntValue = NamedIntValue(
        name = when (value) {
            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            CameraCharacteristics.LENS_FACING_BACK -> "BACK"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
            null -> "UNAVAILABLE"
            else -> "UNKNOWN"
        },
        value = value
    )

    private fun hardwareLevel(value: Int?): NamedIntValue = NamedIntValue(
        name = when (value) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            null -> "UNAVAILABLE"
            else -> "UNKNOWN"
        },
        value = value
    )

    private fun capability(value: Int): NamedIntValue = NamedIntValue(
        name = CAPABILITY_NAMES[value] ?: "UNKNOWN",
        value = value
    )
}

private fun Array<android.util.Size>?.toDimensions(): List<Dimensions> =
    this?.map { Dimensions(width = it.width, height = it.height) }
        ?.sortedWith(
            compareByDescending<Dimensions> { it.width.toLong() * it.height }
                .thenByDescending { it.width }
                .thenByDescending { it.height }
        )
        .orEmpty()

private fun List<Float>.toNumberJsonArray(): JSONArray = JSONArray().also { array ->
    forEach(array::put)
}

private fun List<String>.toJsonArray(): JSONArray = JSONArray().also { array ->
    forEach(array::put)
}

private inline fun <T> List<T>.toJsonArray(transform: (T) -> Any): JSONArray =
    JSONArray().also { array -> forEach { array.put(transform(it)) } }

private fun <T> List<T>.joinToStringOrNone(transform: (T) -> String): String =
    if (isEmpty()) "none" else joinToString(transform = transform)

private fun List<Float>.formatFocalLengths(): String =
    joinToStringOrNone { "${it.formatDecimal()} mm" }

private fun Float.formatDecimal(): String = String.format(Locale.US, "%.3f", this)

private const val REPORT_FILE_NAME = "camera_report.json"
private const val JSON_INDENT_SPACES = 2

private val CAPABILITY_NAMES = mapOf(
    0 to "BACKWARD_COMPATIBLE",
    1 to "MANUAL_SENSOR",
    2 to "MANUAL_POST_PROCESSING",
    3 to "RAW",
    4 to "PRIVATE_REPROCESSING",
    5 to "READ_SENSOR_SETTINGS",
    6 to "BURST_CAPTURE",
    7 to "YUV_REPROCESSING",
    8 to "DEPTH_OUTPUT",
    9 to "CONSTRAINED_HIGH_SPEED_VIDEO",
    10 to "MOTION_TRACKING",
    11 to "LOGICAL_MULTI_CAMERA",
    12 to "MONOCHROME",
    13 to "SECURE_IMAGE_DATA",
    14 to "SYSTEM_CAMERA",
    15 to "OFFLINE_PROCESSING",
    16 to "ULTRA_HIGH_RESOLUTION_SENSOR",
    17 to "REMOSAIC_REPROCESSING",
    18 to "DYNAMIC_RANGE_TEN_BIT",
    19 to "STREAM_USE_CASE",
    20 to "COLOR_SPACE_PROFILES"
)
