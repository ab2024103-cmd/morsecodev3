package app.morsecode.android.core.media

import app.morsecode.android.core.model.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The music queue behind §6.11's Now-Playing screen: shuffle, repeat, "UP
 * NEXT · 5 SONGS", and the Save / Liked toggles.
 *
 * Process-scoped state (§8.2) so playback survives a tab change, a rotation
 * and a minimise; the fragment renders this and owns none of it.
 */
class PlaybackQueue {

    enum class Repeat { OFF, ALL, ONE }

    /**
     * §6.11.0: decoding failures are data the player surface must explain, not
     * an exception a service thread may lose. A StateFlow keeps the newest
     * failure long enough for a newly opened Now-Playing screen to show it.
     */
    data class Failure(val id: Long, val trackName: String, val detail: String)

    data class State(
        val tracks: List<MediaItem> = emptyList(),
        val index: Int = 0,
        val shuffle: Boolean = false,
        val repeat: Repeat = Repeat.OFF,
        val isPlaying: Boolean = false,
        val positionMillis: Long = 0,
    ) {
        val current: MediaItem? get() = tracks.getOrNull(index)

        /** "UP NEXT · 5 SONGS" — what follows the current track (§6.11). */
        val upNext: List<MediaItem>
            get() = if (tracks.isEmpty()) emptyList() else tracks.drop(index + 1)
    }

    private val stateFlow = MutableStateFlow(State())
    val state: StateFlow<State> get() = stateFlow

    private val failureFlow = MutableStateFlow<Failure?>(null)
    val failure: StateFlow<Failure?> get() = failureFlow

    private var shuffleOrder: List<Int> = emptyList()

    fun setQueue(tracks: List<MediaItem>, startIndex: Int) {
        stateFlow.value = State(
            tracks = tracks,
            index = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0)),
        )
        failureFlow.value = null
        shuffleOrder = tracks.indices.shuffled()
    }

    /** Publishes an actionable player error for the visible Now-Playing UI. */
    fun reportFailure(track: MediaItem, detail: String) {
        failureFlow.value = Failure(track.id, track.name, detail)
    }

    /** An acknowledged error must not return on rotation or when reopening the player. */
    fun acknowledgeFailure(id: Long) {
        if (failureFlow.value?.id == id) failureFlow.value = null
    }

    fun setPlaying(playing: Boolean) {
        stateFlow.value = stateFlow.value.copy(isPlaying = playing)
    }

    fun setPosition(millis: Long) {
        stateFlow.value = stateFlow.value.copy(positionMillis = millis)
    }

    fun toggleShuffle() {
        val next = !stateFlow.value.shuffle
        if (next) shuffleOrder = stateFlow.value.tracks.indices.shuffled()
        stateFlow.value = stateFlow.value.copy(shuffle = next)
    }

    fun cycleRepeat() {
        val next = when (stateFlow.value.repeat) {
            Repeat.OFF -> Repeat.ALL
            Repeat.ALL -> Repeat.ONE
            Repeat.ONE -> Repeat.OFF
        }
        stateFlow.value = stateFlow.value.copy(repeat = next)
    }

    /**
     * The next index, or null when the queue has genuinely ended.
     *
     * Repeat ONE stays on the track (a repeat that advanced would not be a
     * repeat); repeat ALL wraps; OFF stops at the end rather than looping
     * silently.
     */
    fun nextIndex(current: State = stateFlow.value): Int? {
        val size = current.tracks.size
        if (size == 0) return null
        return when {
            current.repeat == Repeat.ONE -> current.index
            current.shuffle -> shuffleOrder.getOrNull((shuffleOrder.indexOf(current.index) + 1) % size)
            current.index + 1 < size -> current.index + 1
            current.repeat == Repeat.ALL -> 0
            else -> null
        }
    }

    /**
     * §6.11's previous button: within the first few seconds it goes to the
     * previous track, later it restarts the current one — the behaviour every
     * music player has and everyone expects.
     */
    fun previousIndex(current: State = stateFlow.value): Int {
        if (current.positionMillis > RESTART_THRESHOLD_MS) return current.index
        if (current.tracks.isEmpty()) return 0
        return when {
            current.repeat == Repeat.ONE -> current.index
            current.index > 0 -> current.index - 1
            current.repeat == Repeat.ALL -> current.tracks.size - 1
            else -> 0
        }
    }

    fun moveTo(index: Int) {
        val size = stateFlow.value.tracks.size
        if (size == 0) return
        stateFlow.value = stateFlow.value.copy(
            index = index.coerceIn(0, size - 1),
            positionMillis = 0,
        )
    }

    fun advance(): Boolean {
        val next = nextIndex() ?: return false
        moveTo(next)
        return true
    }

    fun rewind() {
        moveTo(previousIndex())
    }

    /** §6.7's rule, applied here: drag to reorder, swipe to remove (§6.11 Queue). */
    fun move(from: Int, to: Int) {
        val tracks = ArrayList(stateFlow.value.tracks)
        if (from !in tracks.indices || to !in tracks.indices) return
        val moved = tracks.removeAt(from)
        tracks.add(to, moved)
        val currentId = stateFlow.value.current?.uri
        stateFlow.value = stateFlow.value.copy(
            tracks = tracks,
            index = tracks.indexOfFirst { it.uri == currentId }.coerceAtLeast(0),
        )
    }

    fun remove(index: Int) {
        val tracks = ArrayList(stateFlow.value.tracks)
        if (index !in tracks.indices) return
        val currentId = stateFlow.value.current?.uri
        tracks.removeAt(index)
        val newIndex = tracks.indexOfFirst { it.uri == currentId }
        stateFlow.value = stateFlow.value.copy(
            tracks = tracks,
            index = if (newIndex >= 0) newIndex else index.coerceIn(0, (tracks.size - 1).coerceAtLeast(0)),
        )
    }

    private companion object {
        /** Before this, Previous means "the track before"; after, "restart". */
        const val RESTART_THRESHOLD_MS = 3_000L
    }
}
