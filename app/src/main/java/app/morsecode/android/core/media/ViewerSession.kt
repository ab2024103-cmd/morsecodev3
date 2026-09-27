package app.morsecode.android.core.media

import app.morsecode.android.core.model.MediaItem

/**
 * The list the viewer and the player page through (§6.10, §6.11).
 *
 * §6.10 requires ‹ › — here, a swipe — to move "through the same day-grouped
 * list" the grid showed, bound to the item that was tapped. Handing a few
 * thousand items through an Intent is not possible, and re-querying would
 * risk a different order than the one on screen, so the grid publishes the
 * exact list it rendered and the viewer reads it.
 *
 * Process-scoped, like every other coordinator (§8.2).
 */
object ViewerSession {

    private var items: List<MediaItem> = emptyList()

    var startIndex: Int = 0
        private set

    fun open(items: List<MediaItem>, index: Int) {
        this.items = items
        startIndex = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }

    fun list(): List<MediaItem> = items

    fun itemAt(index: Int): MediaItem? = items.getOrNull(index)

    val count: Int get() = items.size

    fun clear() {
        items = emptyList()
        startIndex = 0
    }
}
