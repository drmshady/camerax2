package com.example.camerax

import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.Locale

class ManifestWriter {

    /**
     * Writes manifest.json deterministically:
     * - stable key ordering (sorted recursively)
     * - pretty-printed JSON (2 spaces)
     * - atomic-ish write (tmp then rename; fallback copy+delete)
     * - merges with existing manifest to preserve unknown fields
     */
    fun writeManifest(sessionInfo: Map<String, Any?>, directory: File) {
        if (!directory.exists()) {
            throw IOException("Directory does not exist: ${directory.absolutePath}")
        }
        val manifestFile = File(directory, "manifest.json")
        val tmpFile = File(directory, "manifest.json.tmp")

        // Merge with existing manifest to preserve unknown fields
        val merged = if (manifestFile.exists()) {
            try {
                val existing = parseJsonObject(manifestFile.readText(Charsets.UTF_8))
                // sessionInfo takes precedence, but preserve existing keys not in sessionInfo
                existing.putAll(sessionInfo)
                existing
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse existing manifest, will overwrite: ${e.message}")
                sessionInfo
            }
        } else {
            sessionInfo
        }

        val json = toJsonDeterministic(merged)

        // write tmp then replace
        tmpFile.writeText(json, Charsets.UTF_8)
        replaceFile(tmpFile, manifestFile)
    }

    /**
     * Updates manifest.json with image metadata from a captured JPEG.
     * Should be called after each image is saved.
     *
     * Adds/updates:
     * - savedImageSize: { width, height }
     * - savedImageResolution: "WxH"
     * - exifOrientationStandard: int
     * - deviceRotationDegrees: int (first capture value or updates if already set)
     * - intrinsicsKey: "<MODEL>_<lens>_<WxH>" (lens placeholder for now)
     * - imageSizesSeen: array of unique {w,h} objects
     * - resolutionMismatch: true if more than one unique size seen
     *
     * Must be called on background thread.
     *
     * @param directory Session directory containing manifest.json
     * @param imageFile The saved JPEG file
     * @param deviceRotationDegrees Device rotation at capture time (0/90/180/270)
     */
    fun updateManifestWithImageMetadata(
        directory: File,
        imageFile: File,
        deviceRotationDegrees: Int
    ) {
        if (!directory.exists()) {
            Log.w(TAG, "Directory does not exist: ${directory.absolutePath}")
            return
        }

        val manifestFile = File(directory, "manifest.json")
        val tmpFile = File(directory, "manifest.json.tmp")

        try {
            // Read image metadata
            val (width, height) = ImageSizeUtil.readSavedJpegSize(imageFile.absolutePath)
            val orientation = ImageSizeUtil.readExifOrientation(imageFile.absolutePath)

            if (width <= 0 || height <= 0) {
                Log.w(TAG, "Invalid image size ${width}x${height} for ${imageFile.name}")
                return
            }

            // Parse existing manifest
            val manifest = if (manifestFile.exists()) {
                parseJsonObject(manifestFile.readText(Charsets.UTF_8))
            } else {
                linkedMapOf()
            }

            // Update saved image size
            manifest["savedImageSize"] = linkedMapOf("width" to width, "height" to height)
            manifest["savedImageResolution"] = "${width}x${height}"
            manifest["exifOrientationStandard"] = orientation

            // Set deviceRotationDegrees (use first capture value or update)
            if (!manifest.containsKey("deviceRotationDegrees")) {
                manifest["deviceRotationDegrees"] = deviceRotationDegrees
            }

            // Build intrinsicsKey: "<MODEL>_<lens>_<WxH>"
            val modelSanitized = sanitizeForKey(Build.MODEL)
            val lensPlaceholder = "main" // TODO: derive from camera ID or lens info
            manifest["intrinsicsKey"] = "${modelSanitized}_${lensPlaceholder}_${width}x${height}"

            // Track unique image sizes
            @Suppress("UNCHECKED_CAST")
            val existingSizes = manifest["imageSizesSeen"] as? List<Map<String, Any?>> ?: emptyList()
            val sizesList = existingSizes.toMutableList()

            val newSize = mapOf("w" to width, "h" to height)
            val alreadyExists = sizesList.any { size ->
                (size["w"] as? Number)?.toInt() == width &&
                (size["h"] as? Number)?.toInt() == height
            }

            if (!alreadyExists) {
                sizesList.add(newSize)
            }

            manifest["imageSizesSeen"] = sizesList

            // Check for resolution mismatch
            val uniqueSizes = sizesList.distinctBy {
                val w = (it["w"] as? Number)?.toInt() ?: 0
                val h = (it["h"] as? Number)?.toInt() ?: 0
                "$w,$h"
            }
            manifest["resolutionMismatch"] = uniqueSizes.size > 1

            // Write back
            val json = toJsonDeterministic(manifest)
            tmpFile.writeText(json, Charsets.UTF_8)
            replaceFile(tmpFile, manifestFile)

        } catch (t: Throwable) {
            Log.e(TAG, "Failed updating manifest with image metadata: ${t.message}", t)
            try { tmpFile.delete() } catch (_: Throwable) {}
        }
    }

    private fun sanitizeForKey(input: String): String {
        // Replace non-alphanumeric with underscore, collapse multiple underscores
        return input.replace(Regex("[^a-zA-Z0-9]+"), "_")
            .trim('_')
            .take(32) // limit length
            .ifEmpty { "unknown" }
    }

    private fun replaceFile(tmp: File, target: File) {
        // Best-effort atomic replace; Windows can be tricky.
        if (target.exists()) {
            // Try delete first (Windows rename-over-existing can fail)
            if (!target.delete()) {
                // If delete failed, try rename anyway (some FS allow it)
            }
        }
        if (!tmp.renameTo(target)) {
            // Fallback: copy then delete tmp
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun toJsonDeterministic(value: Any?): String {
        val sb = StringBuilder(32 * 1024)
        writeJsonValue(sb, value, 0)
        sb.append("\n")
        return sb.toString()
    }

    private fun writeJsonValue(sb: StringBuilder, value: Any?, level: Int) {
        when (value) {
            null -> sb.append("null")
            is String -> sb.append('"').append(escapeJsonString(value)).append('"')
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Float -> writeNumber(sb, value.toDouble())
            is Double -> writeNumber(sb, value)
            is Number -> sb.append(value.toString())

            is Map<*, *> -> {
                val entries = value.entries
                    .mapNotNull { e ->
                        val k = e.key?.toString() ?: return@mapNotNull null
                        k to e.value
                    }
                    .sortedBy { it.first }

                sb.append("{")
                if (entries.isNotEmpty()) sb.append("\n")
                for ((idx, kv) in entries.withIndex()) {
                    indent(sb, level + 1)
                    sb.append('"').append(escapeJsonString(kv.first)).append('"')
                    sb.append(": ")
                    writeJsonValue(sb, kv.second, level + 1)
                    if (idx != entries.size - 1) sb.append(",")
                    sb.append("\n")
                }
                if (entries.isNotEmpty()) indent(sb, level)
                sb.append("}")
            }

            is Iterable<*> -> {
                val list = value.toList() // keep iteration order
                sb.append("[")
                if (list.isNotEmpty()) sb.append("\n")
                for (i in list.indices) {
                    indent(sb, level + 1)
                    writeJsonValue(sb, list[i], level + 1)
                    if (i != list.size - 1) sb.append(",")
                    sb.append("\n")
                }
                if (list.isNotEmpty()) indent(sb, level)
                sb.append("]")
            }

            is Array<*> -> {
                val list = value.toList()
                sb.append("[")
                if (list.isNotEmpty()) sb.append("\n")
                for (i in list.indices) {
                    indent(sb, level + 1)
                    writeJsonValue(sb, list[i], level + 1)
                    if (i != list.size - 1) sb.append(",")
                    sb.append("\n")
                }
                if (list.isNotEmpty()) indent(sb, level)
                sb.append("]")
            }

            else -> {
                // Fallback: encode as string to avoid crashing on unknown types
                sb.append('"').append(escapeJsonString(value.toString())).append('"')
            }
        }
    }

    private fun writeNumber(sb: StringBuilder, d: Double) {
        // JSON does not allow NaN/Infinity
        if (d.isNaN() || d.isInfinite()) {
            sb.append("null")
            return
        }
        // Use Locale.US to ensure '.' decimal separator
        val s = if (kotlin.math.abs(d - d.toLong().toDouble()) < 1e-12) {
            d.toLong().toString()
        } else {
            // Keep reasonable precision, avoid scientific notation where possible
            String.format(Locale.US, "%.12f", d).trimEnd('0').trimEnd('.')
        }
        sb.append(s)
    }

    private fun indent(sb: StringBuilder, level: Int) {
        repeat(level) { sb.append("  ") } // 2 spaces
    }

    private fun escapeJsonString(input: String): String {
        val sb = StringBuilder(input.length + 16)
        for (ch in input) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }

    // ---- JSON Parsing for Merging ----

    /**
     * Simple JSON object parser to preserve existing manifest fields.
     * Returns a LinkedHashMap to maintain insertion order.
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
            val key = unescapeJsonString(content.substring(keyStart, i))
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
                val str = unescapeJsonString(json.substring(start, i))
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
                val num = try {
                    if ('.' in numStr || 'e' in numStr || 'E' in numStr) {
                        numStr.toDouble()
                    } else {
                        numStr.toLong()
                    }
                } catch (e: NumberFormatException) {
                    null
                }
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

    private fun unescapeJsonString(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'u' -> {
                        // Unicode escape
                        if (i + 5 < s.length) {
                            try {
                                val code = s.substring(i + 2, i + 6).toInt(16)
                                sb.append(code.toChar())
                                i += 6
                            } catch (e: Exception) {
                                sb.append(s[i])
                                i++
                            }
                        } else {
                            sb.append(s[i])
                            i++
                        }
                    }
                    else -> {
                        sb.append(s[i])
                        i++
                    }
                }
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }

    private companion object {
        private const val TAG = "ManifestWriter"
    }
}
