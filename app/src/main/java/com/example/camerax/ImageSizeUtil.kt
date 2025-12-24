package com.example.camerax

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.File

object ImageSizeUtil {

    /**
     * Reads the actual encoded pixel matrix size of the saved JPEG/PNG without decoding the bitmap.
     * NOTE: This is the stored pixel matrix size (not "rotation-corrected").
     */
    fun readImageSize(file: File): Pair<Int, Int>? {
        // Fast path (JPEG/PNG): bounds decode, no bitmap allocation
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            return opts.outWidth to opts.outHeight
        }

        // EXIF fallback (best-effort)
        return try {
            val exif = ExifInterface(file)
            val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            if (w > 0 && h > 0) w to h else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Reads EXIF orientation tag value (1..8). Returns 1 (normal) if missing/unknown.
     */
    fun readExifOrientation(file: File): Int {
        return try {
            val exif = ExifInterface(file)
            val v = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            when (v) {
                ExifInterface.ORIENTATION_NORMAL,
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
                ExifInterface.ORIENTATION_ROTATE_180,
                ExifInterface.ORIENTATION_FLIP_VERTICAL,
                ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_TRANSVERSE,
                ExifInterface.ORIENTATION_ROTATE_270 -> v
                else -> ExifInterface.ORIENTATION_NORMAL
            }
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }
}
