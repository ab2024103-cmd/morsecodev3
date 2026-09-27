package app.morsecode.android.core.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * §13 DEVICE PERFORMANCE TIERING.
 *
 * Tiering scales pre-hash limits, thumbnail sizes, page sizes, thread pools and
 * animation. It NEVER disables transfer, discovery, broadcast, WebShare,
 * pause/resume, conflict resolution or any other functional capability.
 */
class DeviceTier(context: Context) {

    private val appContext = context.applicationContext

    val isLowEndDevice: Boolean = run {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lowRam = am != null && am.isLowRamDevice
        Build.VERSION.SDK_INT < 23 ||
            lowRam ||
            Runtime.getRuntime().availableProcessors() <= 2
    }

    /** Chunk-handling thread pools cap at 2 on the low tier (§13). */
    val chunkThreads: Int = if (isLowEndDevice) 2 else 4

    /** §9.4 PREHASH_LIMIT = 256 MB; the low tier hashes less. */
    val preHashLimitBytes: Long = if (isLowEndDevice) 64L * 1024 * 1024 else 256L * 1024 * 1024

    /** Post-transfer sha256 is replaced by a size-only check on the lowest tier, with a log line (§9.4). */
    val verifyWithSha: Boolean = !isLowEndDevice

    val thumbnailPx: Int = if (isLowEndDevice) 128 else 256

    /** LRU bitmap cache budget in bytes (§3.3: no image-loading library needed). */
    val thumbnailCacheBytes: Int = if (isLowEndDevice) 3 * 1024 * 1024 else 12 * 1024 * 1024

    /** MediaStore page size (§12.5 INV-10). */
    val pageSize: Int = if (isLowEndDevice) 60 else 200

    /** §10.2 peer cap: default 4, user-settable 2–8, low tier 2. */
    val maxBroadcastPeers: Int = if (isLowEndDevice) 2 else 8

    /**
     * §10.2 / §13: a Nearby broadcast is capped at 3 peers, and 2 on the low
     * tier — the radio, not the app, is the limit. Nearby stays fully
     * available either way; only the count changes.
     */
    val maxNearbyPeers: Int = if (isLowEndDevice) 2 else 3

    /**
     * §4.11: everything obeys ANIMATOR_DURATION_SCALE; on a low-tier device the
     * radar degrades to a static ring and progress animation is disabled.
     */
    fun animationScale(): Float {
        if (isLowEndDevice) return 0f
        return try {
            Settings.Global.getFloat(
                appContext.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        } catch (e: Exception) {
            1f
        }
    }

    fun animationsEnabled(): Boolean = animationScale() > 0f

    /** Scales a design duration by the system setting; 0 means "apply instantly". */
    fun scaled(durationMs: Long): Long = (durationMs * animationScale()).toLong()
}
