package com.example.camerax

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata

class DeviceCapabilityChecker(private val characteristics: CameraCharacteristics) {

    fun hasManualSensor(): Boolean {
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        return capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
    }

    fun supportsManualFocus(): Boolean {
        val minFocusDist = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        return minFocusDist != null && minFocusDist > 0
    }

    fun supportsManualWhiteBalance(): Boolean {
        return characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true

    }
}