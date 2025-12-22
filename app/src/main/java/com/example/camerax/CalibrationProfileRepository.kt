package com.example.camerax

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

data class CalibrationProfile(
    val sessionName: String,
    val sessionDir: File,
    val chosenResolution: String? = null,
    val lockedFocusDistanceDiopters: Float? = null,
    val createdTimestampMs: Long? = null
) {
    fun displayLabel(): String {
        val res = chosenResolution?.let { " • $it" } ?: ""
        val fd = lockedFocusDistanceDiopters?.let { " • fd=${(it * 100).roundToInt()/100.0}" } ?: ""
        return "$sessionName$res$fd"
    }
}

object CalibrationProfileRepository {

    fun listCalibrationProfiles(context: Context): List<CalibrationProfile> {
        val roots = findSessionRoots(context)
        val found = mutableListOf<CalibrationProfile>()

        for (root in roots) {
            val children = root.listFiles()?.filter { it.isDirectory } ?: continue
            for (dir in children) {
                val manifest = File(dir, "manifest.json")
                if (!manifest.exists()) continue
                val json = runCatching { JSONObject(manifest.readText(Charsets.UTF_8)) }.getOrNull() ?: continue

                val type = (json.optString("sessionType", json.optString("type", ""))).trim().uppercase()
                if (type != "CALIBRATION") continue

                val chosenResolution = json.optString("chosenResolution", null).takeIf { !it.isNullOrBlank() }
                val lockedFd = json.optDouble("lockedFocusDistanceDiopters", Double.NaN)
                    .takeIf { !it.isNaN() }?.toFloat()

                val createdTs = json.optLong("timestampMs", 0L).takeIf { it > 0L }
                    ?: parseTimestampFromName(dir.name)

                found.add(
                    CalibrationProfile(
                        sessionName = dir.name,
                        sessionDir = dir,
                        chosenResolution = chosenResolution,
                        lockedFocusDistanceDiopters = lockedFd,
                        createdTimestampMs = createdTs
                    )
                )
            }
        }

        return found.sortedWith(compareByDescending<CalibrationProfile> { it.createdTimestampMs ?: 0L }.thenByDescending { it.sessionName })
    }

    private fun findSessionRoots(context: Context): List<File> {
        val roots = mutableListOf<File>()

        // Common locations (keep lightweight, deterministic)
        val internalCandidates = listOf(
            File(context.filesDir, "sessions"),
            File(context.filesDir, "Sessions"),
            File(context.filesDir, "photogrammetry_sessions")
        )
        internalCandidates.filter { it.exists() && it.isDirectory }.forEach { roots.add(it) }

        context.getExternalFilesDir(null)?.let { ext ->
            val externalCandidates = listOf(
                File(ext, "sessions"),
                File(ext, "Sessions"),
                File(ext, "photogrammetry_sessions")
            )
            externalCandidates.filter { it.exists() && it.isDirectory }.forEach { roots.add(it) }
        }

        // If none found, fall back to scanning immediate children for any directory containing manifest.json files.
        if (roots.isEmpty()) {
            val fallbacks = listOfNotNull(context.filesDir, context.getExternalFilesDir(null))
            for (base in fallbacks) {
                val dirs = base.listFiles()?.filter { it.isDirectory } ?: continue
                for (d in dirs) {
                    val anyManifest = d.listFiles()?.any { it.isDirectory && File(it, "manifest.json").exists() } == true
                    if (anyManifest) {
                        roots.add(d)
                    }
                }
            }
        }

        return roots.distinct()
    }

    private fun parseTimestampFromName(name: String): Long? {
        // Expected patterns like SESSION_YYYYMMDD_HHMMSS or CALIBRATION_YYYYMMDD_HHMMSS
        val m = Regex(".*_(\\d{8})_(\\d{6}).*").matchEntire(name) ?: return null
        return try {
            val ymd = m.groupValues[1]
            val hms = m.groupValues[2]
            // very rough ordering timestamp for sorting only (not for absolute time)
            (ymd + hms).toLong()
        } catch (_: Throwable) {
            null
        }
    }
}

class CalibrationConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("calibration_config", Context.MODE_PRIVATE)

    fun getSelectedCalibrationSessionName(): String? =
        prefs.getString(KEY_SELECTED_SESSION, null)

    fun setSelectedCalibrationSessionName(name: String?) {
        prefs.edit().putString(KEY_SELECTED_SESSION, name).apply()
    }

    fun getStabilizeCaptureFocus(): Boolean =
        prefs.getBoolean(KEY_STABILIZE_CAPTURE_FOCUS, false)

    fun setStabilizeCaptureFocus(value: Boolean) {
        prefs.edit().putBoolean(KEY_STABILIZE_CAPTURE_FOCUS, value).apply()
    }

    fun getUseCalibrationFocusDistance(): Boolean =
        prefs.getBoolean(KEY_USE_CAL_FOCUS_DISTANCE, false)

    fun setUseCalibrationFocusDistance(value: Boolean) {
        prefs.edit().putBoolean(KEY_USE_CAL_FOCUS_DISTANCE, value).apply()
    }

    companion object {
        private const val KEY_SELECTED_SESSION = "selected_calibration_session"
        private const val KEY_STABILIZE_CAPTURE_FOCUS = "stabilize_capture_focus"
        private const val KEY_USE_CAL_FOCUS_DISTANCE = "use_cal_focus_distance"
    }
}
