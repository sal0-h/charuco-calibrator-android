package com.example.charucocalibrator.stereo

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Creates a compact, adb-pullable evidence bundle without duplicating captured images. */
object StereoSupportBundleExporter {
    const val OUTPUT_FILE_NAME = "stereo_support_bundle_latest.zip"
    private const val MANIFEST_FILE_NAME = "manifest.json"
    private const val MAX_BOARD_METADATA_FILES = 50

    fun export(context: Context): File {
        val root = checkNotNull(context.getExternalFilesDir(null)) {
            "App-specific external files directory is unavailable"
        }
        val output = File(root, OUTPUT_FILE_NAME)
        val temporary = File(root, "$OUTPUT_FILE_NAME.tmp")
        val files = collectSupportFiles(root)
        val entryNames = files.map { it.relativeTo(root).invariantSeparatorsPath }

        ZipOutputStream(FileOutputStream(temporary)).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_FILE_NAME))
            zip.write(buildManifest(context, entryNames).toString(2).toByteArray())
            zip.closeEntry()

            files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }

        if (output.exists()) check(output.delete()) { "Could not replace $OUTPUT_FILE_NAME" }
        check(temporary.renameTo(output)) { "Could not finalize $OUTPUT_FILE_NAME" }
        return output
    }

    internal fun collectSupportFiles(root: File): List<File> {
        val result = linkedSetOf<File>()

        listOf(
            "camera_report.json",
            "stereo_probe_report.json",
            "stereo_calibration.json"
        ).map(root::resolve).filter(File::isFile).forEach(result::add)

        root.listFiles()
            ?.filter { it.isFile && it.name.startsWith("disparity_") && it.extension == "json" }
            ?.maxByOrNull(File::lastModified)
            ?.let(result::add)

        File(root, "stereo_pairs").listFiles()
            ?.filter(File::isDirectory)
            ?.maxByOrNull(File::lastModified)
            ?.resolve("metadata.json")
            ?.takeIf(File::isFile)
            ?.let(result::add)

        File(root, "stereo_calibration_pairs").listFiles()
            ?.filter(File::isDirectory)
            ?.map { it.resolve("corners.json") }
            ?.filter(File::isFile)
            ?.sortedByDescending(File::lastModified)
            ?.take(MAX_BOARD_METADATA_FILES)
            ?.forEach(result::add)

        File(root, StereoDiagnosticsLogger.DIAGNOSTICS_DIRECTORY)
            .resolve(StereoDiagnosticsLogger.LATEST_FILE_NAME)
            .takeIf(File::isFile)
            ?.let(result::add)

        return result.sortedBy { it.relativeTo(root).invariantSeparatorsPath }
    }

    private fun buildManifest(context: Context, entries: List<String>): JSONObject =
        JSONObject().apply {
            put("generated_at_utc", Instant.now().toString())
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("android_sdk", Build.VERSION.SDK_INT)
            put("package_name", context.packageName)
            put("app_version", runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "unknown")
            put("images_included", false)
            put("entries", JSONArray(entries))
        }
}
