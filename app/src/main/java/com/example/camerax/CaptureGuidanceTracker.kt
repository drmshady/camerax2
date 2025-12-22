package com.example.camerax

import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic phase guidance + sufficiency evaluation for CAPTURE sessions.
 *
 * Updates stats ONLY on capture saved (not per analysis frame).
 * Live guidance uses latest marker+quality snapshot and current stats.
 */
internal class CaptureGuidanceTracker(
    private val stableIdsN: Int = GuidanceCommon.STABLE_IDS_N_DEFAULT,
    private val distanceMinCm: Double = 20.0,
    private val distanceMaxCm: Double = 30.0,
    private val edgeMarginFrac: Double = 0.10, // user decision 4A
    private val goodCapturesTarget: Int = 60,
    private val perTagTarget: Int = 10,
    private val gridTargetFilled: Int = 7,
    private val crossArchRequired: Boolean = true
) {

    enum class Phase { A_ANCHOR, B_LEFT, C_RIGHT, D_CROSSARCH, E_CLEANUP }

    data class LiveGuidance(
        val message: String,
        val phase: Phase,
        val phaseProgress: String,
        val coverageText: String,
        val enough: Boolean,
        val blockReason: String? = null
    )

    // ---- persistent session stats (capture-saved only) ----
    private val gridCounts = IntArray(9) { 0 }
    private val perTagCaptureCount = linkedMapOf<Long, Int>()
    private var goodCaptures: Int = 0

    private var aCenterMid = 0
    private var aLeftMid = 0
    private var aRightMid = 0
    private var aHighAny = 0
    private var aLowAny = 0

    private var bLeftMid = 0
    private var bLeftHigh = 0
    private var bLeftLow = 0

    private var cRightMid = 0
    private var cRightHigh = 0
    private var cRightLow = 0

    private var crossArchTotal = 0
    private var crossArchHigh = 0
    private var crossArchLow = 0

    private var stableIdsLocked: List<Long> = emptyList()
    private var requiredIdsActive: List<Long> = emptyList()

    @Synchronized
    fun resetForNewSession() {
        for (i in 0 until 9) gridCounts[i] = 0
        perTagCaptureCount.clear()
        goodCaptures = 0

        aCenterMid = 0; aLeftMid = 0; aRightMid = 0; aHighAny = 0; aLowAny = 0
        bLeftMid = 0; bLeftHigh = 0; bLeftLow = 0
        cRightMid = 0; cRightHigh = 0; cRightLow = 0
        crossArchTotal = 0; crossArchHigh = 0; crossArchLow = 0

        stableIdsLocked = emptyList()
        requiredIdsActive = emptyList()
    }

    @Synchronized
    fun onRequiredIdsChanged(newRequiredIds: List<Long>) {
        // If user changes required IDs mid-session, reset stats to keep interpretation deterministic.
        requiredIdsActive = newRequiredIds.toList()
        stableIdsLocked = emptyList()
        for (i in 0 until 9) gridCounts[i] = 0
        perTagCaptureCount.clear()
        goodCaptures = 0

        aCenterMid = 0; aLeftMid = 0; aRightMid = 0; aHighAny = 0; aLowAny = 0
        bLeftMid = 0; bLeftHigh = 0; bLeftLow = 0
        cRightMid = 0; cRightHigh = 0; cRightLow = 0
        crossArchTotal = 0; crossArchHigh = 0; crossArchLow = 0
    }

    private fun currentTrackedIds(requiredIds: List<Long>, stableCandidates: List<Long>): List<Long> {
        return if (requiredIds.isNotEmpty()) requiredIds else stableCandidates
    }

    private fun lockStableIdsIfPossible(requiredIds: List<Long>, stableCandidates: List<Long>) {
        if (requiredIds.isNotEmpty()) {
            stableIdsLocked = requiredIds
            return
        }
        if (stableIdsLocked.isNotEmpty()) return
        if (stableCandidates.size >= stableIdsN) {
            stableIdsLocked = stableCandidates.take(stableIdsN)
        }
    }

    private fun distanceOk(distanceCm: Double?): Boolean {
        if (distanceCm == null) return true
        return distanceCm in distanceMinCm..distanceMaxCm
    }

    /**
     * Compute framing using corners vs edge margin. If corners not available, fallback to centers.
     */
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

    private fun livePhase(): Phase {
        if (!isPhaseAComplete()) return Phase.A_ANCHOR
        if (!isPhaseBComplete()) return Phase.B_LEFT
        if (!isPhaseCComplete()) return Phase.C_RIGHT
        if (!isPhaseDComplete()) return Phase.D_CROSSARCH
        return Phase.E_CLEANUP
    }

    private fun isPhaseAComplete(): Boolean {
        return aCenterMid >= 2 && aLeftMid >= 2 && aRightMid >= 2 && aHighAny >= 2 && aLowAny >= 2
    }

    private fun isPhaseBComplete(): Boolean {
        return bLeftMid >= 5 && bLeftHigh >= 3 && bLeftLow >= 3
    }

    private fun isPhaseCComplete(): Boolean {
        return cRightMid >= 5 && cRightHigh >= 3 && cRightLow >= 3
    }

    private fun isPhaseDComplete(): Boolean {
        if (crossArchTotal < 6) return false
        if (crossArchHigh < 2) return false
        if (crossArchLow < 2) return false
        return true
    }

    private fun phaseProgressText(phase: Phase): String {
        return when (phase) {
            Phase.A_ANCHOR -> {
                val done = min(aCenterMid, 2) + min(aLeftMid, 2) + min(aRightMid, 2) + min(aHighAny, 2) + min(aLowAny, 2)
                "Phase A (Anchor): $done/10"
            }
            Phase.B_LEFT -> {
                val done = min(bLeftMid, 5) + min(bLeftHigh, 3) + min(bLeftLow, 3)
                "Phase B (Left sweep): $done/11"
            }
            Phase.C_RIGHT -> {
                val done = min(cRightMid, 5) + min(cRightHigh, 3) + min(cRightLow, 3)
                "Phase C (Right sweep): $done/11"
            }
            Phase.D_CROSSARCH -> "Phase D (Cross-arch): $crossArchTotal/6 (H:$crossArchHigh L:$crossArchLow)"
            Phase.E_CLEANUP -> "Phase E (Cleanup)"
        }
    }

    private fun enoughNow(trackedIds: List<Long>): Pair<Boolean, List<String>> {
        val reasons = ArrayList<String>()
        if (goodCaptures < goodCapturesTarget) reasons.add("Need more good shots: $goodCaptures/$goodCapturesTarget")
        val filled = GuidanceCommon.filledGridCells(gridCounts)
        if (filled < gridTargetFilled) reasons.add("Coverage: $filled/$gridTargetFilled")
        if (crossArchRequired && !isPhaseDComplete()) reasons.add("Cross-arch obliques missing")
        // per-tag counts
        for (id in trackedIds) {
            val c = perTagCaptureCount[id] ?: 0
            if (c < perTagTarget) reasons.add("Tag $id: $c/$perTagTarget")
        }
        return (reasons.isEmpty()) to reasons
    }

    /**
     * Live guidance for HUD (called often; does NOT mutate stats).
     */
    @Synchronized
    fun buildLiveGuidance(
        markerStatus: MarkerStatus,
        quality: QualityResult?,
        markerSessionSummary: Map<String, Any?>
    ): LiveGuidance {

        val frozenMarker = GuidanceCommon.frozenFromMarkerStatus(markerStatus)
        val q = quality
        val distance = q?.distanceCm
        val distOk = distanceOk(distance)
        val frameFramingOk = framingOk(frozenMarker)

        val required = markerStatus.requiredIds
        val stableCandidates = GuidanceCommon.chooseStableIdsFromSessionSummary(markerSessionSummary, stableIdsN)
        lockStableIdsIfPossible(required, stableCandidates)
        val trackedIds = if (required.isNotEmpty()) required else stableIdsLocked.ifEmpty { stableCandidates }

        val phase = livePhase()
        val phaseProgress = phaseProgressText(phase)

        val filled = GuidanceCommon.filledGridCells(gridCounts)
        val coverage = "Coverage: $filled/9"

        val (enough, reasons) = enoughNow(trackedIds)

        // Priority guidance
        val msg = when {
            markerStatus.detectedCount == 0 -> "No markers: move closer / improve lighting"
            required.isNotEmpty() && markerStatus.missingRequiredIds.isNotEmpty() ->
                "Missing: ${markerStatus.missingRequiredIds.joinToString(",")}"
            !frameFramingOk -> "Reframe: keep tags away from edges"
            !distOk -> {
                if (distance != null && distance < distanceMinCm) "Move farther (target ${distanceMinCm.toInt()}–${distanceMaxCm.toInt()} cm)"
                else "Move closer (target ${distanceMinCm.toInt()}–${distanceMaxCm.toInt()} cm)"
            }
            else -> {
                // Phase hint
                when (phase) {
                    Phase.A_ANCHOR -> "Next: anchor ring (front/left/right + high/low)"
                    Phase.B_LEFT -> "Next: sweep LEFT posterior (upper+lower rail)"
                    Phase.C_RIGHT -> "Next: sweep RIGHT posterior (upper+lower rail)"
                    Phase.D_CROSSARCH -> "Next: cross-arch obliques (high+low)"
                    Phase.E_CLEANUP -> {
                        val weak = trackedIds.filter { (perTagCaptureCount[it] ?: 0) < perTagTarget }
                        if (weak.isNotEmpty()) "Cleanup: weak tags ${weak.joinToString(",")} (need $perTagTarget each)"
                        else if (!enough) reasons.firstOrNull() ?: "Keep going"
                        else "Enough ✅"
                    }
                }
            }
        }

        // For BLOCK mode: only required missing OR framing bad OR distance out-of-range
        val blockReason = when {
            markerStatus.mode != MarkerMode.BLOCK -> null
            required.isNotEmpty() && markerStatus.missingRequiredIds.isNotEmpty() -> "Missing required"
            !frameFramingOk -> "Framing"
            !distOk -> "Distance"
            else -> null
        }

        return LiveGuidance(
            message = msg,
            phase = phase,
            phaseProgress = phaseProgress,
            coverageText = coverage,
            enough = enough,
            blockReason = blockReason
        )
    }

    /**
     * Update stats ONLY on successful capture save (deterministic).
     */
    @Synchronized
    fun onCaptureSaved(
        markerSnapshot: GuidanceCommon.FrozenMarkerSnapshot,
        qualitySnapshot: GuidanceCommon.FrozenQualitySnapshot,
        markerSessionSummary: Map<String, Any?>
    ) {
        // "Good capture" definition: quality OK + distance ok + framing ok + at least 1 marker
        val qOk = qualitySnapshot.status == QualityStatus.OK
        val dOk = distanceOk(qualitySnapshot.distanceCm)
        val fOk = framingOk(markerSnapshot)
        val hasMarkers = markerSnapshot.detections.isNotEmpty()

        if (!(qOk && dOk && fOk && hasMarkers)) return

        goodCaptures += 1

        val w = markerSnapshot.frameWidth
        val h = markerSnapshot.frameHeight

        // Determine tracked IDs
        val required = markerSnapshot.requiredIds
        val stableCandidates = GuidanceCommon.chooseStableIdsFromSessionSummary(markerSessionSummary, stableIdsN)
        lockStableIdsIfPossible(required, stableCandidates)
        val trackedIds = if (required.isNotEmpty()) required else stableIdsLocked.ifEmpty { stableCandidates }

        // Coverage: use average center across detections
        if (w > 0 && h > 0) {
            var sx = 0.0
            var sy = 0.0
            for (d in markerSnapshot.detections) { sx += d.centerX; sy += d.centerY }
            val xNorm = GuidanceCommon.clamp01(sx / markerSnapshot.detections.size / w.toDouble())
            val yNorm = GuidanceCommon.clamp01(sy / markerSnapshot.detections.size / h.toDouble())
            val cell = GuidanceCommon.gridIndex3x3(xNorm, yNorm)
            if (cell in 0..8) gridCounts[cell] += 1

            // Phase bins
            val lat = GuidanceCommon.lateralBin(xNorm)
            val ht = GuidanceCommon.heightBin(yNorm)

            if (ht == GuidanceCommon.HeightBin.MID && lat == GuidanceCommon.LateralBin.CENTER) aCenterMid += 1
            if (ht == GuidanceCommon.HeightBin.MID && lat == GuidanceCommon.LateralBin.LEFT) aLeftMid += 1
            if (ht == GuidanceCommon.HeightBin.MID && lat == GuidanceCommon.LateralBin.RIGHT) aRightMid += 1
            if (ht == GuidanceCommon.HeightBin.HIGH) aHighAny += 1
            if (ht == GuidanceCommon.HeightBin.LOW) aLowAny += 1

            if (lat == GuidanceCommon.LateralBin.LEFT && ht == GuidanceCommon.HeightBin.MID) bLeftMid += 1
            if (lat == GuidanceCommon.LateralBin.LEFT && ht == GuidanceCommon.HeightBin.HIGH) bLeftHigh += 1
            if (lat == GuidanceCommon.LateralBin.LEFT && ht == GuidanceCommon.HeightBin.LOW) bLeftLow += 1

            if (lat == GuidanceCommon.LateralBin.RIGHT && ht == GuidanceCommon.HeightBin.MID) cRightMid += 1
            if (lat == GuidanceCommon.LateralBin.RIGHT && ht == GuidanceCommon.HeightBin.HIGH) cRightHigh += 1
            if (lat == GuidanceCommon.LateralBin.RIGHT && ht == GuidanceCommon.HeightBin.LOW) cRightLow += 1

            // Cross-arch: wide spread + both sides
            val spread = GuidanceCommon.spreadXNorm(markerSnapshot.detections, w)
            val both = GuidanceCommon.hasBothSides(markerSnapshot.detections, w)
            val isCross = spread >= 0.65 && both
            if (isCross) {
                crossArchTotal += 1
                if (ht == GuidanceCommon.HeightBin.HIGH) crossArchHigh += 1
                if (ht == GuidanceCommon.HeightBin.LOW) crossArchLow += 1
            }
        }

        // Per-tag counts (tracked IDs only)
        val presentIds = markerSnapshot.detectedIds.toSet()
        for (id in trackedIds) {
            if (presentIds.contains(id)) {
                perTagCaptureCount[id] = (perTagCaptureCount[id] ?: 0) + 1
            } else {
                // ensure key exists for deterministic output
                if (!perTagCaptureCount.containsKey(id)) perTagCaptureCount[id] = 0
            }
        }
    }

    @Synchronized
    fun buildManifestSummary(markerSessionSummary: Map<String, Any?>): Map<String, Any?> {
        val required = requiredIdsActive
        val stableCandidates = GuidanceCommon.chooseStableIdsFromSessionSummary(markerSessionSummary, stableIdsN)
        lockStableIdsIfPossible(required, stableCandidates)
        val trackedIds = if (required.isNotEmpty()) required else stableIdsLocked.ifEmpty { stableCandidates }

        val filled = GuidanceCommon.filledGridCells(gridCounts)
        val (enough, reasons) = enoughNow(trackedIds)

        val gridMap = linkedMapOf<String, Any?>()
        for (i in 0 until 9) gridMap[i.toString()] = gridCounts[i]

        val perTagMap = linkedMapOf<String, Any?>()
        val idsSorted = trackedIds.sorted()
        for (id in idsSorted) {
            perTagMap[id.toString()] = perTagCaptureCount[id] ?: 0
        }

        val phaseMap = linkedMapOf<String, Any?>(
            "phaseA" to linkedMapOf(
                "centerMid" to aCenterMid,
                "leftMid" to aLeftMid,
                "rightMid" to aRightMid,
                "highAny" to aHighAny,
                "lowAny" to aLowAny
            ),
            "phaseB_left" to linkedMapOf("leftMid" to bLeftMid, "leftHigh" to bLeftHigh, "leftLow" to bLeftLow),
            "phaseC_right" to linkedMapOf("rightMid" to cRightMid, "rightHigh" to cRightHigh, "rightLow" to cRightLow),
            "phaseD_crossArch" to linkedMapOf("total" to crossArchTotal, "high" to crossArchHigh, "low" to crossArchLow)
        )

        return linkedMapOf(
            "version" to 1,
            "stableIdsN" to stableIdsN,
            "trackedIds" to trackedIds,
            "distanceRangeCm" to listOf(distanceMinCm, distanceMaxCm),
            "edgeMarginFrac" to edgeMarginFrac,
            "goodCaptures" to goodCaptures,
            "targets" to linkedMapOf(
                "goodCaptures" to goodCapturesTarget,
                "perTag" to perTagTarget,
                "gridFilled" to gridTargetFilled,
                "crossArchRequired" to crossArchRequired
            ),
            "coverageGridCounts" to gridMap,
            "coverageGridFilled" to filled,
            "perTagCaptureCount" to perTagMap,
            "phaseProgress" to phaseMap,
            "enough" to enough,
            "reasonsIfNotEnough" to reasons
        )
    }

    /**
     * Expand marker summary for sidecar: add distance/framing derived fields + phase/bin hints.
     */
    @Synchronized
    fun buildSidecarMarkerSummary(
        markerSnapshot: GuidanceCommon.FrozenMarkerSnapshot,
        qualitySnapshot: GuidanceCommon.FrozenQualitySnapshot,
        markerSessionSummary: Map<String, Any?>
    ): Map<String, Any?> {

        val required = markerSnapshot.requiredIds
        val stableCandidates = GuidanceCommon.chooseStableIdsFromSessionSummary(markerSessionSummary, stableIdsN)
        lockStableIdsIfPossible(required, stableCandidates)
        val trackedIds = if (required.isNotEmpty()) required else stableIdsLocked.ifEmpty { stableCandidates }

        val distOk = distanceOk(qualitySnapshot.distanceCm)
        val frmOk = framingOk(markerSnapshot)

        val w = markerSnapshot.frameWidth
        val h = markerSnapshot.frameHeight
        var cell: Int? = null
        var lat: String? = null
        var ht: String? = null
        var cross: Boolean = false

        if (w > 0 && h > 0 && markerSnapshot.detections.isNotEmpty()) {
            var sx = 0.0; var sy = 0.0
            for (d in markerSnapshot.detections) { sx += d.centerX; sy += d.centerY }
            val xNorm = GuidanceCommon.clamp01(sx / markerSnapshot.detections.size / w.toDouble())
            val yNorm = GuidanceCommon.clamp01(sy / markerSnapshot.detections.size / h.toDouble())
            cell = GuidanceCommon.gridIndex3x3(xNorm, yNorm)
            lat = GuidanceCommon.lateralBin(xNorm).name
            ht = GuidanceCommon.heightBin(yNorm).name

            val spread = GuidanceCommon.spreadXNorm(markerSnapshot.detections, w)
            val both = GuidanceCommon.hasBothSides(markerSnapshot.detections, w)
            cross = spread >= 0.65 && both
        }

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
            "requiredIds" to markerSnapshot.requiredIds,
            "trackedIds" to trackedIds,
            "missingRequiredIds" to markerSnapshot.missingRequiredIds,
            "detectedIds" to markerSnapshot.detectedIds.sorted(),
            "allRequiredVisible" to markerSnapshot.allRequiredVisible,
            "framingOk" to frmOk,
            "distanceCm" to qualitySnapshot.distanceCm,
            "distanceOk" to distOk,
            "phase" to livePhase().name,
            "gridCell" to cell,
            "lateralBin" to lat,
            "heightBin" to ht,
            "crossArch" to cross,
            "detections" to detList
        )
    }
}
