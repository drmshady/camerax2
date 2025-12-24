package com.example.camerax

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.time.Instant
import java.util.Locale

data class ImageSidecarMetadata(
    val timestampMs: Long,
    val filename: String,
    // actual saved image size (best-effort). Used for intrinsics reuse.
    val imageSize: List<Int>? = null,
    // stable key for selecting intrinsics calibration on PC
    val intrinsicsKey: String? = null,
    val iso: Int?,
    val shutterNs: Long?,
    val focusDistanceDiopters: Float?,
    val distanceEstimateCm: Double?,
    val blurScore: Double,
    val exposureFlags: List<String>,
    val qualityStatus: String,
    val lockModeUsed: String,
    val torchState: String,

    // session context
    val sessionType: String,
    val sessionName: String,
    val doctorName: String? = null,
    val patientName: String? = null,
    val patientId: String? = null,
    val calibrationTargetDistanceCm: Int? = null,

    // Phase 1.5 markers (optional)
    val markerSummary: Any? = null,

    // ---- NEW: truth fields (merge into existing JSON) ----
    val savedWidth: Int? = null,
    val savedHeight: Int? = null,
    val exifOrientation: Int? = null,          // EXIF tag value 1..8
    val deviceRotationDegrees: Int? = null,    // 0/90/180/270 at capture time
    val rotationDegreesApplied: Int? = null    // pixel-rotation applied by app (usually 0)
) {
    val timestampIso: String = Instant.ofEpochMilli(timestampMs).toString()
}

/**
 * Writes per-image sidecar JSON without deleting unknown keys.
 *
 * - Reads existing JSON if present.
 * - Updates/overwrites known keys (timestamp, ISO, etc.) with current values.
 * - Preserves any other keys that might have been added by other modules.
 * - Writes via tmp + replace to avoid partial files.
 */
class ImageSidecarWriter {

    fun writeSidecarJson(imageFile: File, meta: ImageSidecarMetadata) {
        val jsonFile = File(imageFile.parentFile, imageFile.nameWithoutExtension + ".json")
        val tmpFile = File(imageFile.parentFile, imageFile.nameWithoutExtension + ".json.tmp")

        try {
            val root = readExistingJson(jsonFile)

            // Core fields
            root.put("timestampMs", meta.timestampMs)
            root.put("timestampIso", meta.timestampIso)
            root.put("filename", meta.filename)

            // Keep legacy key name used by existing PC scripts
            if (meta.imageSize != null) root.put("image_size", JSONArray(meta.imageSize)) else root.put("image_size", JSONObject.NULL)
            if (meta.intrinsicsKey != null) root.put("intrinsicsKey", meta.intrinsicsKey) else root.put("intrinsicsKey", JSONObject.NULL)

            // Camera values
            if (meta.iso != null) root.put("ISO", meta.iso) else root.put("ISO", JSONObject.NULL)
            if (meta.shutterNs != null) root.put("shutterNs", meta.shutterNs) else root.put("shutterNs", JSONObject.NULL)
            if (meta.focusDistanceDiopters != null) {
                root.put("focusDistanceDiopters", String.format(Locale.US, "%.6f", meta.focusDistanceDiopters).toDouble())
            } else {
                root.put("focusDistanceDiopters", JSONObject.NULL)
            }
            if (meta.distanceEstimateCm != null) {
                root.put("distanceEstimateCm", String.format(Locale.US, "%.6f", meta.distanceEstimateCm).toDouble())
            } else {
                root.put("distanceEstimateCm", JSONObject.NULL)
            }
            root.put("blurScore", String.format(Locale.US, "%.6f", meta.blurScore).toDouble())

            root.put("exposureFlags", JSONArray(meta.exposureFlags))
            root.put("qualityStatus", meta.qualityStatus)
            root.put("lockModeUsed", meta.lockModeUsed)
            root.put("torchState", meta.torchState)

            // Session context
            root.put("sessionType", meta.sessionType)
            root.put("sessionName", meta.sessionName)

            putNullableString(root, "doctorName", meta.doctorName)
            putNullableString(root, "patientName", meta.patientName)
            putNullableString(root, "patientId", meta.patientId)

            if (meta.calibrationTargetDistanceCm != null) root.put("calibrationTargetDistanceCm", meta.calibrationTargetDistanceCm)
            else root.put("calibrationTargetDistanceCm", JSONObject.NULL)

            // Markers (Any -> JSON)
            root.put("markerSummary", toJsonValue(meta.markerSummary))

            // ---- NEW truth fields ----
            if (meta.savedWidth != null) root.put("savedWidth", meta.savedWidth) else root.put("savedWidth", JSONObject.NULL)
            if (meta.savedHeight != null) root.put("savedHeight", meta.savedHeight) else root.put("savedHeight", JSONObject.NULL)
            if (meta.exifOrientation != null) root.put("exifOrientation", meta.exifOrientation) else root.put("exifOrientation", JSONObject.NULL)
            if (meta.deviceRotationDegrees != null) root.put("deviceRotationDegrees", meta.deviceRotationDegrees) else root.put("deviceRotationDegrees", JSONObject.NULL)
            if (meta.rotationDegreesApplied != null) root.put("rotationDegreesApplied", meta.rotationDegreesApplied) else root.put("rotationDegreesApplied", JSONObject.NULL)

            val out = root.toString(2)
            tmpFile.writeText(out, Charsets.UTF_8)

            if (jsonFile.exists()) {
                try { jsonFile.delete() } catch (_: Throwable) {}
            }
            if (!tmpFile.renameTo(jsonFile)) {
                jsonFile.writeText(out, Charsets.UTF_8)
                try { tmpFile.delete() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed writing sidecar for ${imageFile.name}: ${t.message}", t)
            try { tmpFile.delete() } catch (_: Throwable) {}
        }
    }

    private fun readExistingJson(jsonFile: File): JSONObject {
        if (!jsonFile.exists()) return JSONObject()
        return try {
            val txt = jsonFile.readText(Charsets.UTF_8)
            val v = JSONTokener(txt).nextValue()
            if (v is JSONObject) v else JSONObject()
        } catch (_: Throwable) {
            JSONObject()
        }
    }

    private fun putNullableString(o: JSONObject, key: String, value: String?) {
        if (value == null) o.put(key, JSONObject.NULL) else o.put(key, value)
    }

    private fun toJsonValue(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is JSONObject, is JSONArray -> value
            is String, is Boolean, is Int, is Long -> value
            is Float -> String.format(Locale.US, "%.6f", value).toDouble()
            is Double -> {
                if (value.isNaN() || value.isInfinite()) JSONObject.NULL
                else String.format(Locale.US, "%.6f", value).toDouble()
            }
            is Map<*, *> -> {
                val obj = JSONObject()
                for ((k, v) in value.entries) {
                    if (k == null) continue
                    obj.put(k.toString(), toJsonValue(v))
                }
                obj
            }
            is Iterable<*> -> {
                val arr = JSONArray()
                for (x in value) arr.put(toJsonValue(x))
                arr
            }
            is Array<*> -> {
                val arr = JSONArray()
                for (x in value) arr.put(toJsonValue(x))
                arr
            }
            else -> value.toString()
        }
    }

    private companion object {
        private const val TAG = "ImageSidecarWriter"
    }
}
