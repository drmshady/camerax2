package com.example.camerax

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import java.util.concurrent.atomic.AtomicReference

class CaptureResultStore {
    private val isoRef = AtomicReference<Int?>(null)
    private val shutterNsRef = AtomicReference<Long?>(null)
    private val focusDistRef = AtomicReference<Float?>(null)

    val sessionCallback: CameraCaptureSession.CaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: android.hardware.camera2.CaptureRequest,
                result: TotalCaptureResult
            ) {
                isoRef.set(result.get(CaptureResult.SENSOR_SENSITIVITY))
                shutterNsRef.set(result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                focusDistRef.set(result.get(CaptureResult.LENS_FOCUS_DISTANCE))
            }
        }

    fun latestIso(): Int? = isoRef.get()
    fun latestShutterNs(): Long? = shutterNsRef.get()
    fun latestFocusDistance(): Float? = focusDistRef.get()
}
