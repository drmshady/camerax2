package com.example.camerax

import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.util.Log
import java.io.File

object ImageSizeUtil {

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
            ExifInterface.ORIENTATION_NORMAL
        }
    }
}
