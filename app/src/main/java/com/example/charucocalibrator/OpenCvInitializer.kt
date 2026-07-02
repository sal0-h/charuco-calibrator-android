package com.example.charucocalibrator

import android.util.Log
import org.opencv.android.OpenCVLoader
import java.util.concurrent.atomic.AtomicBoolean

object OpenCvInitializer {
    private val initialized = AtomicBoolean(false)
    private val runtimeLock = Any()

    fun isInitialized(): Boolean = initialized.get()

    fun <T> withLock(block: () -> T): T = synchronized(runtimeLock, block)

    fun ensureInitialized(): Boolean {
        if (initialized.get()) return true
        synchronized(this) {
            if (initialized.get()) return true
            val loaded = runCatching {
                OpenCVLoader.initLocal()
            }.getOrElse { error ->
                Log.e(TAG, "OpenCVLoader.initLocal() threw", error)
                false
            }
            if (loaded) {
                initialized.set(true)
                Log.i(TAG, "OpenCV loaded successfully (${OpenCVLoader.OPENCV_VERSION})")
            } else {
                Log.e(TAG, "OpenCV initialization failed")
            }
            return loaded
        }
    }
}

private const val TAG = "OpenCvInitializer"
