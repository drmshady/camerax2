package com.example.camerax

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import java.util.concurrent.atomic.AtomicReference

data class CaptureSnapshot(
    val iso: Int?,
    val shutterNs: Long?,
    val focusDistanceDiopters: Float?
)

/**
 * Stores latest Camera2 values observed via Camera2Interop capture callbacks.
 * - Used by CameraController (latestIso/latestShutterNs/latestFocusDistance)
 * - Used by Step 6 sidecar writing (snapshot) without re-querying camera
 */
class CaptureResultStore {

    private val isoRef = AtomicReference<Int?>(null)
    private val shutterNsRef = AtomicReference<Long?>(null)
    private val focusDistRef = AtomicReference<Float?>(null)

    val sessionCallback: CameraCaptureSession.CaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                val shutterNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val focusDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE) // diopters

                if (iso != null) isoRef.set(iso)
                if (shutterNs != null) shutterNsRef.set(shutterNs)
                if (focusDist != null) focusDistRef.set(focusDist)
            }
        }

    // For CameraController Step 4
    fun latestIso(): Int? = isoRef.get()
    fun latestShutterNs(): Long? = shutterNsRef.get()
    fun latestFocusDistance(): Float? = focusDistRef.get()

    // For Step 6 (deterministic snapshot without re-query)
    fun snapshot(): CaptureSnapshot =
        CaptureSnapshot(
            iso = isoRef.get(),
            shutterNs = shutterNsRef.get(),
            focusDistanceDiopters = focusDistRef.get()
        )
}
