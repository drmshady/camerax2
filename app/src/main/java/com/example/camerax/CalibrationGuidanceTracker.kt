package com.example.camerax

import kotlin.math.min

/**
 * Deterministic guidance + sufficiency evaluation for CALIBRATION sessions.
 *
 * For calibration we keep it simple:
 * - Encourage 3x3 coverage of the board/flags in the frame
 * - Encourage distance around a target (25cm) but do not over-block (no BLOCK mode here)
 * - Count good calibration captures (quality OK + markers present)
 */
internal  class CalibrationGuidanceTracker(
    private val distanceTargetCm: Double = 25.0,
    private val distanceMinCm: Double = 20.0,
    private val distanceMaxCm: Double = 30.0,
    private val edgeMarginFrac: Double = 0.10,
    private val goodCapturesTarget: Int = 25,
    private val gridTargetFilled: Int = 8
) {

    data class LiveGuidance(
        val message: String,
        val progress: String,
        val coverageText: String,
        val enough: Boolean
    )

    private val gridCounts = IntArray(9) { 0 }
    private var goodCaptures: Int = 0

    @Synchronized
    fun resetForNewSession() {
        for (i in 0 until 9) gridCounts[i] = 0
        goodCaptures = 0
    }

    private fun distanceOk(distanceCm: Double?): Boolean {
        if (distanceCm == null) return true
        return distanceCm in distanceMinCm..distanceMaxCm
    }

    private fun framingOk(snapshot: GuidanceCommon.FrozenMarkerSnapshot): Boolean {
        val w = snapshot.frameWidth
        val h = snapshot.frameHeight
        if (w <= 0 || h <= 0) return snapshot.framingOk
        val mx = w * edgeMarginFrac
        val my = h * edgeMarginFrac
        if (snapshot.detections.isEmpty()) return true
        for (d in snapshot.detections) {
            val corners = d.corners
            if (corners.isNotEmpty()) {
                for ((cx, cy) in corners) {
                    if (cx < mx || cx > (w - mx) || cy < my || cy > (h - my)) return false
                }
            } else {
                if (d.centerX < mx || d.centerX > (w - mx) || d.centerY < my || d.centerY > (h - my)) return false
            }
        }
        return true
    }

    @Synchronized
    fun buildLiveGuidance(markerStatus: MarkerStatus, quality: QualityResult?): LiveGuidance {
        val frozen = GuidanceCommon.frozenFromMarkerStatus(markerStatus)
        val dist = quality?.distanceCm
        val distOk = distanceOk(dist)
        val frmOk = framingOk(frozen)

        val filled = GuidanceCommon.filledGridCells(gridCounts)
        val coverage = "Coverage: $filled/9"
        val enough = (goodCaptures >= goodCapturesTarget) && (filled >= gridTargetFilled)

        val progress = "Calib shots: $goodCaptures/$goodCapturesTarget"

        val msg = when {
            markerStatus.detectedCount == 0 -> "No markers: bring board/flags into view"
            !frmOk -> "Reframe: keep board away from edges"
            !distOk -> {
                if (dist != null && dist < distanceMinCm) "Move farther (target ~$distanceTargetCm cm)"
                else "Move closer (target ~$distanceTargetCm cm)"
            }
            filled < gridTargetFilled -> {
                val empty = GuidanceCommon.firstEmptyGridCell(gridCounts)
                if (empty != null) "Move board to ${GuidanceCommon.cellName(empty)}"
                else "Move board around the frame"
            }
            else -> if (enough) "Calibration enough ✅" else "Keep going"
        }

        return LiveGuidance(msg, progress, coverage, enough)
    }

    @Synchronized
    fun onCaptureSaved(markerSnapshot: GuidanceCommon.FrozenMarkerSnapshot, qualitySnapshot: GuidanceCommon.FrozenQualitySnapshot) {
        val qOk = qualitySnapshot.status == QualityStatus.OK
        val hasMarkers = markerSnapshot.detections.isNotEmpty()
        val distOk = distanceOk(qualitySnapshot.distanceCm)
        val frmOk = framingOk(markerSnapshot)

        if (!(qOk && hasMarkers && distOk && frmOk)) return

        goodCaptures += 1

        val w = markerSnapshot.frameWidth
        val h = markerSnapshot.frameHeight
        if (w > 0 && h > 0 && markerSnapshot.detections.isNotEmpty()) {
            var sx = 0.0
            var sy = 0.0
            for (d in markerSnapshot.detections) { sx += d.centerX; sy += d.centerY }
            val xNorm = GuidanceCommon.clamp01(sx / markerSnapshot.detections.size / w.toDouble())
            val yNorm = GuidanceCommon.clamp01(sy / markerSnapshot.detections.size / h.toDouble())
            val cell = GuidanceCommon.gridIndex3x3(xNorm, yNorm)
            if (cell in 0..8) gridCounts[cell] += 1
        }
    }

    @Synchronized
    fun buildManifestSummary(): Map<String, Any?> {
        val filled = GuidanceCommon.filledGridCells(gridCounts)
        val enough = (goodCaptures >= goodCapturesTarget) && (filled >= gridTargetFilled)

        val gridMap = linkedMapOf<String, Any?>()
        for (i in 0 until 9) gridMap[i.toString()] = gridCounts[i]

        return linkedMapOf(
            "version" to 1,
            "distanceTargetCm" to distanceTargetCm,
            "distanceRangeCm" to listOf(distanceMinCm, distanceMaxCm),
            "edgeMarginFrac" to edgeMarginFrac,
            "goodCaptures" to goodCaptures,
            "targets" to linkedMapOf(
                "goodCaptures" to goodCapturesTarget,
                "gridFilled" to gridTargetFilled
            ),
            "coverageGridCounts" to gridMap,
            "coverageGridFilled" to filled,
            "enough" to enough
        )
    }

    @Synchronized
    fun buildSidecarMarkerSummary(
        markerSnapshot: GuidanceCommon.FrozenMarkerSnapshot,
        qualitySnapshot: GuidanceCommon.FrozenQualitySnapshot
    ): Map<String, Any?> {

        val w = markerSnapshot.frameWidth
        val h = markerSnapshot.frameHeight
        val distOk = distanceOk(qualitySnapshot.distanceCm)
        val frmOk = framingOk(markerSnapshot)

        val detList = markerSnapshot.detections
            .sortedWith(compareBy<GuidanceCommon.FrozenTag> { it.id }.thenBy { it.centerX }.thenBy { it.centerY })
            .map { d ->
                val corners = d.corners.map { listOf(it.first, it.second) }
                linkedMapOf(
                    "id" to d.id,
                    "centerPx" to listOf(d.centerX, d.centerY),
                    "centerNorm" to if (w > 0 && h > 0) listOf(d.centerX / w.toDouble(), d.centerY / h.toDouble()) else null,
                    "cornersPx" to corners,
                    "quality" to d.quality
                )
            }

        return linkedMapOf(
            "mode" to markerSnapshot.mode.name,
            "dictionary" to "APRILTAG_36h11",
            "frameSize" to listOf(markerSnapshot.frameWidth, markerSnapshot.frameHeight),
            "framingOk" to frmOk,
            "distanceCm" to qualitySnapshot.distanceCm,
            "distanceOk" to distOk,
            "detections" to detList
        )
    }
}
