package com.example.camerax

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionManager(private val context: Context) {

    private var sessionStartTime: Long = 0
    private var sessionName: String = ""
    private var imageCount: Int = 0
    private var sessionActive = false
    private lateinit var sessionDirectory: File
    private lateinit var imagesDirectory: File

    fun startSession() {
        sessionStartTime = System.currentTimeMillis()
        sessionName = "SESSION_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(sessionStartTime))}"
        imageCount = 0
        sessionActive = true

        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val sessionsRoot = File(baseDir, "sessions").apply { mkdirs() }

        sessionDirectory = File(sessionsRoot, sessionName)
        imagesDirectory = File(sessionDirectory, "images")
        sessionDirectory.mkdirs()
        imagesDirectory.mkdirs()
    }

    fun endSession() {
        sessionActive = false
    }

    fun isSessionActive(): Boolean {
        return sessionActive
    }

    fun getNextImageFile(): File {
        imageCount++
        val imageFileName = "img_${String.format("%04d", imageCount)}.jpg"
        return File(imagesDirectory, imageFileName)
    }

    fun hasSession(): Boolean {
        return this::sessionDirectory.isInitialized && sessionDirectory.exists()
    }

    fun getSessionName(): String {
        return sessionName
    }

    fun getSessionInfo(): Map<String, Any> {
        return mapOf(
            "sessionStartTime" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(sessionStartTime)),
            "deviceModel" to Build.MODEL,
            "androidVersion" to Build.VERSION.RELEASE,
            "totalImages" to imageCount
        )
    }

    fun getSessionDirectory(): File {
        return sessionDirectory
    }
}
