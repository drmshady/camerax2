package com.example.camerax

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

data class OverlayDetection(
    val id: Long,
    val centerX: Float,
    val centerY: Float,
    val corners: FloatArray? = null, // [x0,y0,x1,y1,x2,y2,x3,y3] in PreviewView coords
    val quality: Double? = null
)

/**
 * Lightweight overlay drawn on top of PreviewView.
 *
 * IMPORTANT:
 * - Does not touch CameraX pipeline.
 * - Draws using points already mapped to PreviewView coordinates.
 * - Rendering is skipped when there are no detections.
 */
class MarkerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // Stored as an immutable snapshot reference to avoid concurrent modification.
    @Volatile private var detectionsSnapshot: List<OverlayDetection> = emptyList()

    private val polyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF00FF00.toInt() // green
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFD700.toInt() // gold
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 28f
        color = 0xFFFFFFFF.toInt()
    }

    private val tmpPath = Path()

    fun setDetections(detections: List<OverlayDetection>) {
        detectionsSnapshot = detections
        // Avoid forcing redraw too often; this is already called from analyzer at ~10–15fps.
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val dets = detectionsSnapshot
        if (dets.isEmpty()) return

        for (d in dets) {
            val corners = d.corners
            if (corners != null && corners.size >= 8) {
                tmpPath.reset()
                tmpPath.moveTo(corners[0], corners[1])
                tmpPath.lineTo(corners[2], corners[3])
                tmpPath.lineTo(corners[4], corners[5])
                tmpPath.lineTo(corners[6], corners[7])
                tmpPath.close()
                canvas.drawPath(tmpPath, polyPaint)
            }

            // Center
            canvas.drawCircle(d.centerX, d.centerY, 6f, centerPaint)

            // ID label (clamped inside view)
            val label = d.id.toString()
            val tx = clamp(d.centerX + 8f, 0f, max(0f, width.toFloat() - 1f))
            val ty = clamp(d.centerY - 8f, textPaint.textSize, max(textPaint.textSize, height.toFloat() - 1f))
            canvas.drawText(label, tx, ty, textPaint)
        }
    }

    private fun clamp(v: Float, lo: Float, hi: Float): Float = min(max(v, lo), hi)
}
