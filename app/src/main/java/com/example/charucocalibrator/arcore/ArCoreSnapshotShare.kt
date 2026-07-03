package com.example.charucocalibrator.arcore

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object ArCoreSnapshotShare {
    fun shareExport(context: Context, result: ArCoreSnapshotResult) {
        val appContext = context.applicationContext
        val authority = "${appContext.packageName}.fileprovider"
        val uris = result.filesWritten.mapNotNull { fileName ->
            val file = result.exportDir.resolve(fileName)
            if (!file.isFile) return@mapNotNull null
            FileProvider.getUriForFile(appContext, authority, file)
        }
        if (uris.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/octet-stream"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_SUBJECT, "ARCore snapshot export")
        }
        val chooser = Intent.createChooser(intent, "Share ARCore snapshot").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    }

    fun adbPullHint(exportDir: File): String =
        "adb pull ${exportDir.absolutePath}/ ."
}
