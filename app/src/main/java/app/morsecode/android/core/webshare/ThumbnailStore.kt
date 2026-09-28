package app.morsecode.android.core.webshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.Collections

/**
 * §7.2: sized, server-cached thumbnails keyed on id + mtime.
 *
 * The cache is deliberately bounded. A cache that only ever grows is not a
 * cache on a low-storage phone: it becomes a second media library and slowly
 * makes WebShare fail. The least-recently used file is evicted first, with
 * successful reads touching it so frequently browsed albums remain warm.
 */
class ThumbnailStore(
    context: Context,
    private val maxPixels: Int = DEFAULT_SIZE,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val dir = File(context.applicationContext.cacheDir, "webthumbs").apply { mkdirs() }

    /** id + mtime: an edited file gets a different key and a fresh decode. */
    fun keyFor(path: String, modifiedMillis: Long): String =
        "${path.hashCode()}-$modifiedMillis-$maxPixels.jpg"

    fun cached(path: String, modifiedMillis: Long): File? =
        File(dir, keyFor(path, modifiedMillis)).takeIf { it.exists() && it.length() > 0 }?.also(::touch)

    /**
     * Returns a cached thumbnail, decoding it first if necessary. Falls back to
     * null — never to a broken image — and the caller then streams the
     * original, which is honest rather than blank (§6.19).
     */
    @Synchronized
    fun thumbnail(source: File): File? {
        if (!source.exists()) return null
        val key = keyFor(source.absolutePath, source.lastModified())
        val target = File(dir, key)
        if (target.exists() && target.length() > 0) return target.also(::touch)

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPixels)
            }
            val bitmap: Bitmap = BitmapFactory.decodeFile(source.absolutePath, options) ?: return null
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            bitmap.recycle()
            touch(target)
            evictToBudget()
            target.takeIf { it.exists() && it.length() > 0 }
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    /**
     * Least-recently-used eviction. Returns how many entries were deleted, so
     * the behaviour is observable in tests and diagnostics rather than a
     * best-effort side effect hidden in a decode path.
     */
    @Synchronized
    fun evictToBudget(): Int {
        val files = dir.listFiles()?.filter { it.isFile }?.toMutableList() ?: return 0
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return 0
        // Manual comparator, API-23 safe — Comparator.comparingLong is a Java
        // 8 default helper and cannot be used without desugaring.
        Collections.sort(files, java.util.Comparator { left, right ->
            when {
                left.lastModified() < right.lastModified() -> -1
                left.lastModified() > right.lastModified() -> 1
                else -> left.name.compareTo(right.name)
            }
        })
        var removed = 0
        for (file in files) {
            if (total <= maxBytes) break
            val length = file.length()
            if (file.delete()) {
                total -= length
                removed++
            }
        }
        return removed
    }

    fun cachedBytes(): Long = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

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

    private fun touch(file: File) {
        // LRU uses the filesystem timestamp so it survives process recreation.
        file.setLastModified(now())
    }

    private companion object {
        const val DEFAULT_SIZE = 320
        const val DEFAULT_MAX_BYTES = 32L * 1024L * 1024L
        const val QUALITY = 78
    }
}
