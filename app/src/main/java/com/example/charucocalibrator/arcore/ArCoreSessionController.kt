package com.example.charucocalibrator.arcore

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Display
import android.view.Surface
import com.example.charucocalibrator.arcore.model.ArCoreFrameState
import com.google.ar.core.Config
import com.google.ar.core.ImageMetadata
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
            androidCameraTimestampNs = runCatching {
                frame.imageMetadata.getLong(ImageMetadata.SENSOR_TIMESTAMP)
            }.getOrElse { 0L },
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
    }
}

class ArCorePreviewRenderer(
    private val sessionController: ArCoreSessionController,
) : GLSurfaceView.Renderer {
    private var quadProgram = 0
    private var quadPositionHandle = 0
    private var quadTexCoordHandle = 0
    private var quadTextureHandle = 0
    private var cameraTextureId = 0
    private val transformedTexCoordsBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(QUAD_TEX_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD_TEX_COORDS)
                position(0)
            }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1f)
        quadProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        quadPositionHandle = GLES20.glGetAttribLocation(quadProgram, "a_Position")
        quadTexCoordHandle = GLES20.glGetAttribLocation(quadProgram, "a_TexCoord")
        quadTextureHandle = GLES20.glGetUniformLocation(quadProgram, "sTexture")
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        sessionController.onSurfaceCreated(cameraTextureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        sessionController.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        sessionController.onDrawFrame(this)
    }

    fun drawCameraBackground(frame: com.google.ar.core.Frame) {
        QUAD_TEX_COORDS_BUFFER.position(0)
        transformedTexCoordsBuffer.position(0)
        frame.transformDisplayUvCoords(QUAD_TEX_COORDS_BUFFER, transformedTexCoordsBuffer)
        transformedTexCoordsBuffer.position(0)
        GLES20.glUseProgram(quadProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(quadTextureHandle, 0)
        GLES20.glEnableVertexAttribArray(quadPositionHandle)
        GLES20.glVertexAttribPointer(
            quadPositionHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            QUAD_VERTICES_BUFFER,
        )
        GLES20.glEnableVertexAttribArray(quadTexCoordHandle)
        GLES20.glVertexAttribPointer(
            quadTexCoordHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            transformedTexCoordsBuffer,
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(quadPositionHandle)
        GLES20.glDisableVertexAttribArray(quadTexCoordHandle)
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
        private val QUAD_TEX_COORDS = floatArrayOf(
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
        private val QUAD_TEX_COORDS_BUFFER: FloatBuffer =
            ByteBuffer.allocateDirect(QUAD_TEX_COORDS.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(QUAD_TEX_COORDS)
                    position(0)
                }

        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, v_TexCoord);
            }
        """
    }
}
