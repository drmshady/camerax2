package com.example.camerax

import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.Stack
import kotlin.math.pow

enum class QualityStatus { UNKNOWN, OK, BLUR, OVER, UNDER, SPECULAR }

data class QualityResult(
    val status: QualityStatus,
    val blurScore: Double,        // Laplacian variance (ROI)
    val overPercent: Double,      // clipped highlights percent (ROI)
    val underPercent: Double,     // clipped shadows percent (ROI)
    val distanceCm: Double?,       // best-effort estimate, null if not available
    val specularClusterCount: Int = 0,
    val largestSpecularClusterSize: Int = 0
) {
    companion object {
        fun default(): QualityResult = QualityResult(
            status = QualityStatus.UNKNOWN,
            blurScore = 0.0,
            overPercent = 0.0,
            underPercent = 0.0,
            distanceCm = null
        )
    }
}

class QualityAnalyzer(
    private val resultStore: CaptureResultStore,
    private val targetFps: Int = 12,
    private val roiFrac: Double = 0.40,
    private val blurThreshold: Double = 150.0,
    private val clipHigh: Int = 245,
    private val clipLow: Int = 10,
    private val overThresh: Double = 0.02,
    private val underThresh: Double = 0.02,
    private val specularClusterMaxCount: Int = 5,
    private val specularClusterMaxSize: Int = 100,
    private val markerDetector: MarkerDetector? = null,
    private val onMarkerResult: ((MarkerStatus) -> Unit)? = null,
    private val onResult: (QualityResult) -> Unit
) : ImageAnalysis.Analyzer {

    private var lastAnalyzeNs: Long = 0L
    private val intervalNs: Long = (1_000_000_000L / targetFps.coerceAtLeast(1))

    override fun analyze(image: ImageProxy) {
        try {
            val nowNs = image.imageInfo.timestamp
            if (lastAnalyzeNs != 0L && (nowNs - lastAnalyzeNs) < intervalNs) {
                return
            }
            lastAnalyzeNs = nowNs

            if (image.format != ImageFormat.YUV_420_888) {
                return
            }

            markerDetector?.let {
                it.process(image)
                onMarkerResult?.invoke(it.latest())
            }

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
            val step = 2
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
            var overCount = 0
            var underCount = 0
            var total = 0
            val expStep = 2
            val roiOverPixels = mutableListOf<Pair<Int, Int>>()
            for (y in startY until (startY + roiH) step expStep) {
                val base = y * rowStride
                for (x in startX until (startX + roiW) step expStep) {
                    val v = yBytes[base + x].toInt() and 0xFF
                    if (v >= clipHigh) {
                        overCount++
                        roiOverPixels.add(Pair(x, y))
                    }
                    if (v <= clipLow) {
                        underCount++
                    }
                    total++
                }
            }
            val overPct = if (total > 0) overCount.toDouble() / total else 0.0
            val underPct = if (total > 0) underCount.toDouble() / total else 0.0

            // --- Specular Reflection Analysis (Blob detection) ---
            var specularClusterCount = 0
            var largestSpecularClusterSize = 0
            if (overPct > 0) {
                val (clusters, largestCluster) = findPixelClusters(roiOverPixels)
                specularClusterCount = clusters
                largestSpecularClusterSize = largestCluster
            }

            // ---- Distance estimate (best effort) ----
            val focusDiopters = resultStore.latestFocusDistance()
            val distanceCm = focusDiopters?.takeIf { it > 0f }?.let { (100.0 / it) }

            val status = when {
                variance < blurThreshold -> QualityStatus.BLUR
                overPct > overThresh -> {
                    if (specularClusterCount <= specularClusterMaxCount && largestSpecularClusterSize <= specularClusterMaxSize) {
                        QualityStatus.SPECULAR
                    } else {
                        QualityStatus.OVER
                    }
                }
                underPct > underThresh -> QualityStatus.UNDER
                else -> QualityStatus.OK
            }

            onResult(
                QualityResult(
                    status = status,
                    blurScore = variance,
                    overPercent = overPct,
                    underPercent = underPct,
                    distanceCm = distanceCm,
                    specularClusterCount = specularClusterCount,
                    largestSpecularClusterSize = largestSpecularClusterSize
                )
            )
        } finally {
            image.close()
        }
    }

    private fun findPixelClusters(pixels: List<Pair<Int, Int>>): Pair<Int, Int> {
        if (pixels.isEmpty()) return Pair(0, 0)

        val pixelSet = pixels.toHashSet()
        var clusterCount = 0
        var maxClusterSize = 0

        while (pixelSet.isNotEmpty()) {
            clusterCount++
            val stack = Stack<Pair<Int, Int>>()
            stack.push(pixelSet.first())
            pixelSet.remove(stack.peek())
            var currentClusterSize = 0

            while (stack.isNotEmpty()) {
                val (x, y) = stack.pop()
                currentClusterSize++

                // Check 8 neighbors
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val neighbor = Pair(x + dx, y + dy)
                        if (pixelSet.contains(neighbor)) {
                            pixelSet.remove(neighbor)
                            stack.push(neighbor)
                        }
                    }
                }
            }
            if (currentClusterSize > maxClusterSize) {
                maxClusterSize = currentClusterSize
            }
        }

        return Pair(clusterCount, maxClusterSize)
    }
}
