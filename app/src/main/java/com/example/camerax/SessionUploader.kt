package com.example.camerax

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

class SessionUploader(private val context: Context) {

    data class UploadMeta(
        val sessionType: String,
        val sessionName: String,
        val zipFileName: String,
        val fileSize: Long,
        val sha256: String
    ) {
        fun toJsonString(): String {
            val o = JSONObject()
            o.put("sessionType", sessionType)
            o.put("sessionName", sessionName)
            o.put("zipFileName", zipFileName)
            o.put("fileSize", fileSize)
            o.put("sha256", sha256)
            return o.toString()
        }
    }

    data class UploadResult(
        val success: Boolean,
        val httpCode: Int,
        val responseBody: String,
        val errorMessage: String? = null
    )

    suspend fun upload(
        url: String,
        zipFile: File,
        meta: UploadMeta,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit
    ): UploadResult = withContext(Dispatchers.IO) {
        if (!zipFile.exists()) {
            return@withContext UploadResult(false, -1, "", "ZIP not found: ${zipFile.absolutePath}")
        }

        val boundary = "----camerax2-${UUID.randomUUID()}"
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            useCaches = false
            connectTimeout = 12_000
            readTimeout = 60_000
            setRequestProperty("Connection", "Keep-Alive")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        try {
            val total = zipFile.length().coerceAtLeast(1L)
            var sent = 0L

            BufferedOutputStream(conn.outputStream).use { out ->
                fun writeString(s: String) = out.write(s.toByteArray(Charsets.UTF_8))

                fun writeTextPart(name: String, value: String) {
                    writeString(twoHyphens + boundary + lineEnd)
                    writeString("""Content-Disposition: form-data; name="$name"$lineEnd""")
                    writeString("""Content-Type: text/plain; charset=UTF-8$lineEnd""")
                    writeString(lineEnd)
                    writeString(value)
                    writeString(lineEnd)
                }

                // meta JSON
                writeString(twoHyphens + boundary + lineEnd)
                writeString("""Content-Disposition: form-data; name="meta"$lineEnd""")
                writeString("""Content-Type: application/json; charset=UTF-8$lineEnd""")
                writeString(lineEnd)
                writeString(meta.toJsonString())
                writeString(lineEnd)

                // redundant fields (compat)
                writeTextPart("sessionType", meta.sessionType)
                writeTextPart("sessionName", meta.sessionName)
                writeTextPart("zipFileName", meta.zipFileName)
                writeTextPart("fileSize", meta.fileSize.toString())
                writeTextPart("sha256", meta.sha256)

                // file part
                writeString(twoHyphens + boundary + lineEnd)
                writeString("""Content-Disposition: form-data; name="file"; filename="${zipFile.name}"$lineEnd""")
                writeString("""Content-Type: application/zip$lineEnd""")
                writeString("""Content-Transfer-Encoding: binary$lineEnd""")
                writeString(lineEnd)

                val buffer = ByteArray(64 * 1024)
                BufferedInputStream(FileInputStream(zipFile)).use { input ->
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        sent += read.toLong()
                        onProgress(sent, total)
                    }
                }

                writeString(lineEnd)
                writeString(twoHyphens + boundary + twoHyphens + lineEnd)
                out.flush()
            }

            val code = conn.responseCode
            val body = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            } catch (_: Throwable) {
                ""
            }

            UploadResult(code in 200..299, code, body, if (code in 200..299) null else "HTTP $code")
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "Upload failed: ${t.message}", t)
            UploadResult(false, -1, "", t.message ?: "Upload error")
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val TAG = "SessionUploader"

        fun computeSha256(file: File, shouldContinue: () -> Boolean = { true }): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(256 * 1024)
            FileInputStream(file).use { fis ->
                while (true) {
                    if (!shouldContinue()) throw CancellationException("SHA256 canceled")
                    val r = fis.read(buf)
                    if (r <= 0) break
                    md.update(buf, 0, r)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun markSessionSent(
            context: Context,
            meta: UploadMeta,
            url: String,
            response: String,
            httpCode: Int
        ) {
            try {
                val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
                val dir = File(baseDir, "shared/sent").apply { mkdirs() }
                val f = File(dir, "${meta.sessionName}_${meta.sha256.take(12)}.json")

                val o = JSONObject()
                o.put("timeMs", System.currentTimeMillis())
                o.put("url", url)
                o.put("httpCode", httpCode)
                o.put("meta", JSONObject(meta.toJsonString()))
                o.put("response", response)

                f.writeText(o.toString(2))
            } catch (t: Throwable) {
                Log.w(TAG, "markSessionSent failed: ${t.message}")
            }
        }
    }
}
