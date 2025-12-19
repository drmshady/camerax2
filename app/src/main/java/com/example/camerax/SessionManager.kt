package com.example.camerax

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SessionType { CAPTURE, CALIBRATION }

data class SessionMeta(
    val type: SessionType,
    val doctorName: String? = null,
    val patientName: String? = null,
    val patientId: String? = null,
    val calibrationTargetDistanceCm: Int? = null
)

class SessionManager(private val context: Context) {

    private var sessionStartTime: Long = 0L
    private var sessionName: String = ""
    private var imageCount: Int = 0
    private var sessionActive: Boolean = false
    private var sessionMeta: SessionMeta? = null

    private lateinit var sessionDirectory: File
    private lateinit var imagesDirectory: File

    fun startSession(meta: SessionMeta) {
        sessionMeta = meta
        sessionStartTime = System.currentTimeMillis()
        imageCount = 0
        sessionActive = true

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(sessionStartTime))
        sessionName = when (meta.type) {
            SessionType.CALIBRATION -> {
                val wd = meta.calibrationTargetDistanceCm ?: 0
                "CALIB_${ts}_WD${wd}cm"
            }
            SessionType.CAPTURE -> "SESSION_${ts}"
        }

        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val sessionsRoot = File(baseDir, "sessions").apply { mkdirs() }

        sessionDirectory = File(sessionsRoot, sessionName).apply { mkdirs() }
        imagesDirectory = File(sessionDirectory, "images").apply { mkdirs() }
    }

    fun endSession() {
        sessionActive = false
    }

    fun isSessionActive(): Boolean = sessionActive

    fun hasSession(): Boolean = this::sessionDirectory.isInitialized && sessionDirectory.exists()

    fun getSessionName(): String = sessionName

    fun getSessionType(): SessionType = sessionMeta?.type ?: SessionType.CAPTURE

    fun getSessionMeta(): SessionMeta? = sessionMeta

    fun getNextImageFile(): File {
        imageCount++
        val imageFileName = "img_${String.format("%04d", imageCount)}.jpg"
        return File(imagesDirectory, imageFileName)
    }

    fun getSessionDirectory(): File = sessionDirectory

    fun getSessionInfo(): Map<String, Any?> {
        val info = linkedMapOf<String, Any?>(
            "sessionName" to sessionName,
            "sessionType" to getSessionType().name,
            "sessionStartTime" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(sessionStartTime)),
            "deviceModel" to Build.MODEL,
            "androidVersion" to Build.VERSION.RELEASE,
            "totalImages" to imageCount
        )

        val meta = sessionMeta
        if (meta != null) {
            if (meta.type == SessionType.CAPTURE) {
                info["doctorName"] = meta.doctorName
                info["patientName"] = meta.patientName
                info["patientId"] = meta.patientId
            } else {
                info["calibrationTargetDistanceCm"] = meta.calibrationTargetDistanceCm
            }
        }
        return info
    }
}
