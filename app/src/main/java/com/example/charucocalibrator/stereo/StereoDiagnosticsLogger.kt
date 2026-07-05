package com.example.charucocalibrator.stereo

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.charucocalibrator.FrameMetadata
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent, low-volume diagnostics for one visit to the stereo tool.
 *
 * Events are JSON Lines so a partially written session remains readable after a crash. The
 * timestamped file is retained for history and a fixed latest file gives adb a stable pull path.
 */
class StereoDiagnosticsLogger(context: Context) : AutoCloseable {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val startedAtElapsedMs = SystemClock.elapsedRealtime()
    private var lastStreamState: StereoStreamState? = null
    private var lastTelemetryAtElapsedMs = 0L

    private val directory = File(
        checkNotNull(context.applicationContext.getExternalFilesDir(null)) {
            "App-specific external files directory is unavailable"
        },
        DIAGNOSTICS_DIRECTORY
    ).apply { mkdirs() }

    val sessionFile: File = File(directory, "stereo_session_${System.currentTimeMillis()}.jsonl")
    val latestFile: File = File(directory, LATEST_FILE_NAME)

    init {
        latestFile.writeText("")
        pruneOldSessions()
        log(
            event = "session_started",
            details = JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("device", Build.DEVICE)
                put("android_sdk", Build.VERSION.SDK_INT)
                put("package_name", context.packageName)
                put("app_version", appVersion(context))
            }
        )
    }

    fun log(event: String, details: JSONObject = JSONObject()) {
        if (closed.get()) return
        val record = JSONObject().apply {
            put("generated_at_utc", Instant.now().toString())
            put("elapsed_ms", SystemClock.elapsedRealtime() - startedAtElapsedMs)
            put("event", event)
            put("details", details)
        }.toString()

        synchronized(lock) {
            if (closed.get()) return
            sessionFile.appendText(record + "\n")
            latestFile.appendText(record + "\n")
        }
        Log.i(TAG, "$event $details")
    }

    /** Logs every state transition and samples live stream metrics at most every two seconds. */
    fun logLiveState(state: StereoLiveState) {
        val now = SystemClock.elapsedRealtime()
        val stateChanged = state.streamState != lastStreamState
        if (stateChanged) {
            lastStreamState = state.streamState
            log("stream_state_changed", state.toDiagnosticsJson())
        }
        if (state.streamState == StereoStreamState.STREAMING &&
            now - lastTelemetryAtElapsedMs >= TELEMETRY_INTERVAL_MS
        ) {
            lastTelemetryAtElapsedMs = now
            log("stream_telemetry", state.toDiagnosticsJson())
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val record = JSONObject().apply {
            put("generated_at_utc", Instant.now().toString())
            put("elapsed_ms", SystemClock.elapsedRealtime() - startedAtElapsedMs)
            put("event", "session_ended")
            put("details", JSONObject())
        }.toString()
        synchronized(lock) {
            sessionFile.appendText(record + "\n")
            latestFile.appendText(record + "\n")
        }
        Log.i(TAG, "session_ended")
    }

    private fun pruneOldSessions() {
        directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("stereo_session_") && it != latestFile }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_RETAINED_SESSIONS)
            ?.forEach(File::delete)
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    companion object {
        const val DIAGNOSTICS_DIRECTORY = "stereo_diagnostics"
        const val LATEST_FILE_NAME = "stereo_session_latest.jsonl"
        private const val TAG = "StereoDiagnostics"
        private const val TELEMETRY_INTERVAL_MS = 2_000L
        private const val MAX_RETAINED_SESSIONS = 10
    }
}

private fun StereoLiveState.toDiagnosticsJson(): JSONObject = JSONObject().apply {
    put("state", streamState.name)
    put("left_physical_camera_id", leftPhysicalId ?: JSONObject.NULL)
    put("right_physical_camera_id", rightPhysicalId ?: JSONObject.NULL)
    put("pair_label", pairLabel ?: JSONObject.NULL)
    put(
        "resolution",
        resolution?.let { "${it.width}x${it.height}" } ?: JSONObject.NULL
    )
    put("left_fps", leftFps.toDouble())
    put("right_fps", rightFps.toDouble())
    put("timestamp_delta_ns", timestampDeltaNs ?: JSONObject.NULL)
    put("left_frame_count", leftFrameCount)
    put("right_frame_count", rightFrameCount)
    put("fallback_reason", fallbackReason ?: JSONObject.NULL)
    put("hal_error", halError ?: JSONObject.NULL)
    put("warning", warningMessage ?: JSONObject.NULL)
    put("af_policy", afPolicy)
    put("ois_disabled", oisDisabled)
    put("left_metadata", leftMetadata.toDiagnosticsJson())
    put("right_metadata", rightMetadata.toDiagnosticsJson())
}

private fun FrameMetadata?.toDiagnosticsJson(): Any {
    if (this == null) return JSONObject.NULL
    return JSONObject().apply {
        put("sensor_timestamp_ns", sensorTimestampNs ?: JSONObject.NULL)
        this@toDiagnosticsJson.appendToJson(this)
    }
}
