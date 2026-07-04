package com.example.charucocalibrator.arcore

import android.app.Activity
import android.content.Context
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException

enum class ArCoreAvailabilityStatus {
    Supported,
    SupportedApkUpdateRequired,
    UnsupportedDevice,
    UnknownError,
}

data class ArCoreDepthModeProbe(
    val automaticSupported: Boolean,
    val rawDepthOnlySupported: Boolean,
    val selectedMode: Config.DepthMode,
    val selectedModeLabel: String,
)

object ArCoreCapabilityChecker {

    fun checkAvailability(context: Context): ArCoreAvailabilityStatus {
        return try {
            when (ArCoreApk.getInstance().checkAvailability(context)) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED,
                ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
                -> ArCoreAvailabilityStatus.Supported

                ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> ArCoreAvailabilityStatus.SupportedApkUpdateRequired
                ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE ->
                    ArCoreAvailabilityStatus.UnsupportedDevice

                else -> ArCoreAvailabilityStatus.UnknownError
            }
        } catch (_: Exception) {
            ArCoreAvailabilityStatus.UnknownError
        }
    }

    fun requestInstall(activity: Activity): ArCoreInstallResult {
        return try {
            when (ArCoreApk.getInstance().requestInstall(activity, true)) {
                ArCoreApk.InstallStatus.INSTALLED -> ArCoreInstallResult.Installed
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> ArCoreInstallResult.InstallRequested
            }
        } catch (e: UnavailableUserDeclinedInstallationException) {
            ArCoreInstallResult.UserDeclined(e.message ?: "User declined ARCore install")
        } catch (e: UnavailableArcoreNotInstalledException) {
            ArCoreInstallResult.Error(e.message ?: "ARCore not installed")
        } catch (e: UnavailableApkTooOldException) {
            ArCoreInstallResult.Error(e.message ?: "ARCore APK too old")
        } catch (e: UnavailableSdkTooOldException) {
            ArCoreInstallResult.Error(e.message ?: "ARCore SDK too old")
        } catch (e: UnavailableDeviceNotCompatibleException) {
            ArCoreInstallResult.Error(e.message ?: "Device not compatible with ARCore")
        } catch (e: Exception) {
            ArCoreInstallResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    fun probeDepthModes(session: Session): ArCoreDepthModeProbe {
        val automaticSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        val rawOnlySupported = session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
        val selected = when {
            automaticSupported -> Config.DepthMode.AUTOMATIC
            rawOnlySupported -> Config.DepthMode.RAW_DEPTH_ONLY
            else -> Config.DepthMode.DISABLED
        }
        val label = when (selected) {
            Config.DepthMode.AUTOMATIC -> "AUTOMATIC"
            Config.DepthMode.RAW_DEPTH_ONLY -> "RAW_DEPTH_ONLY"
            else -> "DISABLED"
        }
        return ArCoreDepthModeProbe(
            automaticSupported = automaticSupported,
            rawDepthOnlySupported = rawOnlySupported,
            selectedMode = selected,
            selectedModeLabel = label,
        )
    }
}

sealed class ArCoreInstallResult {
    data object Installed : ArCoreInstallResult()
    data object InstallRequested : ArCoreInstallResult()
    data class UserDeclined(val message: String) : ArCoreInstallResult()
    data class Error(val message: String) : ArCoreInstallResult()
}
