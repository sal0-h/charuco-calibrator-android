package com.example.charucocalibrator

class CaptureSessionManager {
    @Volatile
    var currentSessionId: String = newSessionId()
        private set

    private var nextFrameIndex = 0

    fun startNewSession(): String {
        currentSessionId = newSessionId()
        nextFrameIndex = 0
        return currentSessionId
    }

    fun nextFrameIndex(): Int = nextFrameIndex++

    private fun newSessionId(): String = "session_${System.currentTimeMillis()}"

    companion object {
        const val METADATA_KEY = "capture_session_id"
        const val FRAME_INDEX_KEY = "capture_frame_index"
    }
}
