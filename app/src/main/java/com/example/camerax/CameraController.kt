package com.example.camerax

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@SuppressLint("UnsafeOptInUsageError")
@OptIn(ExperimentalCamera2Interop::class)
class CameraController(
    private val camera: Camera,
    private val characteristics: CameraCharacteristics,
    private val resultStore: CaptureResultStore,
    private val coroutineScope: CoroutineScope,
    private val executor: Executor
) {
    @Volatile var aeStatus: String = "AUTO"
    @Volatile var wbStatus: String = "AUTO"
    @Volatile var afStatus: String = "AUTO"

    // Step 6 needs this
    @Volatile var lockModeUsed: String = "fallback"

    private val camera2 = Camera2CameraControl.from(camera.cameraControl)

    fun lockForPhotogrammetry(settleMs: Long = 1500L) {
        coroutineScope.launch {
            try {
                // 1) Force AUTO first
                setAllAuto().await()

                // 2) Help AE/AWB/AF converge at center, then wait
                runCenterMetering(settleMs)

                // 3) Read what AUTO settled to
                val autoIso = resultStore.latestIso()
                val autoShutter = resultStore.latestShutterNs()

                Log.d(TAG, "AUTO settled ISO=$autoIso shutterNs=$autoShutter")

                // 4) Lock exposure + WB using those values if possible
                applyExposureAndWbLock(autoShutter, autoIso).await()

                Log.d(TAG, "Lock done: AE=$aeStatus WB=$wbStatus AF=$afStatus mode=$lockModeUsed")
            } catch (t: Throwable) {
                Log.e(TAG, "Lock failed: ${t.message}", t)
                lockModeUsed = "fallback"
            }
        }
    }

    fun unlockAll(): ListenableFuture<Void> {
        aeStatus = "AUTO"
        wbStatus = "AUTO"
        afStatus = "AUTO"
        lockModeUsed = "fallback"
        camera.cameraControl.cancelFocusAndMetering()
        return setAllAuto()
    }

    // ---------------- internal ----------------

    private fun setAllAuto(): ListenableFuture<Void> {
        val b = CaptureRequestOptions.Builder()
            // AE
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
            // AWB
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
            // AF
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

        return camera2.addCaptureRequestOptions(b.build())
    }

    private suspend fun runCenterMetering(settleMs: Long) {
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val point = factory.createPoint(0.5f, 0.5f)

        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        )
            .setAutoCancelDuration((settleMs + 300L).coerceAtMost(2500L), TimeUnit.MILLISECONDS)
            .build()

        try {
            camera.cameraControl.startFocusAndMetering(action)
        } catch (_: Throwable) { /* non-fatal */ }

        delay(settleMs)
        afStatus = "AUTO settled"
    }

    private fun applyExposureAndWbLock(autoShutterNs: Long?, autoIso: Int?): ListenableFuture<Void> {
        val b = CaptureRequestOptions.Builder()

        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val hasManualSensor = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true

        val canManualExposure = hasManualSensor && exposureRange != null && isoRange != null
        val aeLockAvailable = characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true

        // ---- Exposure ----
        if (canManualExposure && autoShutterNs != null && autoIso != null) {
            val shutterClamped = autoShutterNs.coerceIn(exposureRange!!.lower, exposureRange.upper)
            val isoClamped = autoIso.coerceIn(isoRange!!.lower, isoRange.upper)

            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterClamped)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, isoClamped)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)

            aeStatus = "MANUAL from AUTO (ISO=$isoClamped, t=$shutterClamped)"
            lockModeUsed = "manual"
        } else if (aeLockAvailable) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)

            aeStatus = "AE_LOCK (no manual)"
            lockModeUsed = "AE_LOCK"
        } else {
            aeStatus = "AUTO (no lock available)"
            lockModeUsed = "fallback"
        }

        // ---- White balance ----
        val awbLockAvailable = characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
        if (awbLockAvailable) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
            wbStatus = "AWB_LOCK"
        } else {
            wbStatus = "AUTO (no lock available)"
        }

        return camera2.addCaptureRequestOptions(b.build())
    }

    private suspend fun <T> ListenableFuture<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            addListener({
                try { cont.resume(get()) }
                catch (e: Exception) { cont.resumeWithException(e) }
            }, MoreExecutors.directExecutor())
        }

    private companion object { const val TAG = "CameraController" }
}
