package com.example.charucocalibrator

object AcceptanceConfig {
    const val MIN_CHARUCO_CORNERS = 12
    const val SHARPNESS_THRESHOLD = 100.0
    const val MIN_BBOX_AREA_RATIO = 0.10
    const val MIN_TIME_BETWEEN_ACCEPTED_MS = 900L
    const val MAX_ACCEPTED_FRAMES = 30
    const val MIN_CENTER_DISTANCE_RATIO = 0.10
    const val MIN_AREA_RATIO_DELTA = 0.03
    const val MIN_ASPECT_DELTA = 0.06
    const val MIN_FRAMES_FOR_CALIBRATION = 10
    const val MAX_PER_VIEW_REPROJECTION_ERROR_PX = 3.0
    const val MAX_ISO = 1000
    const val MAX_EXPOSURE_TIME_NS = 15_000_000L
    /** Max relative focus-distance oscillation (diopters) in a short window; catches AF hunting. */
    const val MAX_FOCUS_RELATIVE_STD_DEV = 0.08f
    const val MIN_FOCUS_SAMPLES_FOR_VARIANCE = 5
    const val REQUIRE_CAPTURE_METADATA = true
    const val MIN_VIEWS_FOR_RATIONAL_MODEL = 25
}
