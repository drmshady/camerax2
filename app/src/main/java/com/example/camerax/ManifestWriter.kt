package com.example.camerax

import org.json.JSONObject
import java.io.File

class ManifestWriter {

    fun writeManifest(sessionInfo: Map<String, Any?>, directory: File) {
        val manifestFile = File(directory, "manifest.json")
        val tmpFile = File(directory, "manifest.json.tmp")

        val json = JSONObject().apply {
            sessionInfo.forEach { (k, v) ->
                // Keep deterministic keys; write nulls explicitly
                put(k, v ?: JSONObject.NULL)
            }
        }

        try {
            tmpFile.writeText(json.toString(4), Charsets.UTF_8)
            if (manifestFile.exists()) manifestFile.delete()
            if (!tmpFile.renameTo(manifestFile)) {
                // fallback if rename fails on some devices
                manifestFile.writeText(json.toString(4), Charsets.UTF_8)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try { tmpFile.delete() } catch (_: Throwable) {}
        }
    }
}
