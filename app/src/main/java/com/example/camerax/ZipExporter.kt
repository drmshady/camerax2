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
 * Export an existing session folder into a ZIP in a shared/export subfolder.
 *
 * - Deterministic ordering (sorted relative paths)
 * - Safe entry names (no traversal)
 * - Writes to .tmp then renames
 * - If zip name exists, appends _01, _02, ...
 */
class ZipExporter(private val context: Context) {

    fun exportSessionToZip(sessionDir: File, sessionType: SessionType): File {
        require(sessionDir.exists() && sessionDir.isDirectory) {
            "Session directory does not exist: ${sessionDir.absolutePath}"
        }

        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val exportRoot = File(baseDir, "shared/export").apply { mkdirs() }

        val subFolder = when (sessionType) {
            SessionType.CALIBRATION -> "calibration"
            SessionType.CAPTURE -> "capture"
        }
        val exportDir = File(exportRoot, subFolder).apply { mkdirs() }

        val baseZipName = sessionDir.name + ".zip"
        val zipFile = uniqueFile(exportDir, baseZipName)
        val tmpFile = File(exportDir, zipFile.name + ".tmp")

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

    private fun uniqueFile(dir: File, filename: String): File {
        val base = filename.removeSuffix(".zip")
        var candidate = File(dir, filename)
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_${String.format("%02d", i)}.zip")
            i++
        }
        return candidate
    }

    private fun isSafeEntryName(name: String): Boolean {
        if (name.startsWith("/")) return false
        val parts = name.split('/')
        return parts.none { it == ".." || it.isEmpty() }
    }

    private companion object {
        private const val TAG = "ZipExporter"
        private const val DEFAULT_BUFFER = 16 * 1024
    }
}
