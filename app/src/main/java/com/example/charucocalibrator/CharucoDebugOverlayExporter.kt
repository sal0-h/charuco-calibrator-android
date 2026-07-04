package com.example.charucocalibrator

import android.content.Context
import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

object CharucoDebugOverlayExporter {
    fun exportOverlays(
        context: Context,
        frames: List<AcceptedFrameRecord>,
        sessionId: String,
        maxFrames: Int = DEBUG_OVERLAY_COUNT
    ): List<File> {
        if (frames.isEmpty()) return emptyList()

        val directory = File(
            checkNotNull(context.getExternalFilesDir(null)) {
                "App-specific external files directory is unavailable"
            },
            DEBUG_OVERLAYS_DIR
        ).apply { mkdirs() }

        val outputs = ArrayList<File>()
        frames.take(maxFrames).forEach { frame ->
            runCatching {
                exportFrameOverlay(frame, sessionId, directory)
            }.onSuccess { file ->
                outputs += file
            }.onFailure { error ->
                Log.w(TAG, "Failed to export debug overlay for ${frame.imageFile.name}", error)
            }
        }
        return outputs
    }

    private fun exportFrameOverlay(
        frame: AcceptedFrameRecord,
        sessionId: String,
        directory: File
    ): File {
        val gray = Imgcodecs.imread(frame.imageFile.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
        check(OpenCvMatAccess.isAlive(gray)) {
            "Could not read grayscale image ${frame.imageFile.name}"
        }

        val bgr = Mat()
        return try {
            Imgproc.cvtColor(gray, bgr, Imgproc.COLOR_GRAY2BGR)
            val imagePoints = OpenCvMatAccess.readPoint2fRows(frame.charucoCorners).orEmpty()
            val ids = OpenCvMatAccess.readIntRows(frame.charucoIds).orEmpty()
            val pairCount = minOf(imagePoints.size, ids.size)
            for (index in 0 until pairCount) {
                val point = imagePoints[index]
                val cornerId = ids[index]
                Imgproc.circle(
                    bgr,
                    point,
                    CIRCLE_RADIUS_PX,
                    Scalar(0.0, 255.0, 0.0),
                    CIRCLE_THICKNESS_PX
                )
                Imgproc.putText(
                    bgr,
                    cornerId.toString(),
                    Point(point.x + TEXT_OFFSET_X, point.y + TEXT_OFFSET_Y),
                    Imgproc.FONT_HERSHEY_SIMPLEX,
                    TEXT_SCALE,
                    Scalar(0.0, 255.0, 255.0),
                    TEXT_THICKNESS_PX
                )
            }

            val output = File(
                directory,
                "debug_${sessionId}_frame${frame.frameIndex}_ids.jpg"
            )
            check(Imgcodecs.imwrite(output.absolutePath, bgr)) {
                "OpenCV failed to write ${output.name}"
            }
            output
        } finally {
            gray.release()
            bgr.release()
        }
    }

    const val DEBUG_OVERLAYS_DIR = "debug_overlays"
    private const val DEBUG_OVERLAY_COUNT = 3
    private const val CIRCLE_RADIUS_PX = 14
    private const val CIRCLE_THICKNESS_PX = 2
    private const val TEXT_SCALE = 1.2
    private const val TEXT_THICKNESS_PX = 2
    private const val TEXT_OFFSET_X = 16.0
    private const val TEXT_OFFSET_Y = -12.0
    private const val TAG = "CharucoDebugOverlay"
}
