package app.morsecode.android

import app.morsecode.android.core.model.Direction
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.model.TransferState
import app.morsecode.android.core.transfer.TransferQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §20.1: the queue is the one source for the queue view, the notification and
 * the batch summary. §21.1 names the queue state machine as a required test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TransferQueueTest {

    private fun item(
        id: String,
        batch: String = "batch-1",
        state: TransferState = TransferState.QUEUED,
        size: Long = 1000,
    ) = TransferItem(
        id = id,
        batchId = batch,
        direction = Direction.SENDING,
        state = state,
        file = FakeSession.file("$id.jpg", size),
    )

    @Test
    fun theWorkerAlwaysGetsTheFirstQueuedItemInInsertionOrder() {
        val queue = TransferQueue()
        queue.add(listOf(item("a"), item("b"), item("c")))
        assertEquals("a", queue.nextQueued()!!.id)

        queue.setState("a", TransferState.IN_PROGRESS)
        assertEquals("b", queue.nextQueued()!!.id)

        queue.setState("b", TransferState.PAUSED)
        assertEquals("c", queue.nextQueued()!!.id)

        queue.setState("c", TransferState.COMPLETED)
        assertNull(queue.nextQueued())
    }

    @Test
    fun connectionLossPausesSendsAndRecordsWhereToResumeFrom() {
        val queue = TransferQueue()
        queue.add(listOf(item("a"), item("b"), item("done", state = TransferState.COMPLETED)))
        queue.setState("a", TransferState.IN_PROGRESS)
        queue.setProgress("a", 400, 900_000)

        assertEquals(2, queue.pauseAllForConnectionLoss("Connection lost"))
        assertEquals(TransferState.PAUSED, queue.item("a")!!.state)
        assertEquals(400L, queue.item("a")!!.resumeOffset)
        assertEquals(0L, queue.item("a")!!.speedBps)
        assertEquals("Connection lost", queue.item("b")!!.lastError)
        // A finished item is not dragged back into the queue.
        assertEquals(TransferState.COMPLETED, queue.item("done")!!.state)
    }

    @Test
    fun requeueSkipsWhatTheUserPausedOnPurpose() {
        val queue = TransferQueue()
        queue.add(listOf(item("a"), item("b")))
        queue.pauseAllForConnectionLoss("Connection lost")

        assertEquals(1, queue.requeuePaused(userPaused = setOf("b")))
        assertEquals(TransferState.QUEUED, queue.item("a")!!.state)
        assertEquals(TransferState.PAUSED, queue.item("b")!!.state)
        assertNull("re-queuing clears the stale reason", queue.item("a")!!.lastError)
    }

    @Test
    fun retryFailedTouchesOnlyFailedItemsOfThatBatch() {
        val queue = TransferQueue()
        queue.add(
            listOf(
                item("a", state = TransferState.FAILED),
                item("b", state = TransferState.COMPLETED),
                item("c", batch = "batch-2", state = TransferState.FAILED),
            ),
        )
        queue.update("a") { it.copy(retryCount = 3) }

        assertEquals(1, queue.retryFailed("batch-1"))
        assertEquals(TransferState.QUEUED, queue.item("a")!!.state)
        assertEquals("the retry budget resets", 0, queue.item("a")!!.retryCount)
        assertEquals(TransferState.COMPLETED, queue.item("b")!!.state)
        assertEquals("another batch is untouched", TransferState.FAILED, queue.item("c")!!.state)
    }

    @Test
    fun theSummaryIsDerivedFromTheSameItemsTheRowsRendered() {
        val queue = TransferQueue()
        queue.add(
            listOf(
                item("a", size = 1000),
                item("b", size = 2000),
                item("c", size = 3000),
                item("d", size = 4000),
            ),
            nowMillis = 0,
        )
        queue.update("a") { it.copy(state = TransferState.COMPLETED, bytesTransferred = 1000) }
        queue.update("b") { it.copy(state = TransferState.COMPLETED, bytesTransferred = 2000) }
        queue.setState("c", TransferState.SKIPPED)
        queue.setState("d", TransferState.FAILED)

        assertTrue(queue.isBatchSettled("batch-1"))
        val summary = queue.summaryOf("batch-1", nowMillis = 2000)
        assertEquals(2, summary.sent)
        assertEquals(1, summary.skipped)
        assertEquals(1, summary.failed)
        assertEquals(3000L, summary.totalBytes)
        assertEquals(2000L, summary.elapsedMillis)
        // 3000 bytes in 2 seconds.
        assertEquals(1500L, summary.averageSpeedBps)
        assertTrue(summary.hasFailures)
    }

    @Test
    fun aBatchWithAnythingStillMovingIsNotSettled() {
        val queue = TransferQueue()
        queue.add(listOf(item("a", state = TransferState.COMPLETED), item("b")))
        assertFalse(queue.isBatchSettled("batch-1"))
        queue.setState("b", TransferState.IN_PROGRESS)
        assertFalse(queue.isBatchSettled("batch-1"))
        queue.setState("b", TransferState.CANCELLED)
        assertTrue(queue.isBatchSettled("batch-1"))
    }

    @Test
    fun terminalStatesAreExactlyTheFourThatStopTheWorker() {
        assertTrue(TransferState.COMPLETED.isTerminal)
        assertTrue(TransferState.SKIPPED.isTerminal)
        assertTrue(TransferState.CANCELLED.isTerminal)
        assertTrue(TransferState.FAILED.isTerminal)
        assertFalse(TransferState.QUEUED.isTerminal)
        assertFalse(TransferState.IN_PROGRESS.isTerminal)
        assertFalse("paused work is resumable, not finished", TransferState.PAUSED.isTerminal)
    }
}
