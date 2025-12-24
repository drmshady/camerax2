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

<<<<<<< HEAD
    // Device rotation at capture time (0/90/180/270)
    val deviceRotationDegrees: Int = 0
=======
    // ---- NEW: truth fields (merge into existing JSON) ----
    val savedWidth: Int? = null,
    val savedHeight: Int? = null,
    val exifOrientation: Int? = null,          // EXIF tag value 1..8
    val deviceRotationDegrees: Int? = null,    // 0/90/180/270 at capture time
    val rotationDegreesApplied: Int? = null    // pixel-rotation applied by app (usually 0)
>>>>>>> d21a7b094031335437223262a15276636a5ec8ac
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

<<<<<<< HEAD
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
=======
    private fun readExistingJson(jsonFile: File): JSONObject {
        if (!jsonFile.exists()) return JSONObject()
        return try {
            val txt = jsonFile.readText(Charsets.UTF_8)
            val v = JSONTokener(txt).nextValue()
            if (v is JSONObject) v else JSONObject()
        } catch (_: Throwable) {
            JSONObject()
        }
>>>>>>> d21a7b094031335437223262a15276636a5ec8ac
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

<<<<<<< HEAD
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

=======
>>>>>>> d21a7b094031335437223262a15276636a5ec8ac
    private companion object {
        private const val TAG = "ImageSidecarWriter"
    }
}
