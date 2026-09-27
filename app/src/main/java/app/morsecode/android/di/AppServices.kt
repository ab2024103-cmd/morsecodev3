package app.morsecode.android.di

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import app.morsecode.android.core.data.HistoryStore
import app.morsecode.android.core.data.JournalStore
import app.morsecode.android.core.data.Prefs
import app.morsecode.android.core.data.RecentDevices
import app.morsecode.android.core.network.Discovery
import app.morsecode.android.core.network.LanTransport
import app.morsecode.android.core.network.NearbyTransport
import app.morsecode.android.core.network.ConnectionCoordinator
import app.morsecode.android.core.network.ConsentRequests
import app.morsecode.android.core.network.SessionRegistry
import app.morsecode.android.core.storage.Conflicts
import app.morsecode.android.core.storage.ReceiveSinkFactory
import app.morsecode.android.core.transfer.IncomingFiles
import app.morsecode.android.core.transfer.TransferEngine
import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.media.MediaLibrary
import app.morsecode.android.core.media.ThumbnailCache
import app.morsecode.android.core.storage.Destinations
import app.morsecode.android.core.storage.SafStore
import app.morsecode.android.core.util.DeviceTier
import app.morsecode.android.core.util.Ids

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

    /**
     * §8.2: coordinators are process-scoped. The engine owns the queue and the
     * session for the life of the process, never for the life of a screen.
     */
    val engineScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /** §8.2 journal: written on every state transition (§9.7 recovery). */
    val journalStore: JournalStore by lazy { JournalStore(File(appContext.filesDir, "journal.json")) }

    /** §6.12 history, written by the engine's single complete() path (§9.6). */
    val historyStore: HistoryStore by lazy { HistoryStore(File(appContext.filesDir, "history.json")) }

    /** §9 the single source of truth for queue and session state. */
    val transferEngine: TransferEngine by lazy {
        TransferEngine(
            scope = engineScope,
            journal = journalStore,
            history = historyStore,
            logStore = logStore,
        )
    }

    /** §17.4: a transport id and a name. Recency shortens discovery, never consent. */
    val recentDevices: RecentDevices by lazy { RecentDevices(appContext) }

    /** §8.1: one live session per peer; §6.16 reads it to avoid re-prompting. */
    val sessionRegistry: SessionRegistry by lazy { SessionRegistry() }

    /** §9.5 default policy is "Rename duplicates" (§6.13). */
    val conflictPolicy: Conflicts.BatchPolicy by lazy {
        Conflicts.BatchPolicy(Conflicts.Policy.RENAME)
    }

    val receiveSinks: ReceiveSinkFactory by lazy {
        ReceiveSinkFactory(appContext, destinations, logStore)
    }

    /** §8.2: incoming files are owned here, never by a screen. */
    val incomingFiles: IncomingFiles by lazy {
        IncomingFiles(
            engine = transferEngine,
            sinks = receiveSinks,
            conflictPolicy = conflictPolicy,
            logStore = logStore,
            verifyWithSha = deviceTier.verifyWithSha,
        )
    }

    /** A stable per-install id; the peer sees it in HELLO and in every beacon. */
    val deviceId: String by lazy {
        val prefs = appContext.getSharedPreferences("morsecode.identity", Context.MODE_PRIVATE)
        prefs.getString("deviceId", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }
    }

    val deviceName: String get() = android.os.Build.MODEL ?: Ids.APP_NAME

    /** §11.2 LAN transport: TCP :33456, one data connection per file. */
    val lanTransport: LanTransport by lazy {
        LanTransport(
            scope = engineScope,
            deviceId = deviceId,
            deviceName = deviceName,
            incoming = incomingFiles,
            openContent = { item ->
                runCatching { appContext.contentResolver.openInputStream(item.file.uri) }.getOrNull()
            },
            logStore = logStore,
        )
    }

    /**
     * §11.3 Nearby, behind the same Transport interface as LAN. A device
     * without Play Services simply never starts it and stays on LAN (§20.6).
     */
    val nearbyTransport: NearbyTransport by lazy {
        NearbyTransport(
            context = appContext,
            scope = engineScope,
            deviceId = deviceId,
            deviceName = deviceName,
            incoming = incomingFiles,
            openContent = { item ->
                runCatching { appContext.contentResolver.openInputStream(item.file.uri) }.getOrNull()
            },
            // §11.1: Nearby publishes into the SAME deduplicated list as LAN.
            onPeerFound = { peer -> discovery.publish(peer) },
            onPeerLost = { endpointId -> discovery.forget(endpointId) },
            logStore = logStore,
        )
    }

    /** §11.1 one discovery component, one deduplicated peer list. */
    val discovery: Discovery by lazy {
        Discovery(
            context = appContext,
            scope = engineScope,
            deviceId = deviceId,
            deviceName = deviceName,
            logStore = logStore,
        )
    }

    /** §17.2 consent gates, brokered process-wide (§6.16). */
    val consentRequests: ConsentRequests by lazy { ConsentRequests() }

    /**
     * Owns discovery, the transports and the live session (§8.2, §3.7).
     * Its `install()` wires consent BEFORE anything can start listening.
     */
    val connections: ConnectionCoordinator by lazy {
        ConnectionCoordinator(
            context = appContext,
            engine = transferEngine,
            discovery = discovery,
            lan = lanTransport,
            nearby = nearbyTransport,
            sessions = sessionRegistry,
            consent = consentRequests,
            recentDevices = recentDevices,
            logStore = logStore,
        )
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** True once [init] has run; guards services reached from static entry points. */
    fun isInitialised(): Boolean = ::appContext.isInitialized
}
