package com.example.charucocalibrator.arcore

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import com.example.charucocalibrator.arcore.model.DepthOverlayMode
import com.example.charucocalibrator.arcore.model.DepthSourceToggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Captures the current ARCore GL preview and frame state and writes a snapshot export.
 * Extracted from ArCoreExplorerScreen so the composable only owns UI state, not the
 * PixelCopy / GL-thread / IO orchestration.
 */
object ArCoreSnapshotCapture {
    suspend fun captureAndExport(
        context: Context,
        surfaceView: GLSurfaceView,
        sessionController: ArCoreSessionController,
        mode: DepthOverlayMode,
        source: DepthSourceToggle,
        opacity: Float,
        confidenceThreshold: Int,
    ): ArCoreSnapshotResult {
        val previewBitmap = captureGlSurface(surfaceView)
        val snapshotState = suspendCancellableCoroutine { cont ->
            surfaceView.queueEvent {
                cont.resume(sessionController.copyFrameStateForExport())
            }
        }
        val evidence = ArCoreOverlayEvidence(
            previewBitmap = previewBitmap,
            mode = mode,
            source = source,
            opacity = opacity,
            confidenceThreshold = confidenceThreshold,
        )
        return withContext(Dispatchers.IO) {
            ArCoreSnapshotExporter.exportSnapshot(context, snapshotState, evidence)
        }
    }

    private suspend fun captureGlSurface(surfaceView: GLSurfaceView): Bitmap? =
        suspendCancellableCoroutine { cont ->
            val width = surfaceView.width
            val height = surfaceView.height
            if (width <= 0 || height <= 0) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(
                surfaceView,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        cont.resume(bitmap)
                    } else {
                        bitmap.recycle()
                        cont.resume(null)
                    }
                },
                Handler(Looper.getMainLooper()),
            )
            cont.invokeOnCancellation { bitmap.recycle() }
        }
}
