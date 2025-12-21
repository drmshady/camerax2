package com.example.camerax

import android.util.Log
import java.io.File
import java.time.Instant
import java.util.Locale

data class ImageSidecarMetadata(
    val timestampMs: Long,
    val filename: String,
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
    val markerSummary: Any? = null
) {
    val timestampIso: String = Instant.ofEpochMilli(timestampMs).toString()
}

class ImageSidecarWriter {

    fun writeSidecarJson(imageFile: File, meta: ImageSidecarMetadata) {
        val jsonFile = File(imageFile.parentFile, imageFile.nameWithoutExtension + ".json")
        val tmpFile = File(imageFile.parentFile, imageFile.nameWithoutExtension + ".json.tmp")

        try {
            val text = buildJson(meta)
            tmpFile.writeText(text, Charsets.UTF_8)

            if (jsonFile.exists()) jsonFile.delete()
            if (!tmpFile.renameTo(jsonFile)) {
                jsonFile.writeText(text, Charsets.UTF_8)
                tmpFile.delete()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed writing sidecar for ${imageFile.name}: ${t.message}", t)
            try { tmpFile.delete() } catch (_: Throwable) {}
        }
    }

    private fun buildJson(m: ImageSidecarMetadata): String {
        val sb = StringBuilder(1200)
        sb.append("{\n")

        kvLong(sb, "timestampMs", m.timestampMs, comma = true)
        kvString(sb, "timestampIso", m.timestampIso, comma = true)
        kvString(sb, "filename", m.filename, comma = true)

        kvInt(sb, "ISO", m.iso, comma = true)
        kvLongNullable(sb, "shutterNs", m.shutterNs, comma = true)
        kvFloat(sb, "focusDistanceDiopters", m.focusDistanceDiopters, comma = true)
        kvDouble(sb, "distanceEstimateCm", m.distanceEstimateCm, comma = true)
        kvDoubleNonNull(sb, "blurScore", m.blurScore, comma = true)

        array(sb, "exposureFlags", m.exposureFlags, comma = true)
        kvString(sb, "qualityStatus", m.qualityStatus, comma = true)
        kvString(sb, "lockModeUsed", m.lockModeUsed, comma = true)
        kvString(sb, "torchState", m.torchState, comma = true)

        kvString(sb, "sessionType", m.sessionType, comma = true)
        kvString(sb, "sessionName", m.sessionName, comma = true)

        // optional clinical info (null for calibration)
        kvNullableString(sb, "doctorName", m.doctorName, comma = true)
        kvNullableString(sb, "patientName", m.patientName, comma = true)
        kvNullableString(sb, "patientId", m.patientId, comma = true)

        kvInt(sb, "calibrationTargetDistanceCm", m.calibrationTargetDistanceCm, comma = true)
        kvAny(sb, "markerSummary", m.markerSummary, comma = false)

        sb.append("\n}")
        return sb.toString()
    }


    private fun kvAny(sb: StringBuilder, key: String, value: Any?, comma: Boolean) {
        sb.append("  \"").append(esc(key)).append("\": ")
        writeAny(sb, value, indent = "  ")
        if (comma) sb.append(",")
        sb.append("\n")
    }

    private fun writeAny(sb: StringBuilder, value: Any?, indent: String) {
        when (value) {
            null -> sb.append("null")
            is String -> sb.append("\"").append(esc(value)).append("\"")
            is Boolean, is Int, is Long, is Double, is Float -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append("{\n")
                val entries = value.entries.toList()
                for ((i, e) in entries.withIndex()) {
                    val k = e.key?.toString() ?: "null"
                    sb.append(indent).append("  \"").append(esc(k)).append("\": ")
                    writeAny(sb, e.value, indent + "  ")
                    if (i != entries.size - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append(indent).append("}")
            }
            is List<*> -> {
                sb.append("[")
                for (i in value.indices) {
                    if (i > 0) sb.append(", ")
                    writeAny(sb, value[i], indent)
                }
                sb.append("]")
            }
            else -> sb.append("\"").append(esc(value.toString())).append("\"")
        }
    }

    // ---- Writers (unique JVM signatures) ----

    private fun kvString(sb: StringBuilder, key: String, value: String, comma: Boolean) {
        sb.append("  \"").append(esc(key)).append("\": \"").append(esc(value)).append("\"")
        if (comma) sb.append(",")
        sb.append("\n")
    }

    private fun kvNullableString(sb: StringBuilder, key: String, value: String?, comma: Boolean) {
        sb.append("  \"").append(esc(key)).append("\": ")
        if (value == null) sb.append("null")
        else sb.append("\"").append(esc(value)).append("\"")
        if (comma) sb.append(",")
        sb.append("\n")
    }

    private fun kvInt(sb: StringBuilder, key: String, value: Int?, comma: Boolean) {
        sb.append("  \"").append(esc(key)).append("\": ").append(value?.toString() ?: "null")
        if (comma) sb.append(",")
        sb.append("\n")
    }

    private fun kvLongNullable(sb: StringBuilder, key: String, value: Long?, comma: Boolean) {
        sb.append("  \"").append(esc(key)).append("\": ").append(value?.toString() ?: "null")
        if (comma) sb.append(",")
        sb.append("\n")
    }

    private fun kvLong(sb: StringBuilder, key: String, value: Long, comma: Boolean) {
        sb.append("  \"").append(esc(key)).append("\": ").append(value)
        if (comma) sb.append(",")
        sb.append("\n")
    }

    private fun kvFloat(sb: StringBuilder, key: String, value: Float?, comma: Boolean) {
        sb.append("  \"").append(esc(key)).append("\": ")
        sb.append(value?.let { String.format(Locale.US, "%.6f", it) } ?: "null")
        if (comma) sb.append(",")
        sb.append("\n")
    }

    private fun kvDouble(sb: StringBuilder, key: String, value: Double?, comma: Boolean) {
        sb.append("  \"").append(esc(key)).append("\": ")
        sb.append(value?.let { String.format(Locale.US, "%.6f", it) } ?: "null")
        if (comma) sb.append(",")
        sb.append("\n")
    }

    private fun kvDoubleNonNull(sb: StringBuilder, key: String, value: Double, comma: Boolean) {
        sb.append("  \"").append(esc(key)).append("\": ")
        sb.append(String.format(Locale.US, "%.6f", value))
        if (comma) sb.append(",")
        sb.append("\n")
    }

    private fun array(sb: StringBuilder, key: String, values: List<String>, comma: Boolean) {
        sb.append("  \"").append(esc(key)).append("\": [")
        values.forEachIndexed { i, v ->
            if (i > 0) sb.append(", ")
            sb.append("\"").append(esc(v)).append("\"")
        }
        sb.append("]")
        if (comma) sb.append(",")
        sb.append("\n")
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    private companion object {
        private const val TAG = "ImageSidecarWriter"
    }
}
