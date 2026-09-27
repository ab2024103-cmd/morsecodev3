package app.morsecode.android

import app.morsecode.android.core.model.Direction
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.model.TransferState
import app.morsecode.android.core.transfer.QueuePresentation
import app.morsecode.android.core.transfer.QueuePresentation.Action
import app.morsecode.android.core.transfer.TransferQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §6.4's row copy, §6.6's two-line summary, §6.7's action sets and reorder
 * rules, and §6.18's notification text — all of them decisions, so all of them
 * tested rather than eyeballed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QueuePresentationTest {

    private fun item(
        id: String,
        state: TransferState,
        total: Long = MB_144,
        sent: Long = 0,
        speed: Long = 0,
        resume: Long = 0,
        direction: Direction = Direction.SENDING,
        error: String? = null,
    ) = TransferItem(
        id = id,
        batchId = "batch-1",
        direction = direction,
        state = state,
        file = FakeSession.file("$id.mp4", total),
        bytesTransferred = sent,
        speedBps = speed,
        resumeOffset = resume,
        lastError = error,
    )

    private companion object {
        fun mb(value: Double): Long = (value * 1024 * 1024).toLong()
        val MB_144 = mb(144.0)
    }

    @Test
    fun rowMetaLinesMatchTheMockedCopy() {
        // Sizes are binary (§4.10's Fmt), so the mock's "144 MB" is 144 MiB.
        assertEquals(
            "48.9 / 144 MB · 6.2 MB/s",
            QueuePresentation.metaLine(
                item("a", TransferState.IN_PROGRESS, MB_144, mb(48.9), mb(6.2)),
            ),
        )
        assertEquals(
            "4.1 MB · waiting",
            QueuePresentation.metaLine(item("b", TransferState.QUEUED, mb(4.1))),
        )
        assertEquals(
            "39.7 / 64 MB · resume 39.7 MB",
            QueuePresentation.metaLine(
                item("c", TransferState.PAUSED, mb(64.0), resume = mb(39.7)),
            ),
        )
        assertEquals(
            "144 MB · CRC verified",
            QueuePresentation.metaLine(item("d", TransferState.COMPLETED, MB_144)),
        )
        // A failure says what happened, not just that it happened (§6.19).
        assertEquals(
            "Connection lost",
            QueuePresentation.metaLine(item("e", TransferState.FAILED, error = "Connection lost")),
        )
    }

    @Test
    fun theLiveSummaryCountsWhatIsActuallyInTheQueue() {
        val items = listOf(
            item("a", TransferState.IN_PROGRESS, speed = mb(6.2)),
            item("b", TransferState.QUEUED),
            item("c", TransferState.PAUSED),
        )
        assertEquals("1 sending · 1 queued · 1 paused — avg 6.2 MB/s", QueuePresentation.liveDetail(items))
        assertEquals(mb(6.2), QueuePresentation.averageSpeed(items))
    }

    @Test
    fun aFinishedBatchInBothDirectionsShowsTwoLinesNeverOne() {
        // §6.6 [GAP]: "In:" and "Out:" are separate lines; one line that mixed
        // them would tell the user something untrue.
        val items = listOf(
            item("in1", TransferState.COMPLETED, direction = Direction.RECEIVING),
            item("in2", TransferState.COMPLETED, direction = Direction.RECEIVING),
            item("in3", TransferState.SKIPPED, direction = Direction.RECEIVING),
            item("out1", TransferState.COMPLETED, direction = Direction.SENDING),
            item("out2", TransferState.FAILED, direction = Direction.SENDING),
        )
        val lines = QueuePresentation.terminalLines(items)
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("In: 2 received · 0 failed · 1 skipped"))
        assertTrue(lines[1].startsWith("Out: 1 sent · 1 failed · 0 skipped"))

        // One direction only → one line, not an empty "Out:" line.
        val incomingOnly = items.filter { it.direction == Direction.RECEIVING }
        assertEquals(1, QueuePresentation.terminalLines(incomingOnly).size)
    }

    @Test
    fun skippedIsNeverFoldedIntoFailed() {
        val items = listOf(
            item("a", TransferState.SKIPPED),
            item("b", TransferState.FAILED),
        )
        val line = QueuePresentation.terminalLines(items).single()
        assertTrue(line.contains("1 failed"))
        assertTrue(line.contains("1 skipped"))
    }

    @Test
    fun everyStateGetsTheActionSetTheMockShows() {
        assertEquals(
            listOf(Action.PAUSE, Action.RETRY, Action.CANCEL),
            QueuePresentation.actionsFor(TransferState.IN_PROGRESS),
        )
        assertEquals(
            listOf(Action.SEND_NOW, Action.REMOVE),
            QueuePresentation.actionsFor(TransferState.QUEUED),
        )
        assertEquals(
            listOf(Action.RESUME, Action.REMOVE),
            QueuePresentation.actionsFor(TransferState.PAUSED),
        )
        assertEquals(
            listOf(Action.RETRY, Action.REMOVE),
            QueuePresentation.actionsFor(TransferState.FAILED),
        )
        // Every state has at least one honest action; none is a dead row.
        for (state in TransferState.values()) {
            assertTrue(state.name, QueuePresentation.actionsFor(state).isNotEmpty())
        }
    }

    @Test
    fun onlyQueuedRowsReorderAndTheRefusalIsExplicit() {
        assertTrue(QueuePresentation.isReorderable(TransferState.QUEUED))
        for (state in TransferState.values().filterNot { it == TransferState.QUEUED }) {
            assertFalse(state.name, QueuePresentation.isReorderable(state))
        }

        val queue = TransferQueue()
        queue.add(
            listOf(
                item("a", TransferState.IN_PROGRESS),
                item("b", TransferState.QUEUED),
                item("c", TransferState.QUEUED),
            ),
        )
        // Moving a queued row among queued rows works…
        assertTrue(queue.move("c", 1))
        assertEquals(listOf("a", "c", "b"), queue.snapshot().map { it.id })
        // …moving the in-flight row is refused, and the caller can say so.
        assertFalse(queue.move("a", 2))
        // …and so is dropping a queued row onto the in-flight one.
        assertFalse(queue.move("b", 0))
    }

    @Test
    fun sendNowMovesToTheHeadOfTheQueuedRunAndRemoveClears() {
        val queue = TransferQueue()
        queue.add(
            listOf(
                item("running", TransferState.IN_PROGRESS),
                item("first", TransferState.QUEUED),
                item("second", TransferState.QUEUED),
                item("third", TransferState.QUEUED),
            ),
        )
        assertTrue(queue.sendNow("third"))
        assertEquals(listOf("running", "third", "first", "second"), queue.snapshot().map { it.id })

        assertTrue(queue.remove("first"))
        assertEquals(listOf("running", "third", "second"), queue.snapshot().map { it.id })

        queue.setState("third", TransferState.COMPLETED)
        queue.setState("second", TransferState.CANCELLED)
        assertEquals(2, queue.clearCompleted())
        assertEquals(listOf("running"), queue.snapshot().map { it.id })
    }

    @Test
    fun theNotificationNamesThePeerTheFileAndTheCombinedProgress() {
        val items = listOf(
            item("a", TransferState.COMPLETED, total = 100, sent = 100),
            item("b", TransferState.IN_PROGRESS, total = 100, sent = 50),
        )
        assertEquals(75, QueuePresentation.combinedProgress(items))

        val content = QueuePresentation.notification(items, "Ravi's Redmi")
        assertEquals("Ravi's Redmi", content.title)
        assertEquals("b.mp4 · 75%", content.text)
        assertTrue(content.showProgress)

        // §6.18: "3 phones" once there is more than one peer.
        assertEquals("3 phones", QueuePresentation.peerLabel("Ravi's Redmi", 3))
        assertEquals("Ravi's Redmi", QueuePresentation.peerLabel("Ravi's Redmi", 1))
    }

    @Test
    fun combinedProgressCountsSkippedAsDoneAndIgnoresCancelled() {
        val items = listOf(
            item("done", TransferState.COMPLETED, total = 100, sent = 100),
            item("skipped", TransferState.SKIPPED, total = 100),
            item("cancelled", TransferState.CANCELLED, total = 100),
        )
        // A skipped file needed no bytes but IS finished; a cancelled one is
        // not part of the job any more.
        assertEquals(100, QueuePresentation.combinedProgress(items))
    }
}
