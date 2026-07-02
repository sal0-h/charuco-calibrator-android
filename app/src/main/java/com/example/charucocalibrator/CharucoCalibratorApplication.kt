package com.example.charucocalibrator

import android.app.Application
import android.util.Log

class CharucoCalibratorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!OpenCvInitializer.ensureInitialized()) {
            Log.e(TAG, "OpenCV failed to initialize in Application.onCreate")
        }
    }

    companion object {
        private const val TAG = "CharucoCalibratorApp"
    }
}
