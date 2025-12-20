package com.example.camerax

import java.io.File
import java.io.IOException
import java.util.Locale

class ManifestWriter {

    /**
     * Writes manifest.json deterministically:
     * - stable key ordering (sorted recursively)
     * - pretty-printed JSON (2 spaces)
     * - atomic-ish write (tmp then rename; fallback copy+delete)
     */
    fun writeManifest(sessionInfo: Map<String, Any?>, directory: File) {
        if (!directory.exists()) {
            throw IOException("Directory does not exist: ${directory.absolutePath}")
        }
        val manifestFile = File(directory, "manifest.json")
        val tmpFile = File(directory, "manifest.json.tmp")

        val json = toJsonDeterministic(sessionInfo)

        // write tmp then replace
        tmpFile.writeText(json, Charsets.UTF_8)
        replaceFile(tmpFile, manifestFile)
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
}
