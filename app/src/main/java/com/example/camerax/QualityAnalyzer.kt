package com.example.camerax

import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.pow

enum class QualityStatus { OK, BLUR, OVER, UNDER }

data class QualityResult(
    val status: QualityStatus,
    val blurScore: Double,        // Laplacian variance (ROI)
    val overPercent: Double,      // clipped highlights percent (ROI)
    val underPercent: Double,     // clipped shadows percent (ROI)
    val distanceCm: Double?       // best-effort estimate, null if not available
)

class QualityAnalyzer(
    private val resultStore: CaptureResultStore,
    private val targetFps: Int = 12,              // ~10–15 fps
    private val roiFrac: Double = 0.40,           // center ROI size
    private val blurThreshold: Double = 150.0,    // tune per device
    private val clipHigh: Int = 245,
    private val clipLow: Int = 10,
    private val overThresh: Double = 0.02,
    private val underThresh: Double = 0.02,
    private val onResult: (QualityResult) -> Unit
) : ImageAnalysis.Analyzer {

    private var lastAnalyzeNs: Long = 0L
    private val intervalNs: Long = (1_000_000_000L / targetFps.coerceAtLeast(1))

    override fun analyze(image: ImageProxy) {
        try {
            val nowNs = image.imageInfo.timestamp
            if (lastAnalyzeNs != 0L && (nowNs - lastAnalyzeNs) < intervalNs) return
            lastAnalyzeNs = nowNs

            if (image.format != ImageFormat.YUV_420_888) return

            val yPlane = image.planes[0]
            val buf = yPlane.buffer
            val width = image.width
            val height = image.height
            val rowStride = yPlane.rowStride

            val yBytes = ByteArray(buf.remaining())
            buf.get(yBytes)

            // ---- Center ROI ----
            val roiW = (width * roiFrac).toInt().coerceAtLeast(64).coerceAtMost(width)
            val roiH = (height * roiFrac).toInt().coerceAtLeast(64).coerceAtMost(height)
            val startX = ((width - roiW) / 2).coerceAtLeast(0)
            val startY = ((height - roiH) / 2).coerceAtLeast(0)

            // ---- Blur: Laplacian variance on ROI (sampled for speed) ----
            var sum = 0.0
            var sumSq = 0.0
            var count = 0

            val step = 2 // speed

            for (y in (startY + 1) until (startY + roiH - 1) step step) {
                val base = y * rowStride
                val baseUp = (y - 1) * rowStride
                val baseDn = (y + 1) * rowStride
                for (x in (startX + 1) until (startX + roiW - 1) step step) {
                    val c = yBytes[base + x].toInt() and 0xFF
                    val up = yBytes[baseUp + x].toInt() and 0xFF
                    val dn = yBytes[baseDn + x].toInt() and 0xFF
                    val lt = yBytes[base + (x - 1)].toInt() and 0xFF
                    val rt = yBytes[base + (x + 1)].toInt() and 0xFF

                    val lap = (up + dn + lt + rt - 4 * c).toDouble()
                    sum += lap
                    sumSq += lap * lap
                    count++
                }
            }

            val mean = if (count > 0) sum / count else 0.0
            val variance = if (count > 0) (sumSq / count) - mean.pow(2.0) else 0.0

            // ---- Exposure check on ROI ----
            var clippedHighCnt = 0
            var clippedLowCnt = 0
            var total = 0

            val expStep = 2
            for (y in startY until (startY + roiH) step expStep) {
                val base = y * rowStride
                for (x in startX until (startX + roiW) step expStep) {
                    val v = yBytes[base + x].toInt() and 0xFF
                    if (v >= clipHigh) clippedHighCnt++
                    if (v <= clipLow) clippedLowCnt++
                    total++
                }
            }

            val overPct = if (total > 0) clippedHighCnt.toDouble() / total else 0.0
            val underPct = if (total > 0) clippedLowCnt.toDouble() / total else 0.0

            // ---- Distance estimate (best effort) ----
            // LENS_FOCUS_DISTANCE is diopters (1/m). Approx distance (m) = 1/diopters.
            val focusDiopters = resultStore.latestFocusDistance()
            val distanceCm = focusDiopters?.takeIf { it > 0f }?.let { (100.0 / it) }

            val status = when {
                variance < blurThreshold -> QualityStatus.BLUR
                overPct > overThresh -> QualityStatus.OVER
                underPct > underThresh -> QualityStatus.UNDER
                else -> QualityStatus.OK
            }

            onResult(
                QualityResult(
                    status = status,
                    blurScore = variance,
                    overPercent = overPct,
                    underPercent = underPct,
                    distanceCm = distanceCm
                )
            )
        } finally {
            image.close()
        }
    }
}
