package com.example.camerax

<<<<<<< HEAD
import android.graphics.Matrix
import android.graphics.PointF
=======
>>>>>>> d21a7b094031335437223262a15276636a5ec8ac
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
<<<<<<< HEAD
import androidx.camera.view.transform.OutputTransform

/**
 * Maps marker detection coordinates from ImageAnalysis (ImageProxy) space
 * to PreviewView display space, handling:
 * - PreviewView scaleType (FILL_CENTER crop vs FIT_CENTER)
 * - Rotation
 * - ImageProxy cropRect
 * - ROI offsets/downscaling from marker detector
 */
class MarkerOverlayMapper {
    
    /**
     * Transform a point from ImageAnalysis coordinates to PreviewView coordinates.
     * 
     * @param imageX X coordinate in ImageAnalysis ImageProxy space
     * @param imageY Y coordinate in ImageAnalysis ImageProxy space
     * @param imageProxy The ImageProxy from ImageAnalysis
     * @param previewView The PreviewView
     * @return PointF in PreviewView display coordinates, or null if transform unavailable
     */
    fun mapToPreview(
        imageX: Float,
        imageY: Float,
        imageProxy: ImageProxy,
        previewView: PreviewView
    ): PointF? {
        try {
            // Get PreviewView output transform
            val outputTransform: OutputTransform = previewView.outputTransform ?: return null
            
            // Create ImageProxy transform factory
            val factory = ImageProxyTransformFactory()
            factory.isUsingRotationDegrees = true
            factory.isUsingCropRect = true
            
            // Create coordinate transform from ImageAnalysis to PreviewView
            val transform = CoordinateTransform(
                factory.getOutputTransform(imageProxy),
                outputTransform
            )
            
            // Transform the point using a Matrix
            val matrix = Matrix()
            transform.transform(matrix)
            
            val points = floatArrayOf(imageX, imageY)
            matrix.mapPoints(points)
            
            return PointF(points[0], points[1])
        } catch (e: Exception) {
            // Transformation may fail if views not laid out yet
            return null
        }
    }
    
    /**
     * Transform all corners of a detected marker.
     */
    fun mapCornersToPreview(
        corners: List<Pair<Double, Double>>,
        imageProxy: ImageProxy,
        previewView: PreviewView
    ): List<PointF>? {
        try {
            val outputTransform: OutputTransform = previewView.outputTransform ?: return null
            val factory = ImageProxyTransformFactory()
            factory.isUsingRotationDegrees = true
            factory.isUsingCropRect = true
            
            val transform = CoordinateTransform(
                factory.getOutputTransform(imageProxy),
                outputTransform
            )
            
            // Get transformation matrix
            val matrix = Matrix()
            transform.transform(matrix)
            
            val result = mutableListOf<PointF>()
            val points = FloatArray(corners.size * 2)
            
            // Pack all corner points
            for (i in corners.indices) {
                points[i * 2] = corners[i].first.toFloat()
                points[i * 2 + 1] = corners[i].second.toFloat()
            }
            
            // Transform all at once
            matrix.mapPoints(points)
            
            // Unpack
            for (i in corners.indices) {
                result.add(PointF(points[i * 2], points[i * 2 + 1]))
            }
            
            return result
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Creates a transform matrix manually (fallback if CameraX transform unavailable).
     * This is less accurate but provides basic mapping.
     */
    fun createFallbackMatrix(
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        rotationDegrees: Int
    ): Matrix {
        val matrix = Matrix()
        
        // Handle rotation first
        when (rotationDegrees) {
            90 -> {
                matrix.postRotate(90f)
                matrix.postTranslate(imageHeight.toFloat(), 0f)
            }
            180 -> {
                matrix.postRotate(180f)
                matrix.postTranslate(imageWidth.toFloat(), imageHeight.toFloat())
            }
            270 -> {
                matrix.postRotate(270f)
                matrix.postTranslate(0f, imageWidth.toFloat())
            }
        }
        
        // Scale to fit view (simplified - assumes FILL_CENTER crop)
        val imageAspect = if (rotationDegrees == 90 || rotationDegrees == 270) {
            imageHeight.toFloat() / imageWidth.toFloat()
        } else {
            imageWidth.toFloat() / imageHeight.toFloat()
        }
        
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        
        val scale = if (imageAspect > viewAspect) {
            // Image wider - fit height
            viewHeight.toFloat() / if (rotationDegrees == 90 || rotationDegrees == 270) imageWidth.toFloat() else imageHeight.toFloat()
        } else {
            // Image taller - fit width
            viewWidth.toFloat() / if (rotationDegrees == 90 || rotationDegrees == 270) imageHeight.toFloat() else imageWidth.toFloat()
        }
        
        matrix.postScale(scale, scale)
        
        return matrix
=======

/**
 * Maps ImageAnalysis / ImageProxy coordinates (full-frame) into PreviewView coordinates,
 * correctly handling PreviewView scale/crop (FILL_CENTER vs FIT_CENTER), rotation, and cropRect.
 *
 * IMPORTANT:
 * - This is safe to run on the ImageAnalysis executor (no UI blocking).
 * - Only needs ImageProxy metadata (rotationDegrees + cropRect + size).
 */
class MarkerOverlayMapper(
    private val previewView: PreviewView
) {
    private val factory: ImageProxyTransformFactory = ImageProxyTransformFactory().apply {
        // These property names are stable across CameraX 1.2+.
        isUsingCropRect = true
        isUsingRotationDegrees = true
    }

    fun mapToView(imageProxy: ImageProxy, detections: List<TagDetection>): List<OverlayDetection> {
        if (detections.isEmpty()) return emptyList()

        val target = previewView.outputTransform ?: return emptyList()
        val source = factory.getOutputTransform(imageProxy)

        val ct = CoordinateTransform(source, target)

        val out = ArrayList<OverlayDetection>(detections.size)
        for (d in detections) {
            // Center
            val center = floatArrayOf(d.centerX.toFloat(), d.centerY.toFloat())
            ct.mapPoints(center)

            // Corners (optional)
            val corners = d.corners
            val mappedCorners: FloatArray? = if (corners != null && corners.size >= 4) {
                val pts = FloatArray(corners.size * 2)
                var i = 0
                for (p in corners) {
                    pts[i++] = p.first.toFloat()
                    pts[i++] = p.second.toFloat()
                }
                ct.mapPoints(pts)
                pts
            } else null

            out.add(
                OverlayDetection(
                    id = d.id,
                    centerX = center[0],
                    centerY = center[1],
                    corners = mappedCorners,
                    quality = d.quality
                )
            )
        }
        return out
>>>>>>> d21a7b094031335437223262a15276636a5ec8ac
    }
}
