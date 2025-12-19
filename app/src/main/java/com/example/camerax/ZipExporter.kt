package com.example.camerax

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Step 7: Export an existing SESSION_... folder into SESSION_....zip.
 *
 * Output location:
 *   <app external files>/shared/export/SESSION_....zip
 *
 * - Safe zip entry names (prevents path traversal entries)
 * - Deterministic ordering (sorted by relative path)
 * - Writes to .tmp then renames
 */
class ZipExporter(private val context: Context) {

    fun exportSessionToZip(sessionDir: File): File {
        require(sessionDir.exists() && sessionDir.isDirectory) {
            "Session directory does not exist: ${sessionDir.absolutePath}"
        }

        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val exportDir = File(File(baseDir, "shared"), "export").apply { mkdirs() }

        val zipName = sessionDir.name + ".zip"
        val zipFile = File(exportDir, zipName)
        val tmpFile = File(exportDir, "$zipName.tmp")

        val basePath = sessionDir.toPath()
        val files = sessionDir.walkTopDown()
            .filter { it.isFile }
            .toList()
            .sortedBy { basePath.relativize(it.toPath()).toString() }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(tmpFile))).use { zos ->
            zos.setLevel(Deflater.BEST_COMPRESSION)

            val buffer = ByteArray(DEFAULT_BUFFER)
            for (f in files) {
                val rel = basePath.relativize(f.toPath()).toString().replace(File.separatorChar, '/')
                val entryName = "${sessionDir.name}/$rel"

                if (!isSafeEntryName(entryName)) {
                    Log.w(TAG, "Skipping unsafe entry name: $entryName")
                    continue
                }

                val entry = ZipEntry(entryName).apply {
                    time = f.lastModified()
                    size = f.length()
                }

                zos.putNextEntry(entry)
                BufferedInputStream(FileInputStream(f)).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        zos.write(buffer, 0, read)
                    }
                }
                zos.closeEntry()
            }
        }

        if (zipFile.exists()) zipFile.delete()
        if (!tmpFile.renameTo(zipFile)) {
            zipFile.outputStream().use { out ->
                tmpFile.inputStream().use { it.copyTo(out) }
            }
            tmpFile.delete()
        }

        return zipFile
    }

    private fun isSafeEntryName(name: String): Boolean {
        if (name.startsWith("/")) return false
        if (name.contains("\\\\")) return false
        val parts = name.split('/')
        return parts.none { it == ".." || it.isEmpty() }
    }

    private companion object {
        private const val TAG = "ZipExporter"
        private const val DEFAULT_BUFFER = 16 * 1024
    }
}
