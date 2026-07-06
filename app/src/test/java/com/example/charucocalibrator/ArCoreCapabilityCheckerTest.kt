package com.example.charucocalibrator

import com.example.charucocalibrator.arcore.ArCoreAvailabilityStatus
import com.example.charucocalibrator.arcore.ArCoreCapabilityChecker
import com.google.ar.core.ArCoreApk
import org.junit.Assert.assertEquals
import org.junit.Test

class ArCoreCapabilityCheckerTest {
    @Test
    fun apkTooOldRequiresUpdate() {
        assertEquals(
            ArCoreAvailabilityStatus.SupportedApkUpdateRequired,
            ArCoreCapabilityChecker.mapAvailability(ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD),
        )
    }

    @Test
    fun installedIsSupported() {
        assertEquals(
            ArCoreAvailabilityStatus.Supported,
            ArCoreCapabilityChecker.mapAvailability(ArCoreApk.Availability.SUPPORTED_INSTALLED),
        )
    }
}
