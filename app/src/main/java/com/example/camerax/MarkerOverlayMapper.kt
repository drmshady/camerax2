package com.example.camerax

import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory

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
    }
}
