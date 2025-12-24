package com.example.camerax

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Phase 2 LAN transfer: uploads a ZIP file to the PC receiver over HTTP.
 *
 * This class was referenced by the Activities but was missing in some project states.
 * Kept minimal and IO-friendly; call from Dispatchers.IO.
 */
class TransferClient {

    fun uploadZip(
        host: String,
        port: Int = 8080,
        zipFile: File,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) {
        require(zipFile.exists()) { "ZIP not found: ${zipFile.absolutePath}" }
        val total = zipFile.length().coerceAtLeast(0L)

        val endpoints = listOf("/upload", "/uploadZip", "/")
        var lastError: Throwable? = null

        for (ep in endpoints) {
            try {
                val url = buildUrl(host, port, ep)
                val resp = uploadMultipart(url, zipFile, total, onProgress)
                // Accept 200..299 as success
                if (resp in 200..299) return
                // If 404, try next endpoint; otherwise fail fast
                if (resp != 404) {
                    throw RuntimeException("HTTP $resp from $url")
                }
            } catch (t: Throwable) {
                lastError = t
                // try next endpoint
            }
        }

        throw lastError ?: RuntimeException("Upload failed (unknown error).")
    }

    private fun buildUrl(hostRaw: String, port: Int, path: String): URL {
        val host = hostRaw.trim().removePrefix("http://").removePrefix("https://")
        val p = if (path.startsWith("/")) path else "/$path"
        return URL("http://$host:$port$p")
    }

    private fun uploadMultipart(
        url: URL,
        zipFile: File,
        total: Long,
        onProgress: (Long, Long) -> Unit
    ): Int {
        val boundary = "----cameraxBoundary${System.currentTimeMillis()}"
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            useCaches = false
            connectTimeout = 15000
            readTimeout = 60000
            setRequestProperty("Connection", "Keep-Alive")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        var sent = 0L
        BufferedOutputStream(conn.outputStream).use { out ->
            // Part header
            val header = StringBuilder()
                .append(twoHyphens).append(boundary).append(lineEnd)
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"${zipFile.name}\"")
                .append(lineEnd)
                .append("Content-Type: application/zip")
                .append(lineEnd)
                .append(lineEnd)
                .toString()

            out.write(header.toByteArray(Charsets.UTF_8))

            // File bytes
            BufferedInputStream(FileInputStream(zipFile)).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    sent += n.toLong()
                    onProgress(sent, total)
                }
            }

            out.write(lineEnd.toByteArray(Charsets.UTF_8))

            // Close boundary
            val footer = (twoHyphens + boundary + twoHyphens + lineEnd)
            out.write(footer.toByteArray(Charsets.UTF_8))
            out.flush()
        }

        val code = conn.responseCode
        // Consume response stream (avoid leaked connections)
        try {
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.use { it.readText() }
        } catch (_: Throwable) {
            // ignore
        } finally {
            conn.disconnect()
        }

        return code
    }
}
