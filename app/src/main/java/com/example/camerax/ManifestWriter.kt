package com.example.camerax

import org.json.JSONObject
import java.io.File

class ManifestWriter {

    fun writeManifest(sessionInfo: Map<String, Any>, directory: File) {
        val manifestFile = File(directory, "manifest.json")
        val tmpFile = File(directory, "manifest.json.tmp")

        val json = JSONObject().apply {
            sessionInfo.forEach { (k, v) -> put(k, v) }
        }

        try {
            tmpFile.writeText(json.toString(4), Charsets.UTF_8)
            if (manifestFile.exists()) manifestFile.delete()
            tmpFile.renameTo(manifestFile)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}