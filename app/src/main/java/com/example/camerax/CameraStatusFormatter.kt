package com.example.camerax

import android.hardware.camera2.CaptureResult
import kotlin.math.roundToInt

/**
 * UI-friendly formatting for camera status without changing capture logic.
 */
object CameraStatusFormatter {

    fun formatAfState(state: Int?): String {
        return when (state) {
            null -> "AF state=—"
            CaptureResult.CONTROL_AF_STATE_INACTIVE -> "AF: INACTIVE"
            CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "AF: PASSIVE_SCAN"
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "AF: PASSIVE_FOCUSED"
            CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "AF: ACTIVE_SCAN"
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "AF: FOCUSED_LOCKED"
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "AF: NOT_FOCUSED_LOCKED"
            // Some devices use additional constants (e.g., PASSIVE_UNFOCUSED). Keep fallback:
            else -> "AF state=$state"
        }
    }

    fun formatFocusDistance(diopters: Float?): String {
        if (diopters == null) return "fd=—"
        if (diopters <= 0f) return "fd=∞"
        val cm = (100f / diopters).roundToInt()
        return "fd≈${cm}cm (${String.format("%.2fD", diopters)})"
    }
}
