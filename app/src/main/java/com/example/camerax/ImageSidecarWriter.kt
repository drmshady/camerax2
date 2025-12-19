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
    val torchState: String
) {
    val timestampIso: String = Instant.ofEpochMilli(timestampMs).toString()
}

/**
 * Deterministic sidecar writer:
 * - stable key order
 * - atomic-ish write via .tmp then rename
 * - NO camera re-query (pure IO)
 */
class ImageSidecarWriter {

    fun writeSidecarJson(imageFile: File, meta: ImageSidecarMetadata) {
        val jsonFile = File(imageFile.parentFile, imageFile.nameWithoutExtension + ".json")
        val tmpFile = File(imageFile.parentFile, imageFile.nameWithoutExtension + ".json.tmp")

        try {
            val text = buildJson(meta)
            tmpFile.writeText(text, Charsets.UTF_8)

            if (jsonFile.exists()) jsonFile.delete()
            if (!tmpFile.renameTo(jsonFile)) {
                // fallback
                jsonFile.writeText(text, Charsets.UTF_8)
                tmpFile.delete()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed writing sidecar for ${imageFile.name}: ${t.message}", t)
            try { tmpFile.delete() } catch (_: Throwable) {}
        }
    }

    private fun buildJson(m: ImageSidecarMetadata): String {
        val sb = StringBuilder(512)
        sb.append("{\n")
        kv(sb, "timestampMs", m.timestampMs, comma = true)
        kv(sb, "timestampIso", m.timestampIso, comma = true)
        kv(sb, "filename", m.filename, comma = true)
        kv(sb, "ISO", m.iso, comma = true)
        kv(sb, "shutterNs", m.shutterNs, comma = true)
        kv(sb, "focusDistanceDiopters", m.focusDistanceDiopters, comma = true)
        kv(sb, "distanceEstimateCm", m.distanceEstimateCm, comma = true)
        kv(sb, "blurScore", m.blurScore, comma = true)
        array(sb, "exposureFlags", m.exposureFlags, comma = true)
        kv(sb, "qualityStatus", m.qualityStatus, comma = true)
        kv(sb, "lockModeUsed", m.lockModeUsed, comma = true)
        kv(sb, "torchState", m.torchState, comma = false)
        sb.append("\n}")
        return sb.toString()
    }

    private fun kv(sb: StringBuilder, key: String, value: String, comma: Boolean) {
        sb.append("  \"").append(esc(key)).append("\": \"").append(esc(value)).append("\"")
        if (comma) sb.append(",")
        sb.append("\n")
    }

    private fun kv(sb: StringBuilder, key: String, value: Int?, comma: Boolean) {
        sb.append("  \"").append(esc(key)).append("\": ")
        sb.append(value?.toString() ?: "null")
        if (comma) sb.append(",")
        sb.append("\n")
    }

    private fun kv(sb: StringBuilder, key: String, value: Long?, comma: Boolean) {
        sb.append("  \"").append(esc(key)).append("\": ")
        sb.append(value?.toString() ?: "null")
        if (comma) sb.append(",")
        sb.append("\n")
    }

    private fun kv(sb: StringBuilder, key: String, value: Float?, comma: Boolean) {
        sb.append("  \"").append(esc(key)).append("\": ")
        sb.append(value?.let { String.format(Locale.US, "%.6f", it) } ?: "null")
        if (comma) sb.append(",")
        sb.append("\n")
    }

    private fun kv(sb: StringBuilder, key: String, value: Double?, comma: Boolean) {
        sb.append("  \"").append(esc(key)).append("\": ")
        sb.append(value?.let { String.format(Locale.US, "%.6f", it) } ?: "null")
        if (comma) sb.append(",")
        sb.append("\n")
    }

    private fun kv(sb: StringBuilder, key: String, value: Double, comma: Boolean) {
        sb.append("  \"").append(esc(key)).append("\": ")
        sb.append(String.format(Locale.US, "%.6f", value))
        if (comma) sb.append(",")
        sb.append("\n")
    }

    private fun kv(sb: StringBuilder, key: String, value: Long, comma: Boolean) {
        sb.append("  \"").append(esc(key)).append("\": ").append(value)
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
