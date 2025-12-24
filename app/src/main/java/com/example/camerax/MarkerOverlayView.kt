package com.example.camerax

import android.content.Context
import android.graphics.Canvas
<<<<<<< HEAD
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import android.view.ViewParent
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView

/**
 * Overlay view that draws detected marker boundaries on top of PreviewView.
 * Must be sized match_parent to PreviewView for correct coordinate mapping.
 */
class MarkerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val mapper = MarkerOverlayMapper()
    
    // Paint for marker boxes
    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    
    // Paint for marker center dots
    private val centerPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // Paint for warning markers (required but not detected, or framing issue)
    private val warningPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    
    // Paint for error markers
    private val errorPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    
    // Paint for text labels
    private val textPaint = Paint().apply {
        color = Color.GREEN
        textSize = 32f
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }
    
    private var currentStatus: MarkerStatus? = null
    private var currentImageProxy: ImageProxy? = null
    private var previewView: PreviewView? = null
    
    /**
     * Update the overlay with new marker detection results.
     * Must be called from UI thread.
     */
    fun updateMarkers(status: MarkerStatus, imageProxy: ImageProxy?) {
        currentStatus = status
        currentImageProxy = imageProxy
        
        // Find PreviewView on first update if needed
        if (previewView == null) {
            previewView = findPreviewView(parent)
        }
        
        invalidate()
    }
    
    /**
     * Clear all markers from display.
     */
    fun clear() {
        currentStatus = null
        currentImageProxy = null
        invalidate()
    }
    
    private fun findPreviewView(viewParent: ViewParent?): PreviewView? {
        if (viewParent == null) return null
        if (viewParent is PreviewView) return viewParent
        
        // Search siblings in parent ViewGroup
        if (viewParent is android.view.ViewGroup) {
            for (i in 0 until viewParent.childCount) {
                val child = viewParent.getChildAt(i)
                if (child is PreviewView) return child
            }
        }
        
        return findPreviewView(viewParent.parent)
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val status = currentStatus ?: return
        val imageProxy = currentImageProxy ?: return
        val preview = previewView ?: return
        
        if (status.mode == MarkerMode.OFF || status.detections.isEmpty()) {
            return
        }
        
        // Draw each detected marker
        for (detection in status.detections) {
            drawMarker(canvas, detection, status, imageProxy, preview)
        }
    }
    
    private fun drawMarker(
        canvas: Canvas,
        detection: TagDetection,
        status: MarkerStatus,
        imageProxy: ImageProxy,
        previewView: PreviewView
    ) {
        val corners = detection.corners
        
        // Determine color based on status
        val paint = when {
            status.mode == MarkerMode.BLOCK && !status.framingOk -> errorPaint
            status.requiredIds.isNotEmpty() && !status.requiredIds.contains(detection.id) -> warningPaint
            else -> boxPaint
        }
        
        // Try to map corners if available
        if (corners != null && corners.size >= 4) {
            val mappedCorners = mapper.mapCornersToPreview(corners, imageProxy, previewView)
            
            if (mappedCorners != null && mappedCorners.size >= 4) {
                // Draw polygon connecting corners
                val path = Path()
                path.moveTo(mappedCorners[0].x, mappedCorners[0].y)
                for (i in 1 until mappedCorners.size) {
                    path.lineTo(mappedCorners[i].x, mappedCorners[i].y)
                }
                path.close()
                
                canvas.drawPath(path, paint)
                
                // Draw center dot
                val centerX = mappedCorners.map { it.x }.average().toFloat()
                val centerY = mappedCorners.map { it.y }.average().toFloat()
                canvas.drawCircle(centerX, centerY, 8f, centerPaint.apply { color = paint.color })
                
                // Draw ID label
                textPaint.color = paint.color
                canvas.drawText(
                    detection.id.toString(),
                    centerX + 15f,
                    centerY - 10f,
                    textPaint
                )
                
                return
            }
        }
        
        // Fallback: draw center point only
        val centerPoint = mapper.mapToPreview(
            detection.centerX.toFloat(),
            detection.centerY.toFloat(),
            imageProxy,
            previewView
        )
        
        if (centerPoint != null) {
            // Draw circle at center
            canvas.drawCircle(centerPoint.x, centerPoint.y, 20f, paint)
            canvas.drawCircle(centerPoint.x, centerPoint.y, 8f, centerPaint.apply { color = paint.color })
            
            // Draw ID label
            textPaint.color = paint.color
            canvas.drawText(
                detection.id.toString(),
                centerPoint.x + 25f,
                centerPoint.y - 5f,
                textPaint
            )
        }
    }
=======
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
>>>>>>> d21a7b094031335437223262a15276636a5ec8ac
}
