package app.morsecode.android.di

import android.content.Context
import app.morsecode.android.core.data.Prefs
import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.media.MediaLibrary
import app.morsecode.android.core.media.ThumbnailCache
import app.morsecode.android.core.storage.Destinations
import app.morsecode.android.core.storage.SafStore
import app.morsecode.android.core.util.DeviceTier

/**
 * Manual service locator (§3.1): no DI framework, no annotation processors.
 *
 * Every process-scoped singleton is created lazily here and reached through
 * `AppServices.<name>`. Coordinators are process-scoped, never screen-scoped
 * (§8.2) — a service owned by a fragment stops working the moment that
 * fragment is not visible.
 */
object AppServices {

    private lateinit var appContext: Context

    val context: Context get() = appContext

    val logStore: LogStore by lazy { LogStore(appContext) }

    val prefs: Prefs by lazy { Prefs(appContext) }

    /** §13 tiering: scales work, never functionality. */
    val deviceTier: DeviceTier by lazy { DeviceTier(appContext) }

    /** §12.5: the one repository behind every listing; no screen queries MediaStore. */
    val mediaLibrary: MediaLibrary by lazy { MediaLibrary(appContext, deviceTier, logStore) }

    /** §6.9.2 thumbnails, tier-scaled (§13). */
    val thumbnails: ThumbnailCache by lazy { ThumbnailCache(appContext, deviceTier, logStore) }

    /** §12.1 persisted SAF tree grants. */
    val safStore: SafStore by lazy { SafStore(appContext, logStore) }

    /** §12.4 the single write destination everything follows. */
    val destinations: Destinations by lazy { Destinations(appContext, logStore) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** True once [init] has run; guards services reached from static entry points. */
    fun isInitialised(): Boolean = ::appContext.isInitialized
}
