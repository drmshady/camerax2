package com.example.camerax

import android.graphics.BitmapFactory
<<<<<<< HEAD
import android.media.ExifInterface
import android.util.Log
=======
import androidx.exifinterface.media.ExifInterface
>>>>>>> d21a7b094031335437223262a15276636a5ec8ac
import java.io.File

object ImageSizeUtil {

<<<<<<< HEAD
    private const val TAG = "ImageSizeUtil"

    /**
     * Reads the dimensions of a saved JPEG image efficiently.
     *
     * First attempts to use BitmapFactory.Options.inJustDecodeBounds=true to read dimensions
     * without loading the full bitmap into memory.
     *
     * If that returns 0x0 (some cameras/encoders don't write standard JPEG headers properly),
     * falls back to reading EXIF TAG_IMAGE_WIDTH and TAG_IMAGE_LENGTH.
     *
     * @param path Absolute path to the JPEG file
     * @return Pair<width, height> in pixels, or (0, 0) if unable to determine
     */
    fun readSavedJpegSize(path: String): Pair<Int, Int> {
        val file = File(path)
        if (!file.exists() || !file.canRead()) {
            Log.w(TAG, "File not readable: $path")
            return Pair(0, 0)
        }

        // First try: BitmapFactory.Options with inJustDecodeBounds
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        try {
            BitmapFactory.decodeFile(path, options)
            val width = options.outWidth
            val height = options.outHeight

            if (width > 0 && height > 0) {
                return Pair(width, height)
            }

            Log.d(TAG, "BitmapFactory returned 0x0, trying EXIF fallback for: $path")
        } catch (e: Exception) {
            Log.w(TAG, "BitmapFactory decode failed: ${e.message}, trying EXIF fallback")
        }

        // Fallback: Read from EXIF tags
        try {
            val exif = ExifInterface(path)

            val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)

            if (width > 0 && height > 0) {
                return Pair(width, height)
            }

            Log.w(TAG, "EXIF tags also returned 0x0 for: $path")
        } catch (e: Exception) {
            Log.e(TAG, "EXIF read failed for $path: ${e.message}", e)
        }

        return Pair(0, 0)
    }

    /**
     * Reads the EXIF orientation tag from a JPEG image.
     *
     * EXIF orientation values:
     * 1 = Normal (0°)
     * 2 = Flip horizontal
     * 3 = Rotate 180°
     * 4 = Flip vertical
     * 5 = Flip horizontal + Rotate 270° CW
     * 6 = Rotate 90° CW
     * 7 = Flip horizontal + Rotate 90° CW
     * 8 = Rotate 270° CW
     *
     * @param path Absolute path to the JPEG file
     * @return EXIF orientation value (1-8), default 1 if not found or on error
     */
    fun readExifOrientation(path: String): Int {
        val file = File(path)
        if (!file.exists() || !file.canRead()) {
            Log.w(TAG, "File not readable: $path")
            return ExifInterface.ORIENTATION_NORMAL
        }

        return try {
            val exif = ExifInterface(path)
            exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read EXIF orientation for $path: ${e.message}", e)
=======
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
>>>>>>> d21a7b094031335437223262a15276636a5ec8ac
            ExifInterface.ORIENTATION_NORMAL
        }
    }
}
