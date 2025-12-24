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
    val markerSummary: Any? = null,

    // Device rotation at capture time (0/90/180/270)
    val deviceRotationDegrees: Int = 0
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

    /**
     * Updates the sidecar JSON by merging in image metadata from the saved JPEG.
     * This should be called AFTER the JPEG is saved and the initial sidecar JSON is written.
     *
     * Preserves all existing keys and adds/updates:
     * - savedWidth, savedHeight (from JPEG)
     * - exifOrientation (from JPEG EXIF)
     * - deviceRotationDegrees (from parameter)
     * - rotationDegreesApplied (always 0, we don't rotate pixels)
     * - image_size (legacy array format)
     *
     * Must be called on a background thread/executor.
     *
     * @param imageFile The saved JPEG file
     * @param deviceRotationDegrees Device rotation at capture time (0/90/180/270)
     */
    fun updateSidecarWithImageMetadata(imageFile: File, deviceRotationDegrees: Int) {
        val jsonFile = File(imageFile.parentFile, imageFile.nameWithoutExtension + ".json")
        val tmpFile = File(imageFile.parentFile, imageFile.nameWithoutExtension + ".json.tmp")

        try {
            // Read actual dimensions and orientation from saved JPEG
            val (width, height) = ImageSizeUtil.readSavedJpegSize(imageFile.absolutePath)
            val orientation = ImageSizeUtil.readExifOrientation(imageFile.absolutePath)

            // Parse existing JSON to preserve all keys
            val existingMap = if (jsonFile.exists()) {
                parseJsonObject(jsonFile.readText(Charsets.UTF_8))
            } else {
                linkedMapOf()
            }

            // Add/update new keys (merge, don't overwrite unknown keys)
            existingMap["savedWidth"] = width
            existingMap["savedHeight"] = height
            existingMap["exifOrientation"] = orientation
            existingMap["deviceRotationDegrees"] = deviceRotationDegrees
            existingMap["rotationDegreesApplied"] = 0

            // Update legacy image_size if we have valid dimensions
            if (width > 0 && height > 0) {
                existingMap["image_size"] = listOf(width, height)
            }
            // intrinsicsKey is preserved automatically (we don't touch it if it exists)

            // Write back deterministically (sorted keys)
            val text = buildJsonFromMap(existingMap)
            tmpFile.writeText(text, Charsets.UTF_8)

            if (jsonFile.exists()) jsonFile.delete()
            if (!tmpFile.renameTo(jsonFile)) {
                jsonFile.writeText(text, Charsets.UTF_8)
                tmpFile.delete()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed updating sidecar with image metadata for ${imageFile.name}: ${t.message}", t)
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

    // ---- JSON Parsing and Merging ----

    /**
     * Parses a JSON object string into a LinkedHashMap.
     * Preserves insertion order for deterministic output.
     * Simple recursive descent parser for our specific JSON format.
     */
    private fun parseJsonObject(json: String): LinkedHashMap<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        val trimmed = json.trim()

        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            Log.w(TAG, "Invalid JSON object format")
            return result
        }

        val content = trimmed.substring(1, trimmed.length - 1).trim()
        if (content.isEmpty()) return result

        var i = 0
        while (i < content.length) {
            // Skip whitespace
            while (i < content.length && content[i].isWhitespace()) i++
            if (i >= content.length) break

            // Parse key (must be quoted string)
            if (content[i] != '"') break
            i++ // skip opening quote
            val keyStart = i
            while (i < content.length && content[i] != '"') {
                if (content[i] == '\\') i++ // skip escaped char
                i++
            }
            val key = unescapeJson(content.substring(keyStart, i))
            i++ // skip closing quote

            // Skip whitespace and colon
            while (i < content.length && (content[i].isWhitespace() || content[i] == ':')) i++

            // Parse value
            val (value, newIndex) = parseJsonValue(content, i)
            result[key] = value
            i = newIndex

            // Skip whitespace and comma
            while (i < content.length && (content[i].isWhitespace() || content[i] == ',')) i++
        }

        return result
    }

    private fun parseJsonValue(json: String, startIndex: Int): Pair<Any?, Int> {
        var i = startIndex
        while (i < json.length && json[i].isWhitespace()) i++

        if (i >= json.length) return Pair(null, i)

        return when (json[i]) {
            '"' -> {
                // String
                i++ // skip opening quote
                val start = i
                while (i < json.length && json[i] != '"') {
                    if (json[i] == '\\') i++ // skip escaped char
                    i++
                }
                val str = unescapeJson(json.substring(start, i))
                i++ // skip closing quote
                Pair(str, i)
            }
            '[' -> {
                // Array
                val list = mutableListOf<Any?>()
                i++ // skip [
                while (i < json.length && json[i].isWhitespace()) i++
                if (i < json.length && json[i] != ']') {
                    while (true) {
                        val (item, newIndex) = parseJsonValue(json, i)
                        list.add(item)
                        i = newIndex
                        while (i < json.length && json[i].isWhitespace()) i++
                        if (i >= json.length || json[i] == ']') break
                        if (json[i] == ',') {
                            i++
                            while (i < json.length && json[i].isWhitespace()) i++
                        }
                    }
                }
                if (i < json.length && json[i] == ']') i++
                Pair(list, i)
            }
            '{' -> {
                // Nested object
                val endIndex = findMatchingBrace(json, i)
                val nestedJson = json.substring(i, endIndex + 1)
                val nestedMap = parseJsonObject(nestedJson)
                Pair(nestedMap, endIndex + 1)
            }
            't', 'f' -> {
                // Boolean
                if (json.startsWith("true", i)) {
                    Pair(true, i + 4)
                } else if (json.startsWith("false", i)) {
                    Pair(false, i + 5)
                } else {
                    Pair(null, i)
                }
            }
            'n' -> {
                // null
                if (json.startsWith("null", i)) {
                    Pair(null, i + 4)
                } else {
                    Pair(null, i)
                }
            }
            else -> {
                // Number
                val start = i
                if (json[i] == '-') i++
                while (i < json.length && (json[i].isDigit() || json[i] == '.' || json[i] == 'e' || json[i] == 'E' || json[i] == '+' || json[i] == '-')) i++
                val numStr = json.substring(start, i)
                val num = numStr.toDoubleOrNull() ?: numStr.toLongOrNull()
                Pair(num, i)
            }
        }
    }

    private fun findMatchingBrace(json: String, startIndex: Int): Int {
        var depth = 0
        var i = startIndex
        while (i < json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
                '"' -> {
                    // Skip quoted strings
                    i++
                    while (i < json.length && json[i] != '"') {
                        if (json[i] == '\\') i++
                        i++
                    }
                }
            }
            i++
        }
        return json.length - 1
    }

    private fun unescapeJson(s: String): String {
        return s.replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }

    /**
     * Builds deterministic JSON from a map (sorted keys, pretty-printed).
     */
    private fun buildJsonFromMap(map: Map<String, Any?>): String {
        val sb = StringBuilder(2048)
        sb.append("{\n")

        val sortedKeys = map.keys.sorted()
        for ((index, key) in sortedKeys.withIndex()) {
            val value = map[key]
            sb.append("  \"").append(esc(key)).append("\": ")
            writeAny(sb, value, indent = "  ")
            if (index != sortedKeys.size - 1) sb.append(",")
            sb.append("\n")
        }

        sb.append("}")
        return sb.toString()
    }

    private companion object {
        private const val TAG = "ImageSidecarWriter"
    }
}
