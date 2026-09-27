package app.morsecode.android.core.model

import android.net.Uri

/**
 * The shared vocabulary (§8.1 `core/model/Models.kt`).
 *
 * Everything the media library, the file browser and later the transfer engine
 * hand to a screen is one of these types. Screens never see a `Cursor`, a
 * `MediaStore` column or a raw path string (§12.5: one repository).
 */

/** §6.9 tabs and §12.5 categories. */
enum class MediaCategory {
    PHOTOS,
    VIDEOS,
    MUSIC,
    APPS,
    FILES,

    /** §6.9 "CATEGORIES" rows inside the Files tab. */
    DOCUMENTS,
    EBOOKS,
    ARCHIVES,
    APKS,
    LARGE_FILES,
}

/**
 * §4.9 file-type colour families. Kept separate from [MediaCategory] because a
 * `.pdf` inside the Photos folder is still a document tile.
 */
enum class FileType { IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APK, UNKNOWN }

/** §6.9.x sort keys. Folders always sort before files, whichever key is active. */
enum class SortKey { DATE_MODIFIED, NAME, SIZE, TYPE }

data class SortOrder(val key: SortKey = SortKey.DATE_MODIFIED, val descending: Boolean = true)

/**
 * One item in any listing.
 *
 * @param dateMillis always MILLISECONDS (§12.5 DATE UNITS). The library
 *   converts at the cursor, so nothing downstream can mix seconds and
 *   milliseconds and send a file to 1 Jan 1970.
 */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val dateMillis: Long,
    val mimeType: String?,
    val type: FileType,
    /** Video and audio only; 0 when unknown. */
    val durationMillis: Long = 0,
    /** Music only; §6.9 renders "Unknown artist", never a raw `<unknown>`. */
    val artist: String? = null,
    /** Apps only. */
    val packageName: String? = null,
    /** Present for on-disk items; absent for MediaStore-only or SAF items. */
    val path: String? = null,
)

/** §12.5 folderInfo(): name, count, size — plus the newest item for a preview. */
data class FolderInfo(
    val name: String,
    val path: String,
    val itemCount: Int,
    val sizeBytes: Long,
    val newestItem: MediaItem? = null,
)

/** §6.9 category row: a count that is still being computed is [PENDING]. */
data class CategoryCount(val category: MediaCategory, val count: Int) {
    val isPending: Boolean get() = count == PENDING

    companion object {
        /**
         * §6.9: while a count is still being computed show a shimmer, never a
         * hard "0" and never an empty state until the scan has finished.
         */
        const val PENDING = -1
    }
}

/** One page of a category (§12.5 INV-10). */
data class MediaPage(
    val items: List<MediaItem>,
    val offset: Int,
    /** True when the cursor had more rows after this page. */
    val hasMore: Boolean,
)

/** A row in a directory listing; folders are first-class, selectable objects (§6.9). */
data class DirectoryEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val dateMillis: Long,
    val type: FileType,
    val childCount: Int = 0,
)

/**
 * §12.2 GRACEFUL DENIAL.
 *
 * "Cannot be read because of scoped storage" is a DISTINCT condition from
 * "zero children" and has its own type here, so a screen physically cannot
 * render a denied folder as an empty one — which §12.2 calls release-blocking
 * because the two are indistinguishable to the user.
 */
sealed class DirectoryListing {

    data class Ok(val path: String, val entries: List<DirectoryEntry>) : DirectoryListing()

    /** Render "Access needed to view this folder — Grant access" (§6.19, §12.2). */
    data class AccessDenied(val path: String, val suggestedTreeUri: Uri?) : DirectoryListing()

    data class Missing(val path: String) : DirectoryListing()
}

// ---------------------------------------------------------------------------
// §9 TRANSFER ENGINE — the item model and everything derived from it.
// ---------------------------------------------------------------------------

enum class Direction { SENDING, RECEIVING }

/** §9.1 states. QUEUED → IN_PROGRESS → COMPLETED | SKIPPED | PAUSED | CANCELLED | FAILED. */
enum class TransferState {
    QUEUED,
    IN_PROGRESS,
    COMPLETED,
    SKIPPED,
    PAUSED,
    CANCELLED,
    FAILED;

    /** A state the worker will never move on from by itself. */
    val isTerminal: Boolean
        get() = this == COMPLETED || this == SKIPPED || this == CANCELLED || this == FAILED

    /** §9.6: these three are counted separately in the batch summary. */
    val countsAsFailure: Boolean get() = this == FAILED
}

/** The file a queue item refers to, independent of how it is moved. */
data class TransferFile(
    val displayName: String,
    val uri: Uri,
    val mime: String?,
    val size: Long,
)

/** §9.1 ITEM MODEL, field for field. */
data class TransferItem(
    val id: String,
    val batchId: String,
    val direction: Direction,
    val state: TransferState,
    val file: TransferFile,
    /** Preserved folder structure, so AlbumA/img1.jpg never collides with AlbumB/img1.jpg (§9.5). */
    val relativePath: String? = null,
    val totalBytes: Long = file.size,
    val bytesTransferred: Long = 0,
    val speedBps: Long = 0,
    val resumeOffset: Long = 0,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val peerId: String? = null,
    /** Cached SHA-256 for files under PREHASH_LIMIT (§9.4). */
    val sha256: String? = null,
) {
    val progress: Float
        get() = if (totalBytes <= 0) 0f else (bytesTransferred.toFloat() / totalBytes).coerceIn(0f, 1f)

    /** The path conflicts are evaluated against — full relative path (§9.5). */
    val conflictKey: String get() = relativePath ?: file.displayName
}

/** §9.3 sendFile outcomes. */
sealed class SendResult {
    object Completed : SendResult()
    data class SkippedAlreadyPresent(val reason: String) : SendResult()
    object Paused : SendResult()
    object Cancelled : SendResult()
    data class Failed(val error: String) : SendResult()
}

/** §9.6 one coalesced summary per batch, derived from the queue — never recomputed. */
data class BatchSummary(
    val batchId: String,
    val sent: Int,
    val failed: Int,
    val skipped: Int,
    val totalBytes: Long,
    val elapsedMillis: Long,
) {
    val averageSpeedBps: Long
        get() = if (elapsedMillis <= 0) 0 else (totalBytes * 1000L) / elapsedMillis

    /** [Retry failed] appears only when something actually failed (§9.6). */
    val hasFailures: Boolean get() = failed > 0
}

/**
 * §20.3 EVENTS vs STATE: one-shot signals, delivered once and consumed. State
 * that a screen renders lives in the queue's flow instead.
 */
sealed class EngineEvent {
    data class PeerConnected(val peerId: String, val peerName: String) : EngineEvent()
    data class PeerDisconnected(val peerId: String, val reason: String) : EngineEvent()
    data class ItemFailed(val itemId: String, val error: String) : EngineEvent()
    data class BatchCompleted(val summary: BatchSummary) : EngineEvent()
    data class ConflictRaised(val itemId: String, val conflictKey: String) : EngineEvent()
    data class ResumeAvailable(val peerName: String, val remainingItems: Int) : EngineEvent()
}

/**
 * §20.3: observable connection state must distinguish "never connected" from
 * "was connected, now closed", so a fresh screen never announces
 * "connection closed".
 */
sealed class SessionState {
    object NeverConnected : SessionState()
    data class Connected(val peerId: String, val peerName: String) : SessionState()
    data class Closed(val peerId: String, val peerName: String, val reason: String) : SessionState()
}

// ---------------------------------------------------------------------------
// §11.1 UNIFIED DISCOVERY — one deduplicated peer list, whatever found it.
// ---------------------------------------------------------------------------

/**
 * One visible peer. §6.3 renders these in a single list regardless of
 * transport, each with its badge and address; §11.1 forbids a hard transport
 * switch that would hide half the network.
 */
data class DiscoveredPeer(
    /** Transport-level id; also what recent devices store (§17.4). */
    val deviceId: String,
    val name: String,
    /** LAN: "192.168.1.42". Nearby: the endpoint id. Web: the browser address. */
    val address: String,
    val port: Int,
    val transport: app.morsecode.android.core.network.TransportKind,
    val protocolVersion: Int,
    /** §10: the peer announced that it is broadcasting. */
    val broadcasting: Boolean = false,
    val lastSeenMillis: Long = 0,
) {
    val hostPort: String get() = "$address:$port"
}
