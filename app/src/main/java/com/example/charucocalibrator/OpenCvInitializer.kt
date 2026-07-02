package com.example.charucocalibrator

import android.util.Log
import org.opencv.android.OpenCVLoader
import java.util.concurrent.atomic.AtomicBoolean

object OpenCvInitializer {
    private val initialized = AtomicBoolean(false)

    fun ensureInitialized(): Boolean {
        if (initialized.get()) return true
        synchronized(this) {
            if (initialized.get()) return true
            val loaded = OpenCVLoader.initLocal()
            if (loaded) {
                initialized.set(true)
                Log.i(TAG, "OpenCV loaded successfully")
            } else {
                Log.e(TAG, "OpenCV initialization failed")
            }
            return loaded
        }
    }
}

private const val TAG = "OpenCvInitializer"
