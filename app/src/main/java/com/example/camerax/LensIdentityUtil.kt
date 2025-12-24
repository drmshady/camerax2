package com.example.camerax

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlin.math.abs

/**
 * Resolves a stable "lens identity" for session manifests.
 *
 * We log:
 *  - cameraId: Android Camera2 camera ID
 *  - lens: "main" / "tele" / "uw" (or "front" if front camera)
 *  - focalLengthMm: best-effort focal length (mm)
 *
 * This is critical for choosing the correct intrinsics calibration on the PC.
 */
object LensIdentityUtil {

    fun appendLensIdentity(
        sessionInfo: MutableMap<String, Any?>,
        context: Context,
        cameraId: String?,
        characteristics: CameraCharacteristics?
    ) {
        val id = cameraId ?: return

        val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val focalArray = characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val focalMm = focalArray?.minOrNull()?.toDouble()

        val lensFacing = characteristics?.get(CameraCharacteristics.LENS_FACING)
        val lensLabel = when (lensFacing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            else -> bestEffortBackLensLabel(camManager, id, focalMm)
        }

        sessionInfo["cameraId"] = id
        sessionInfo["lens"] = lensLabel
        if (focalMm != null && focalMm > 0.0) {
            // Keep as Double for JSON
            sessionInfo["focalLengthMm"] = focalMm
        } else {
            sessionInfo["focalLengthMm"] = null
        }
    }

    private fun bestEffortBackLensLabel(
        camManager: CameraManager,
        currentId: String,
        currentFocalMm: Double?
    ): String {
        // If we can't read focal length, assume main.
        if (currentFocalMm == null) return "main"

        // Collect back-facing camera focal lengths (min of available focal lengths)
        val list = mutableListOf<Pair<String, Double>>()
        for (id in camManager.cameraIdList) {
            val ch = try { camManager.getCameraCharacteristics(id) } catch (_: Throwable) { null } ?: continue
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
            val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val f = focals?.minOrNull()?.toDouble()
            if (f != null && f > 0.0) list.add(id to f)
        }

        if (list.size < 2) return "main"

        list.sortBy { it.second }

        val minF = list.first().second
        val maxF = list.last().second

        // Determine position by either exact cameraId match or focal proximity.
        val idx = list.indexOfFirst { it.first == currentId }
        val tol = 0.15 // mm tolerance for near-equality comparisons

        // If cameraId not found (logical camera cases), match by focal value.
        val effectiveIdx = if (idx >= 0) idx else {
            // find closest focal
            var best = 0
            var bestErr = Double.MAX_VALUE
            for ((i, p) in list.withIndex()) {
                val err = abs(p.second - currentFocalMm)
                if (err < bestErr) { bestErr = err; best = i }
            }
            best
        }

        return when (effectiveIdx) {
            0 -> {
                // Only label as uw if it is meaningfully smaller than the next one.
                val nextF = list.getOrNull(1)?.second ?: minF
                if (nextF - minF > tol) "uw" else "main"
            }
            list.lastIndex -> {
                val prevF = list.getOrNull(list.lastIndex - 1)?.second ?: maxF
                if (maxF - prevF > tol) "tele" else "main"
            }
            else -> "main"
        }
    }

    fun buildIntrinsicsKey(
        model: String,
        lens: String?,
        imageWidth: Int?,
        imageHeight: Int?
    ): String {
        val cleanModel = sanitizeForKey(model)
        val cleanLens = sanitizeForKey(lens ?: "unknown")
        val w = imageWidth ?: 0
        val h = imageHeight ?: 0
        return "${cleanModel}_${cleanLens}_${w}x${h}"
    }

    private fun sanitizeForKey(s: String): String {
        // Avoid unsupported escape sequences by using raw regex strings.
        return s.trim()
            .replace(Regex("""\s+"""), "_")
            .replace(Regex("""[^A-Za-z0-9_-]"""), "_")
            .trim('_')
            .ifEmpty { "unknown" }
    }

}
