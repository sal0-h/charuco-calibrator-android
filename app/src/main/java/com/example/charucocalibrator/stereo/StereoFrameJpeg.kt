package com.example.charucocalibrator.stereo

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.File
import java.io.FileOutputStream

/** Shared NV21 -> JPEG writer for saved stereo frames. */
internal object StereoFrameJpeg {
    const val QUALITY = 95

    fun write(file: File, frame: StereoFrameSnapshot) {
        FileOutputStream(file).use { output ->
            val compressed = YuvImage(
                frame.nv21,
                ImageFormat.NV21,
                frame.width,
                frame.height,
                null
            ).compressToJpeg(Rect(0, 0, frame.width, frame.height), QUALITY, output)
            check(compressed) { "JPEG compression failed" }
        }
    }
}
