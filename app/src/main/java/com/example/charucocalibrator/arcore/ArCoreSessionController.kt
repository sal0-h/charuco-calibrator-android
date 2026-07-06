package com.example.charucocalibrator.arcore

import android.graphics.Bitmap
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import android.view.Display
import android.view.Surface
import com.example.charucocalibrator.arcore.model.ArCoreFrameState
import com.example.charucocalibrator.arcore.model.DepthImageData
import com.example.charucocalibrator.arcore.model.DepthOverlayMode
import com.example.charucocalibrator.arcore.model.DepthSourceToggle
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArCoreSessionController(
    private val context: Context,
    private val onFrameState: (ArCoreFrameState) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var session: Session? = null
    private var cameraTextureId: Int = 0
    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0
    private var displayRotation: Int = Surface.ROTATION_0
    private var depthModeLabel: String = "DISABLED"
    @Volatile
    private var overlaySettings = ArCoreOverlaySettings()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val latestFrameState = AtomicReference(ArCoreFrameState())

    fun resume(display: Display) {
        displayRotation = display.rotation
        val currentSession = session ?: createSession().also { session = it }
        try {
            currentSession.resume()
            updateLatestFrameState(latestFrameState.get().copy(sessionRunning = true))
        } catch (e: CameraNotAvailableException) {
            onError("Camera not available: ${e.message}")
        } catch (e: Exception) {
            onError("Failed to resume ARCore session: ${e.message}")
        }
    }

    fun pause() {
        session?.pause()
        updateLatestFrameState(latestFrameState.get().copy(sessionRunning = false))
    }

    fun close() {
        session?.close()
        session = null
        updateLatestFrameState(ArCoreFrameState())
    }

    fun onSurfaceCreated(textureId: Int) {
        cameraTextureId = textureId
        session?.setCameraTextureName(textureId)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    fun setDisplayRotation(rotation: Int) {
        displayRotation = rotation
    }

    fun setOverlaySettings(
        overlayMode: DepthOverlayMode,
        depthSource: DepthSourceToggle,
        opacity: Float = DEFAULT_OVERLAY_OPACITY,
        confidenceThreshold: Int = DEFAULT_CONFIDENCE_THRESHOLD,
    ) {
        overlaySettings = ArCoreOverlaySettings(
            mode = overlayMode,
            source = depthSource,
            opacity = opacity.coerceIn(0f, 1f),
            confidenceThreshold = confidenceThreshold.coerceIn(0, 255),
        )
        val current = latestFrameState.get()
        latestFrameState.set(
            current.copy(
                overlayMode = overlayMode.name,
                overlaySource = depthSource.name,
                overlayOpacity = opacity.coerceIn(0f, 1f),
                overlayConfidenceThreshold = confidenceThreshold.coerceIn(0, 255),
            ),
        )
    }

    fun onDrawFrame(renderer: ArCorePreviewRenderer) {
        val currentSession = session ?: return
        if (cameraTextureId != 0) {
            currentSession.setCameraTextureName(cameraTextureId)
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        try {
            currentSession.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
            val frame = currentSession.update()
            if (frame.timestamp == 0L) {
                return
            }
            val frameState = buildFrameState(frame)
            updateLatestFrameState(frameState)
            renderer.drawCameraBackground(frame)
            renderer.drawDepthOverlay(frame, frameState, overlaySettings)
        } catch (e: Exception) {
            Log.w(TAG, "Frame update failed", e)
        }
    }

    fun currentFrameState(): ArCoreFrameState = latestFrameState.get()

    fun copyFrameStateForExport(): ArCoreFrameState = latestFrameState.get().deepCopyForExport()

    private fun createSession(): Session {
        val newSession = Session(context)
        val config = Config(newSession)
        val depthProbe = ArCoreCapabilityChecker.probeDepthModes(newSession)
        config.depthMode = depthProbe.selectedMode
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        depthModeLabel = depthProbe.selectedModeLabel
        newSession.configure(config)
        return newSession
    }

    private fun buildFrameState(frame: com.google.ar.core.Frame): ArCoreFrameState {
        val camera = frame.camera
        return ArCoreFrameState(
            timestampNs = frame.timestamp,
            androidCameraTimestampNs = frame.androidCameraTimestamp,
            trackingState = camera.trackingState.name,
            trackingFailureReason = camera.trackingFailureReason.toExportLabel(),
            imageIntrinsics = ArCoreIntrinsicsReader.readImageIntrinsics(frame),
            textureIntrinsics = ArCoreIntrinsicsReader.readTextureIntrinsics(frame),
            rawDepth = ArCoreDepthReader.readRawDepth(frame),
            smoothedDepth = ArCoreDepthReader.readSmoothedDepth(frame),
            confidence = ArCoreDepthReader.readConfidence(frame),
            depthModeLabel = depthModeLabel,
            sessionRunning = true,
            displayRotation = displayRotation,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            overlayMode = overlaySettings.mode.name,
            overlaySource = overlaySettings.source.name,
            overlayOpacity = overlaySettings.opacity,
            overlayConfidenceThreshold = overlaySettings.confidenceThreshold,
            overlayNdcToDepthTextureUv = latestFrameState.get().overlayNdcToDepthTextureUv,
        )
    }

    fun updateOverlayTransform(settings: ArCoreOverlaySettings, ndcToDepthTextureUv: FloatArray) {
        val current = latestFrameState.get()
        latestFrameState.set(
            current.copy(
                overlayMode = settings.mode.name,
                overlaySource = settings.source.name,
                overlayOpacity = settings.opacity,
                overlayConfidenceThreshold = settings.confidenceThreshold,
                overlayNdcToDepthTextureUv = ndcToDepthTextureUv.toList(),
            ),
        )
    }

    private fun updateLatestFrameState(state: ArCoreFrameState) {
        latestFrameState.set(state)
        mainHandler.post { onFrameState(state) }
    }

    private fun TrackingFailureReason.toExportLabel(): String =
        if (this == TrackingFailureReason.NONE) "NONE" else name

    companion object {
        private const val TAG = "ArCoreSessionController"
        const val DEFAULT_OVERLAY_OPACITY = 0.45f
        const val DEFAULT_CONFIDENCE_THRESHOLD = 200
    }
}

data class ArCoreOverlaySettings(
    val mode: DepthOverlayMode = DepthOverlayMode.Off,
    val source: DepthSourceToggle = DepthSourceToggle.Smoothed,
    val opacity: Float = ArCoreSessionController.DEFAULT_OVERLAY_OPACITY,
    val confidenceThreshold: Int = ArCoreSessionController.DEFAULT_CONFIDENCE_THRESHOLD,
)

class ArCorePreviewRenderer(
    private val sessionController: ArCoreSessionController,
) : GLSurfaceView.Renderer {
    private var cameraProgram = 0
    private var cameraPositionHandle = 0
    private var cameraTexCoordHandle = 0
    private var cameraTextureHandle = 0
    private var overlayProgram = 0
    private var overlayPositionHandle = 0
    private var overlayTexCoordHandle = 0
    private var overlayTextureHandle = 0
    private var overlayAlphaHandle = 0
    private var cameraTextureId = 0
    private var overlayTextureId = 0
    private var overlayUploadTimestampNs = 0L
    private var overlayUploadMode: DepthOverlayMode = DepthOverlayMode.Off
    private var overlayUploadSource: DepthSourceToggle = DepthSourceToggle.Smoothed
    private var overlayUploadWidth = 0
    private var overlayUploadHeight = 0
    private var lastOverlayUploadMs = 0L
    private val transformedCameraTexCoordsBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(QUAD_VIEW_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD_VIEW_COORDS)
                position(0)
            }
    private val transformedDepthTexCoordsBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(QUAD_VIEW_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD_VIEW_COORDS)
                position(0)
            }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1f)
        cameraProgram = createProgram(VERTEX_SHADER, CAMERA_FRAGMENT_SHADER)
        cameraPositionHandle = GLES20.glGetAttribLocation(cameraProgram, "a_Position")
        cameraTexCoordHandle = GLES20.glGetAttribLocation(cameraProgram, "a_TexCoord")
        cameraTextureHandle = GLES20.glGetUniformLocation(cameraProgram, "sTexture")
        overlayProgram = createProgram(VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER)
        overlayPositionHandle = GLES20.glGetAttribLocation(overlayProgram, "a_Position")
        overlayTexCoordHandle = GLES20.glGetAttribLocation(overlayProgram, "a_TexCoord")
        overlayTextureHandle = GLES20.glGetUniformLocation(overlayProgram, "sTexture")
        overlayAlphaHandle = GLES20.glGetUniformLocation(overlayProgram, "u_Alpha")
        val textures = IntArray(2)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glGenTextures(1, textures, 1)
        overlayTextureId = textures[1]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        overlayUploadTimestampNs = 0L
        overlayUploadMode = DepthOverlayMode.Off
        overlayUploadSource = DepthSourceToggle.Smoothed
        overlayUploadWidth = 0
        overlayUploadHeight = 0
        lastOverlayUploadMs = 0L
        sessionController.onSurfaceCreated(cameraTextureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        sessionController.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        sessionController.onDrawFrame(this)
    }

    fun drawCameraBackground(frame: com.google.ar.core.Frame) {
        QUAD_VIEW_COORDS_BUFFER.position(0)
        transformedCameraTexCoordsBuffer.position(0)
        frame.transformCoordinates2d(
            Coordinates2d.VIEW_NORMALIZED,
            QUAD_VIEW_COORDS_BUFFER,
            Coordinates2d.TEXTURE_NORMALIZED,
            transformedCameraTexCoordsBuffer,
        )
        transformedCameraTexCoordsBuffer.position(0)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(cameraProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(cameraTextureHandle, 0)
        GLES20.glEnableVertexAttribArray(cameraPositionHandle)
        GLES20.glVertexAttribPointer(
            cameraPositionHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            QUAD_VERTICES_BUFFER,
        )
        GLES20.glEnableVertexAttribArray(cameraTexCoordHandle)
        GLES20.glVertexAttribPointer(
            cameraTexCoordHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            transformedCameraTexCoordsBuffer,
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(cameraPositionHandle)
        GLES20.glDisableVertexAttribArray(cameraTexCoordHandle)
    }

    fun drawDepthOverlay(
        frame: com.google.ar.core.Frame,
        frameState: ArCoreFrameState,
        settings: ArCoreOverlaySettings,
    ) {
        if (settings.mode == DepthOverlayMode.Off || overlayTextureId == 0) return
        val bitmap = overlayBitmap(frameState, settings) ?: return
        uploadOverlayTextureIfNeeded(frameState.timestampNs, settings, bitmap)
        bitmap.recycle()
        if (overlayUploadTimestampNs == 0L) return

        QUAD_VERTICES_BUFFER.position(0)
        transformedDepthTexCoordsBuffer.position(0)
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            QUAD_VERTICES_BUFFER,
            Coordinates2d.TEXTURE_NORMALIZED,
            transformedDepthTexCoordsBuffer,
        )
        val transformCoords = FloatArray(QUAD_VERTICES.size)
        transformedDepthTexCoordsBuffer.position(0)
        transformedDepthTexCoordsBuffer.get(transformCoords)
        sessionController.updateOverlayTransform(settings, transformCoords)
        QUAD_VERTICES_BUFFER.position(0)
        transformedDepthTexCoordsBuffer.position(0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(overlayProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glUniform1i(overlayTextureHandle, 0)
        GLES20.glUniform1f(overlayAlphaHandle, settings.opacity)
        GLES20.glEnableVertexAttribArray(overlayPositionHandle)
        GLES20.glVertexAttribPointer(
            overlayPositionHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            QUAD_VERTICES_BUFFER,
        )
        GLES20.glEnableVertexAttribArray(overlayTexCoordHandle)
        GLES20.glVertexAttribPointer(
            overlayTexCoordHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            transformedDepthTexCoordsBuffer,
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(overlayPositionHandle)
        GLES20.glDisableVertexAttribArray(overlayTexCoordHandle)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun uploadOverlayTextureIfNeeded(
        timestampNs: Long,
        settings: ArCoreOverlaySettings,
        bitmap: Bitmap,
    ) {
        val nowMs = System.currentTimeMillis()
        val sameFrame = overlayUploadTimestampNs == timestampNs &&
            overlayUploadMode == settings.mode &&
            overlayUploadSource == settings.source
        val throttled = nowMs - lastOverlayUploadMs < OVERLAY_UPLOAD_INTERVAL_MS &&
            overlayUploadMode == settings.mode &&
            overlayUploadSource == settings.source &&
            overlayUploadWidth == bitmap.width &&
            overlayUploadHeight == bitmap.height
        if (sameFrame || throttled) return

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        overlayUploadTimestampNs = timestampNs
        overlayUploadMode = settings.mode
        overlayUploadSource = settings.source
        overlayUploadWidth = bitmap.width
        overlayUploadHeight = bitmap.height
        lastOverlayUploadMs = nowMs
    }

    private fun overlayBitmap(frameState: ArCoreFrameState, settings: ArCoreOverlaySettings): Bitmap? {
        return when (settings.mode) {
            DepthOverlayMode.Off -> null
            DepthOverlayMode.RawDepthHeatmap -> {
                val depth = selectDepth(frameState, settings.source) ?: return null
                depthToHeatmap(depth)
            }
            DepthOverlayMode.Confidence -> {
                val confidence = frameState.confidence ?: return null
                ArCoreDepthColorizer.confidenceToBitmap(
                    confidence = confidence.confidence,
                    width = confidence.width,
                    height = confidence.height,
                )
            }
            DepthOverlayMode.DepthMaskedByConfidence -> {
                val depth = selectDepth(frameState, settings.source) ?: return null
                val confidence = frameState.confidence ?: return null
                if (depth.width != confidence.width || depth.height != confidence.height) {
                    return depthToHeatmap(depth)
                }
                ArCoreDepthColorizer.maskedDepthHeatmapBitmap(
                    depthMm = depth.depthMm,
                    confidence = confidence.confidence,
                    width = depth.width,
                    height = depth.height,
                    confidenceThreshold = settings.confidenceThreshold,
                )
            }
        }
    }

    private fun depthToHeatmap(depth: DepthImageData): Bitmap =
        ArCoreDepthColorizer.depthToHeatmapBitmap(
            depthMm = depth.depthMm,
            width = depth.width,
            height = depth.height,
        )

    private fun selectDepth(frameState: ArCoreFrameState, depthSource: DepthSourceToggle): DepthImageData? =
        when (depthSource) {
            DepthSourceToggle.Raw -> frameState.rawDepth
            DepthSourceToggle.Smoothed -> frameState.smoothedDepth ?: frameState.rawDepth
        }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        private val QUAD_VERTICES = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )
        private val QUAD_VIEW_COORDS = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f,
        )
        private val QUAD_VERTICES_BUFFER: FloatBuffer =
            ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(QUAD_VERTICES)
                    position(0)
                }
        private val QUAD_VIEW_COORDS_BUFFER: FloatBuffer =
            ByteBuffer.allocateDirect(QUAD_VIEW_COORDS.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(QUAD_VIEW_COORDS)
                    position(0)
                }
        private const val OVERLAY_UPLOAD_INTERVAL_MS = 100L

        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val CAMERA_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, v_TexCoord);
            }
        """

        private const val OVERLAY_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D sTexture;
            uniform float u_Alpha;
            varying vec2 v_TexCoord;
            void main() {
                if (v_TexCoord.x < 0.0 || v_TexCoord.x > 1.0 || v_TexCoord.y < 0.0 || v_TexCoord.y > 1.0) {
                    discard;
                }
                vec4 color = texture2D(sTexture, v_TexCoord);
                gl_FragColor = vec4(color.rgb, color.a * u_Alpha);
            }
        """
    }
}
