package app.morsecode.android.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.model.FileType
import app.morsecode.android.core.model.MediaItem
import app.morsecode.android.core.util.DeviceTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * §6.9.2 THUMBNAILS: real decoded thumbnails for photos and videos, the real
 * packaged icon for APKs, real embedded album art for music with the note tile
 * as fallback.
 *
 * The cache is a tier-scaled LRU (§13): decode size and budget shrink on a low
 * tier, capability never does. Keys carry the item's mtime so a file that
 * changed cannot serve a stale bitmap (§7.1 uses the same id+mtime rule).
 *
 * Bind tokens are the caller's job (§6.9.2): the view re-checks its token
 * before applying a bitmap. This class is deliberately not view-aware — it
 * only answers "what does this item look like".
 */
class ThumbnailCache(
    context: Context,
    private val tier: DeviceTier,
    private val logStore: LogStore? = null,
) {

    private val appContext = context.applicationContext
    private val sizePx = tier.thumbnailPx

    private val cache = object : LruCache<String, Bitmap>(tier.thumbnailCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** id + mtime, so an edited file never serves its old thumbnail. */
    fun keyFor(item: MediaItem): String = "${item.id}:${item.dateMillis}:$sizePx"

    fun cached(item: MediaItem): Bitmap? = cache.get(keyFor(item))

    suspend fun load(item: MediaItem): Bitmap? {
        cached(item)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) { decode(item) } ?: return null
        cache.put(keyFor(item), bitmap)
        return bitmap
    }

    fun clear() = cache.evictAll()

    private fun decode(item: MediaItem): Bitmap? = try {
        when (item.type) {
            FileType.IMAGE -> decodeImage(item.uri)
            FileType.VIDEO -> decodeVideo(item)
            FileType.AUDIO -> decodeAlbumArt(item)
            FileType.APK -> null // The launcher icon comes from PackageManager.
            else -> null
        }
    } catch (e: Exception) {
        // A missing thumbnail is a placeholder, never a crash — but it IS
        // logged, because a whole list of blanks means the loader is unwired
        // and must be root-caused rather than papered over (§6.9.2).
        logStore?.w("Thumbnail failed for ${item.name}: ${e.javaClass.simpleName}")
        null
    }

    private fun decodeImage(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            return appContext.contentResolver.loadThumbnail(
                uri,
                android.util.Size(sizePx, sizePx),
                null,
            )
        }
        // Below 29: measure first, then decode at the smallest sample size
        // that still covers the target — never the full bitmap (§13, §20.10).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        return appContext.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun decodeVideo(item: MediaItem): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            return appContext.contentResolver.loadThumbnail(
                item.uri,
                android.util.Size(sizePx, sizePx),
                null,
            )
        }
        @Suppress("DEPRECATION")
        return MediaStore.Video.Thumbnails.getThumbnail(
            appContext.contentResolver,
            item.id,
            MediaStore.Video.Thumbnails.MINI_KIND,
            null,
        )
    }

    private fun decodeAlbumArt(item: MediaItem): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, item.uri)
            val bytes = retriever.embeddedPicture ?: return null
            val options = BitmapFactory.Options()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            options.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
                // Releasing twice must not mask the real result.
            }
        }
    }

    /** Largest power-of-two sample that still fills the target box. */
    fun sampleSizeFor(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= sizePx && height / (sample * 2) >= sizePx) {
            sample *= 2
        }
        return sample
    }
}
