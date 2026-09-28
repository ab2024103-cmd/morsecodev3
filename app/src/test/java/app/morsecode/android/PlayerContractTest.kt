package app.morsecode.android

import android.net.Uri
import app.morsecode.android.core.media.PlaybackQueue
import app.morsecode.android.core.media.SeekContract
import app.morsecode.android.core.media.VolumeControl
import app.morsecode.android.core.model.FileType
import app.morsecode.android.core.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A32: the seek contract, shared by every player. §6.11 says all four players
 * use the SAME contract, so it is tested once, here, and each player calls it
 * rather than reimplementing it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerContractTest {

    private val duration = 248_000L // 4:08

    @Test
    fun aTapAtSeventyFivePercentJumpsToSeventyFivePercent() {
        // A32's first clause, literally.
        assertEquals(186_000L, SeekContract.positionForTouch(750f, 1000, duration))
        assertEquals(0L, SeekContract.positionForTouch(0f, 1000, duration))
        assertEquals(duration, SeekContract.positionForTouch(1000f, 1000, duration))
        // A touch outside the track cannot seek outside the item.
        assertEquals(0L, SeekContract.positionForTouch(-50f, 1000, duration))
        assertEquals(duration, SeekContract.positionForTouch(1500f, 1000, duration))
        // A zero-length item cannot divide by zero.
        assertEquals(0L, SeekContract.positionForTouch(500f, 1000, 0))
    }

    @Test
    fun theSkipButtonsClampAtBothEndsAndNeverWrap() {
        assertEquals(0L, SeekContract.skipBackward(4_000, duration))
        assertEquals(0L, SeekContract.skipBackward(0, duration))
        assertEquals(duration, SeekContract.skipForward(duration - 3_000, duration))
        assertEquals(duration, SeekContract.skipForward(duration, duration))
        // ±10 s in the middle is exactly ±10 s.
        assertEquals(50_000L, SeekContract.skipBackward(60_000, duration))
        assertEquals(70_000L, SeekContract.skipForward(60_000, duration))
    }

    @Test
    fun keyboardStepsFiveSecondsAndHomeEndJumpToTheEnds() {
        assertEquals(55_000L, SeekContract.keyLeft(60_000, duration))
        assertEquals(65_000L, SeekContract.keyRight(60_000, duration))
        assertEquals(0L, SeekContract.keyLeft(2_000, duration))
        assertEquals(duration, SeekContract.keyRight(duration - 1_000, duration))
        assertEquals(0L, SeekContract.home())
        assertEquals(duration, SeekContract.end(duration))
    }

    @Test
    fun theClockYieldsWhileTheUserIsDragging() {
        // §6.11: "playback position follows the thumb, never fights it".
        val drag = SeekContract.DragState()
        assertEquals(60_000L, drag.displayPosition(60_000))

        drag.begin(60_000)
        drag.update(120_000)
        // The clock keeps ticking underneath, and is ignored.
        assertEquals(120_000L, drag.displayPosition(61_000))
        assertEquals(120_000L, drag.displayPosition(62_000))
        assertTrue(drag.isDragging)

        val committed = drag.commit()
        assertEquals(120_000L, committed)
        assertFalse(drag.isDragging)
        // After release the clock drives the bar again.
        assertEquals(63_000L, drag.displayPosition(63_000))
    }

    @Test
    fun aCancelledDragLeavesThePositionAlone() {
        val drag = SeekContract.DragState()
        drag.begin(10_000)
        drag.update(200_000)
        drag.cancel()
        assertEquals(60_000L, drag.displayPosition(60_000))
    }

    @Test
    fun theLabelsAndTheAnnouncementReadAsSpecified() {
        assertEquals("1:44", SeekContract.elapsedLabel(104_000))
        assertEquals("-2:24", SeekContract.remainingLabel(104_000, duration))
        assertEquals("0:00", SeekContract.elapsedLabel(0))
        assertEquals("-0:00", SeekContract.remainingLabel(duration, duration))
        // §15.5's announced value, verbatim from §6.11.
        assertEquals(
            "2 minutes 43 seconds of 4 minutes 8 seconds",
            SeekContract.announcement(163_000, duration),
        )
    }

    // ----- Volume (§6.11) ---------------------------------------------------

    @Test
    fun volumeIsContinuousAndMuteRemembersTheLevel() {
        val volume = VolumeControl(initialPercent = 70)
        assertEquals(VolumeControl.Level.HIGH, volume.level)
        assertEquals(0.7f, volume.gain, 0.001f)

        volume.set(30)
        assertEquals(VolumeControl.Level.LOW, volume.level)

        // Tapping the icon again mutes and REMEMBERS.
        volume.toggleMute()
        assertTrue(volume.isMuted)
        assertEquals(VolumeControl.Level.OFF, volume.level)
        assertEquals(0f, volume.gain, 0.001f)

        volume.toggleMute()
        assertEquals("unmute restores the previous level", 30, volume.percent)
    }

    @Test
    fun volumeClampsAndTheHardwareKeysMoveTheSameSlider() {
        val volume = VolumeControl(initialPercent = 95)
        volume.step(up = true)
        assertEquals(100, volume.percent)
        repeat(12) { volume.step(up = false) }
        assertEquals(0, volume.percent)
        // Dragging to zero is a mute the icon reflects, and unmute still works.
        assertTrue(volume.isMuted)
        volume.toggleMute()
        assertTrue(volume.percent > 0)
    }

    @Test
    fun theVolumeSliderDismissesAfterThreeSecondsIdle() {
        val volume = VolumeControl()
        assertFalse(volume.shouldDismiss(2_999))
        assertTrue(volume.shouldDismiss(3_000))
    }

    // ----- Queue (§6.11) ----------------------------------------------------

    private fun track(id: Long) = MediaItem(
        id = id,
        uri = Uri.parse("content://audio/$id"),
        name = "track$id.mp3",
        sizeBytes = 8_400_000,
        dateMillis = 0,
        mimeType = "audio/mpeg",
        type = FileType.AUDIO,
        durationMillis = 222_000,
    )

    @Test
    fun anUnplayableTrackProducesAnAcknowledgablePlayerError() {
        val queue = PlaybackQueue()
        val broken = track(7).copy(name = "broken.mp3")
        queue.setQueue(listOf(broken), startIndex = 0)

        queue.reportFailure(broken, "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED")
        assertEquals("broken.mp3", queue.failure.value?.trackName)
        assertTrue(queue.failure.value?.detail?.contains("PARSING") == true)

        queue.acknowledgeFailure(broken.id)
        assertNull("an acknowledged snackbar must not return after rotation", queue.failure.value)
    }

    @Test
    fun repeatOffStopsAtTheEndAndRepeatAllWraps() {
        val queue = PlaybackQueue()
        queue.setQueue(listOf(track(1), track(2)), startIndex = 1)

        assertNull("repeat OFF must not loop silently", queue.nextIndex())

        queue.cycleRepeat() // ALL
        assertEquals(0, queue.nextIndex())

        queue.cycleRepeat() // ONE
        assertEquals("repeat ONE stays on the track", 1, queue.nextIndex())
    }

    @Test
    fun previousRestartsTheTrackAfterAFewSecondsAndOtherwiseGoesBack() {
        val queue = PlaybackQueue()
        queue.setQueue(listOf(track(1), track(2), track(3)), startIndex = 1)

        queue.setPosition(500)
        assertEquals("early in the track, Previous goes back", 0, queue.previousIndex())

        queue.setPosition(10_000)
        assertEquals("later, Previous restarts the track", 1, queue.previousIndex())
    }

    @Test
    fun upNextIsWhatFollowsTheCurrentTrack() {
        val queue = PlaybackQueue()
        queue.setQueue(listOf(track(1), track(2), track(3), track(4)), startIndex = 1)
        assertEquals(2, queue.state.value.upNext.size)
        assertEquals("track3.mp3", queue.state.value.upNext.first().name)

        queue.advance()
        assertEquals(1, queue.state.value.upNext.size)
    }

    @Test
    fun reorderingKeepsThePlayingTrackPlaying() {
        val queue = PlaybackQueue()
        queue.setQueue(listOf(track(1), track(2), track(3)), startIndex = 1)
        val playing = queue.state.value.current

        // Dragging another row past the current one must not change what is
        // playing — it moves the list, not the needle.
        queue.move(0, 2)
        assertEquals(playing, queue.state.value.current)
        assertEquals(listOf(2L, 3L, 1L), queue.state.value.tracks.map { it.id })
    }

    @Test
    fun removingAnotherRowLeavesTheNeedleAloneAndRemovingTheCurrentAdvances() {
        val queue = PlaybackQueue()
        queue.setQueue(listOf(track(1), track(2), track(3)), startIndex = 1)
        val playing = queue.state.value.current

        queue.remove(2)
        assertEquals("removing a later row cannot change the current track", playing, queue.state.value.current)
        assertEquals(2, queue.state.value.tracks.size)

        // Swiping away the track that is playing has to land somewhere: the
        // item that took its place, not a crash and not index 0.
        queue.remove(queue.state.value.index)
        assertEquals(1, queue.state.value.tracks.size)
        assertEquals(1L, queue.state.value.current?.id)
    }
}
