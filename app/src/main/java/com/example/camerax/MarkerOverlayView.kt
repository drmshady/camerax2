package com.example.camerax

import android.content.Context
import android.graphics.Canvas
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
}
