package com.example.charucocalibrator.stereo

import android.content.Context
import com.example.charucocalibrator.stereo.model.StereoPairProbeResult
import com.example.charucocalibrator.stereo.model.StereoPhysicalCameraInfo
import com.example.charucocalibrator.stereo.model.StereoProbeReport
import java.io.File
import java.time.Instant

object StereoProbeReportExporter {
    private const val REPORT_FILE_NAME = "stereo_probe_report.json"
    private const val JSON_INDENT_SPACES = 2

    fun export(
        context: Context,
        logicalCameraId: String,
        physicalCameras: List<StereoPhysicalCameraInfo>,
        probedPairs: List<StereoPairProbeResult>,
        notes: String? = null
    ): File {
        val workingPairs = probedPairs.filter { it.success }
        val failedPairs = probedPairs.filter { !it.success }
        val report = StereoProbeReport(
            generatedAtUtc = Instant.now().toString(),
            logicalCameraId = logicalCameraId,
            physicalCameras = physicalCameras,
            probedPairs = probedPairs,
            workingPairs = workingPairs,
            failedPairs = failedPairs,
            notes = notes
        )
        val directory = checkNotNull(context.getExternalFilesDir(null)) {
            "App-specific external files directory is unavailable"
        }
        return File(directory, REPORT_FILE_NAME).apply {
            writeText(report.toJson().toString(JSON_INDENT_SPACES))
        }
    }
}
