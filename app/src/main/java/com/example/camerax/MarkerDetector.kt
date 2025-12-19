package com.example.camerax

import androidx.camera.core.ImageProxy

/**
 * Step 8 (stub):
 * - Interface for future OpenCV ArUco/AprilTag implementation.
 * - Stub returns "not enabled" (N/A).
 */
data class MarkerDetectionStatus(
    val isEnabled: Boolean,
    val count: Int?,
    val displayText: String
) {
    companion object {
        fun notEnabled(): MarkerDetectionStatus =
            MarkerDetectionStatus(
                isEnabled = false,
                count = null,
                displayText = "Markers detected: N/A"
            )
    }
}

interface MarkerDetector {
    /**
     * TODO(OpenCV):
     *  - Convert ImageProxy -> grayscale Mat
     *  - Run ArUco or AprilTag detection
     *  - Store latest count in a thread-safe way
     *
     * NOTE: Do NOT close(image) here. Analyzer owns the lifecycle.
     */
    fun process(image: ImageProxy)

    /** Latest status for UI */
    fun latest(): MarkerDetectionStatus

    /** Reset state between sessions */
    fun reset()
}

class StubMarkerDetector : MarkerDetector {
    override fun process(image: ImageProxy) {
        // Not enabled (stub). No-op.
    }

    override fun latest(): MarkerDetectionStatus = MarkerDetectionStatus.notEnabled()

    override fun reset() {
        // No-op
    }
}
