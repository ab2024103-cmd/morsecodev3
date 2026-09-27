package app.morsecode.android.core.webshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * §7.2: "GET /thumbnail — sized, server-cached thumbnails (id + mtime key)".
 *
 * Stage 14 served the original bytes, which was correct but wasteful: a
 * 4 000-photo grid moved gigabytes to draw 140 px tiles. §20.10 treats that as
 * a defect rather than an inherent cost, so the server now decodes once, keeps
 * the result on disk, and answers from the cache afterwards.
 *
 * The key is id + mtime (§7.1's own rule), so an edited file cannot serve its
 * old picture.
 */
class ThumbnailStore(context: Context, private val maxPixels: Int = DEFAULT_SIZE) {

    private val dir = File(context.applicationContext.cacheDir, "webthumbs").apply { mkdirs() }

    /** id + mtime: an edited file gets a different key and a fresh decode. */
    fun keyFor(path: String, modifiedMillis: Long): String =
        "${path.hashCode()}-$modifiedMillis-$maxPixels.jpg"

    fun cached(path: String, modifiedMillis: Long): File? =
        File(dir, keyFor(path, modifiedMillis)).takeIf { it.exists() && it.length() > 0 }

    /**
     * Returns a cached thumbnail, decoding it first if necessary. Falls back to
     * null — never to a broken image — and the caller then streams the
     * original, which is honest rather than blank (§6.19).
     */
    fun thumbnail(source: File): File? {
        if (!source.exists()) return null
        val key = keyFor(source.absolutePath, source.lastModified())
        val target = File(dir, key)
        if (target.exists() && target.length() > 0) return target

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPixels)
            }
            val bitmap: Bitmap = BitmapFactory.decodeFile(source.absolutePath, options) ?: return null
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            bitmap.recycle()
            target
        } catch (e: Exception) {
            null
        }
    }

    /** Largest power-of-two sample that still covers the target box. */
    fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2
        return sample
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val DEFAULT_SIZE = 320
        const val QUALITY = 78
    }
}
