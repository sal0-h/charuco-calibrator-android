package com.example.charucocalibrator

import android.os.Build

/**
 * Human-readable device descriptor for export metadata, derived from the running
 * device rather than hardcoded to a single target phone.
 */
object DeviceInfo {
    fun describe(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return when {
            manufacturer.isEmpty() && model.isEmpty() -> "unknown"
            model.isEmpty() -> manufacturer
            manufacturer.isEmpty() -> model
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
    }
}
