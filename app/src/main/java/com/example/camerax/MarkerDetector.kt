package com.example.camerax

import android.content.Context
import androidx.camera.core.ImageProxy
import boofcv.abst.fiducial.FiducialDetector
import boofcv.factory.fiducial.ConfigHammingMarker
import boofcv.factory.fiducial.FactoryFiducial
import boofcv.factory.fiducial.HammingDictionary
import boofcv.struct.image.GrayU8
import georegression.struct.point.Point2D_F64
import georegression.struct.shapes.Polygon2D_F64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

enum class MarkerMode { OFF, WARN, BLOCK }

data class TagDetection(
    val id: Long,
    val centerX: Double,
    val centerY: Double,
    val corners: List<Pair<Double, Double>>? = null,
    val quality: Double? = null
)

data class MarkerStatus(
    val timestampNs: Long = 0L,
    val mode: MarkerMode = MarkerMode.OFF,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val detectedCount: Int = 0,
    val detectedIds: List<Long> = emptyList(),
    val detections: List<TagDetection> = emptyList(),
    val requiredIds: List<Long> = emptyList(),
    val missingRequiredIds: List<Long> = emptyList(),
    val allRequiredVisible: Boolean = false,
    val framingOk: Boolean = true,
    val guidanceText: String = "Marker detection: OFF",
    val displayText: String = "Marker detection: OFF"
)

/**
 * Marker detector for Phase 1.5 (HUD guidance).
 *
 * Implementation uses BoofCV's Hamming square fiducials with APRILTAG_36h11 dictionary.
 * This yields AprilTag-like IDs and robust detection at ~10–15fps if you:
 * - use only the luma (Y) plane
 * - crop to a center ROI
 * - downsample (step) before detection
 *
 * IMPORTANT: process() must be called from ImageAnalysis analyzer thread. It must never block the UI thread.
 */
interface MarkerDetector {
    fun setMode(mode: MarkerMode)
    fun setRequiredIds(ids: List<Long>)
    fun reset()

    /** Called from ImageAnalysis analyzer thread */
    fun process(image: ImageProxy)

    fun latest(): MarkerStatus

    /** Summary aggregated across frames (reset per session). */
    fun sessionSummaryMap(): Map<String, Any?>
}

class BoofCvAprilTag36h11Detector(
    context: Context,
    private val roiFrac: Double = 0.60,       // center ROI fraction
    private val downsampleStep: Int = 2,      // 2 => quarter pixels
    private val edgeMarginFrac: Double = 0.10 // framing check margin
) : MarkerDetector {

    private val appContext = context.applicationContext

    private val detector: FiducialDetector<GrayU8> by lazy {
        val configMarker = ConfigHammingMarker.loadDictionary(HammingDictionary.APRILTAG_36h11)
        // null configDetector => defaults
        FactoryFiducial.squareHamming(configMarker, null, GrayU8::class.java)
    }

    private val latestRef = AtomicReference(MarkerStatus())

    @Volatile private var mode: MarkerMode = MarkerMode.OFF
    @Volatile private var requiredIds: List<Long> = emptyList()

    // Session summary (deterministic counters)
    private var framesProcessed: Long = 0L
    private var framesAllRequiredVisible: Long = 0L
    private val perTagCount = linkedMapOf<Long, Long>() // stable insertion order

    override fun setMode(mode: MarkerMode) {
        this.mode = mode
        if (mode == MarkerMode.OFF) {
            latestRef.set(MarkerStatus(mode = MarkerMode.OFF))
        }
    }

    override fun setRequiredIds(ids: List<Long>) {
        // stable order, unique
        this.requiredIds = ids.distinct().sorted()
    }

    override fun reset() {
        framesProcessed = 0L
        framesAllRequiredVisible = 0L
        perTagCount.clear()
        latestRef.set(MarkerStatus(mode = mode))
    }

    override fun latest(): MarkerStatus = latestRef.get()

    override fun process(image: ImageProxy) {
        val localMode = mode
        if (localMode == MarkerMode.OFF) {
            // Still update timestamp so logs can confirm it's running
            latestRef.set(MarkerStatus(timestampNs = image.imageInfo.timestamp, mode = MarkerMode.OFF, frameWidth = image.width, frameHeight = image.height))
            return
        }

        val timestamp = image.imageInfo.timestamp
        val width = image.width
        val height = image.height

        // Use Y plane only
        val yPlane = image.planes[0]
        val buf = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        if (pixelStride != 1) {
            latestRef.set(
                MarkerStatus(
                    timestampNs = timestamp,
                    mode = localMode,
                    frameWidth = width,
                    frameHeight = height,
                    guidanceText = "Marker: unsupported format",
                    displayText = "Marker: unsupported format"
                )
            )
            return
        }

        // ---- ROI (center crop) ----
        val roiW = (width * roiFrac).toInt().coerceAtLeast(160).coerceAtMost(width)
        val roiH = (height * roiFrac).toInt().coerceAtLeast(160).coerceAtMost(height)
        val startX = ((width - roiW) / 2).coerceAtLeast(0)
        val startY = ((height - roiH) / 2).coerceAtLeast(0)

        val step = downsampleStep.coerceAtLeast(1)
        val dsW = max(1, roiW / step)
        val dsH = max(1, roiH / step)

        val gray = GrayU8(dsW, dsH)

        // Copy ROI into GrayU8 with downsample.
        // Avoid copying whole frame: read just the ROI rows we need.
        val dup = buf.duplicate()
        val rowTmp = ByteArray(roiW)

        var yy = 0
        var y = startY
        while (yy < dsH && y < startY + roiH) {
            val pos = y * rowStride + startX
            if (pos < 0 || pos + roiW > dup.capacity()) break

            dup.position(pos)
            dup.get(rowTmp, 0, roiW)

            var xx = 0
            var x = 0
            while (xx < dsW && x < roiW) {
                gray.data[yy * dsW + xx] = rowTmp[x]
                xx++
                x += step
            }

            yy++
            y += step
        }

        // ---- Detect ----
        detector.detect(gray)

        val total = detector.totalFound()
        val detections = ArrayList<TagDetection>(total)
        val ids = ArrayList<Long>(total)

        val center = Point2D_F64()
        val poly = Polygon2D_F64(4)

        for (i in 0 until total) {
            val id = detector.getId(i)
            detector.getCenter(i, center)
            detector.getBounds(i, poly)

            // Convert ROI+downsample coords -> full image coords
            val fullCx = startX + center.x * step
            val fullCy = startY + center.y * step

            val corners = ArrayList<Pair<Double, Double>>(poly.size())
            for (p in poly.vertexes.toList()) {
                corners.add(Pair(startX + p.x * step, startY + p.y * step))
            }

            // Simple quality proxy: normalized area in ROI (bigger = better), clamped 0..1
            val area = polygonArea(corners)
            val q = (area / (roiW.toDouble() * roiH.toDouble())).coerceIn(0.0, 1.0)

            detections.add(
                TagDetection(
                    id = id,
                    centerX = fullCx,
                    centerY = fullCy,
                    corners = corners,
                    quality = q
                )
            )
            ids.add(id)

            // Summary counters
            perTagCount[id] = (perTagCount[id] ?: 0L) + 1L
        }

        framesProcessed += 1L

        val required = requiredIds
        val missing = if (required.isEmpty()) emptyList() else required.filter { rid -> !ids.contains(rid) }
        val allReq = missing.isEmpty()

        if (required.isNotEmpty() && missing.isEmpty()) {
            framesAllRequiredVisible += 1L
        }

        // Framing check: keep detected tag corners away from the full-image edges
        val marginX = (width * edgeMarginFrac).toDouble()
        val marginY = (height * edgeMarginFrac).toDouble()
        val framingOk = if (detections.isEmpty()) {
            true
        } else {
            detections.all { d ->
                val corners = d.corners ?: emptyList()
                if (corners.isNotEmpty()) {
                    corners.all { (cx, cy) ->
                        cx >= marginX && cx <= (width - marginX) &&
                        cy >= marginY && cy <= (height - marginY)
                    }
                } else {
                    d.centerX >= marginX && d.centerX <= (width - marginX) &&
                    d.centerY >= marginY && d.centerY <= (height - marginY)
                }
            }
        }


        val guidance = buildGuidanceText(total, required, missing, framingOk)
        val display = buildDisplayText(total, ids, required, missing, framingOk)

        latestRef.set(
            MarkerStatus(
                timestampNs = timestamp,
                mode = localMode,
                frameWidth = width,
                frameHeight = height,
                detectedCount = total,
                detectedIds = ids.sorted(),
                detections = detections,
                requiredIds = required,
                missingRequiredIds = missing,
                allRequiredVisible = allReq,
                framingOk = framingOk,
                guidanceText = guidance,
                displayText = display
            )
        )
    }

    override fun sessionSummaryMap(): Map<String, Any?> {
        val unique = perTagCount.keys.toList().sorted()
        val perTag = linkedMapOf<String, Any?>()
        for (id in unique) {
            perTag[id.toString()] = perTagCount[id] ?: 0L
        }

        val frames = framesProcessed
        val allReqPct = if (frames > 0L) (framesAllRequiredVisible.toDouble() / frames.toDouble()) else 0.0

        return linkedMapOf(
            "dictionary" to "APRILTAG_36h11",
            "mode" to mode.name,
            "requiredIds" to requiredIds,
            "framesProcessed" to framesProcessed,
            "framesAllRequiredVisible" to framesAllRequiredVisible,
            "pctFramesAllRequiredVisible" to allReqPct,
            "uniqueTagIdsSeen" to unique,
            "perTagCount" to perTag
        )
    }

    private fun buildGuidanceText(
        total: Int,
        required: List<Long>,
        missing: List<Long>,
        framingOk: Boolean
    ): String {
        if (total == 0) return "No markers: move closer / improve lighting"
        if (required.isNotEmpty() && missing.isNotEmpty()) return "Missing markers: ${missing.joinToString(",")}"
        if (!framingOk) return "Reframe: keep markers away from edges"
        return if (required.isNotEmpty()) "All required markers visible ✅" else "Markers visible ✅"
    }

    private fun buildDisplayText(
        total: Int,
        ids: List<Long>,
        required: List<Long>,
        missing: List<Long>,
        framingOk: Boolean
    ): String {
        val idText = if (ids.isEmpty()) "—" else ids.sorted().joinToString(",")
        val base = "Markers: $total (IDs: $idText)"
        val req = if (required.isNotEmpty()) {
            val reqTxt = required.joinToString(",")
            val missTxt = if (missing.isEmpty()) "none" else missing.joinToString(",")
            " | Req: $reqTxt | Missing: $missTxt"
        } else ""
        val frame = if (!framingOk) " | ⚠️ edges" else ""
        return base + req + frame
    }

    private fun polygonArea(points: List<Pair<Double, Double>>): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        for (i in points.indices) {
            val (x1, y1) = points[i]
            val (x2, y2) = points[(i + 1) % points.size]
            sum += x1 * y2 - x2 * y1
        }
        return kotlin.math.abs(sum) * 0.5
    }
}
