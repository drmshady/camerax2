package com.example.camerax

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shared models/helpers for deterministic capture/calibration guidance.
 *
 * IMPORTANT:
 * - Does NOT touch CameraX capture pipeline.
 * - All computations are O(#tags) per frame and only update state on capture saved.
 */
internal object GuidanceCommon {

    const val STABLE_IDS_N_DEFAULT = 8

    data class FrozenTag(
        val id: Long,
        val centerX: Double,
        val centerY: Double,
        val corners: List<Pair<Double, Double>> = emptyList(),
        val quality: Double? = null
    )

    data class FrozenMarkerSnapshot(
        val timestampNs: Long,
        val mode: MarkerMode,
        val frameWidth: Int,
        val frameHeight: Int,
        val requiredIds: List<Long>,
        val detectedIds: List<Long>,
        val missingRequiredIds: List<Long>,
        val allRequiredVisible: Boolean,
        val framingOk: Boolean,
        val detections: List<FrozenTag>
    )

    data class FrozenQualitySnapshot(
        val status: QualityStatus,
        val blurScore: Double,
        val exposureFlags: List<String>,
        val distanceCm: Double?
    )

    enum class LateralBin { LEFT, CENTER, RIGHT }
    enum class HeightBin { LOW, MID, HIGH }

    fun lateralBin(xNorm: Double): LateralBin = when {
        xNorm < 0.33 -> LateralBin.LEFT
        xNorm > 0.66 -> LateralBin.RIGHT
        else -> LateralBin.CENTER
    }

    fun heightBin(yNorm: Double): HeightBin = when {
        yNorm < 0.33 -> HeightBin.LOW
        yNorm > 0.66 -> HeightBin.HIGH
        else -> HeightBin.MID
    }

    fun gridIndex3x3(xNorm: Double, yNorm: Double): Int {
        val col = when {
            xNorm < 0.333333 -> 0
            xNorm < 0.666666 -> 1
            else -> 2
        }
        val row = when {
            yNorm < 0.333333 -> 0
            yNorm < 0.666666 -> 1
            else -> 2
        }
        return row * 3 + col // 0..8
    }

    fun filledGridCells(counts: IntArray): Int {
        var k = 0
        for (c in counts) if (c > 0) k++
        return k
    }

    fun firstEmptyGridCell(counts: IntArray): Int? {
        for (i in 0 until 9) if (counts[i] <= 0) return i
        return null
    }

    fun cellName(index: Int): String {
        val row = index / 3
        val col = index % 3
        val rowName = when (row) {
            0 -> "top"
            1 -> "mid"
            else -> "bottom"
        }
        val colName = when (col) {
            0 -> "left"
            1 -> "center"
            else -> "right"
        }
        return "$rowName-$colName"
    }

    fun clamp01(v: Double): Double = max(0.0, min(1.0, v))

    fun spreadXNorm(detections: List<FrozenTag>, w: Int): Double {
        if (detections.isEmpty() || w <= 0) return 0.0
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        for (d in detections) {
            minX = min(minX, d.centerX)
            maxX = max(maxX, d.centerX)
        }
        return clamp01((maxX - minX) / w.toDouble())
    }

    fun hasBothSides(detections: List<FrozenTag>, w: Int): Boolean {
        if (detections.isEmpty() || w <= 0) return false
        val xs = detections.map { clamp01(it.centerX / w.toDouble()) }
        return xs.any { it < 0.33 } && xs.any { it > 0.66 }
    }

    /**
     * Picks top-N stable IDs from markerDetector.sessionSummaryMap().perTagCount
     *
     * perTagCount is a map where keys are String IDs and values are Long counts.
     */
    fun chooseStableIdsFromSessionSummary(summary: Map<String, Any?>, n: Int): List<Long> {
        val perTagAny = summary["perTagCount"]
        val perTag = perTagAny as? Map<*, *> ?: return emptyList()
        val list = ArrayList<Pair<Long, Long>>()
        for ((k, v) in perTag) {
            val id = k?.toString()?.toLongOrNull() ?: continue
            val cnt = when (v) {
                is Number -> v.toLong()
                else -> v?.toString()?.toLongOrNull() ?: 0L
            }
            list.add(id to cnt)
        }
        // sort by count desc, then id asc
        list.sortWith(compareByDescending<Pair<Long, Long>> { it.second }.thenBy { it.first })
        return list.take(max(0, n)).map { it.first }
    }

    fun frozenFromMarkerStatus(status: MarkerStatus): FrozenMarkerSnapshot {
        val det = status.detections.map { d ->
            FrozenTag(
                id = d.id,
                centerX = d.centerX,
                centerY = d.centerY,
                corners = d.corners ?: emptyList(),
                quality = d.quality
            )
        }
        return FrozenMarkerSnapshot(
            timestampNs = status.timestampNs,
            mode = status.mode,
            frameWidth = status.frameWidth,
            frameHeight = status.frameHeight,
            requiredIds = status.requiredIds.toList(),
            detectedIds = status.detectedIds.toList(),
            missingRequiredIds = status.missingRequiredIds.toList(),
            allRequiredVisible = status.allRequiredVisible,
            framingOk = status.framingOk,
            detections = det
        )
    }
}
