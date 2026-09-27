package app.morsecode.android

import androidx.test.core.app.ApplicationProvider
import app.morsecode.android.core.data.HistoryStore
import app.morsecode.android.core.data.JournalStore
import app.morsecode.android.core.model.EngineEvent
import app.morsecode.android.core.model.SessionState
import app.morsecode.android.core.model.TransferState
import app.morsecode.android.core.transfer.TransferEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * §9 the engine, driven headlessly through [FakeSession] — the Stage 5 harness.
 *
 * These tests are the evidence for A2 (pause/cancel never blocks others),
 * A3 (session restart unsticks and resumes) and A8 (one summary per batch,
 * SKIPPED distinct from FAILED), plus INV-1's three hang guards and §9.3's
 * retry policy.
 *
 * Timings are compressed but the code paths are the shipping ones: the
 * engine's constants are injected, not branched on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TransferEngineTest {

    private val fastTimings = TransferEngine.Timings(
        maxRetries = 3,
        retryDelayMs = 10,
        terminalFallbackMs = 30,
        idleTimeoutMs = 200,
        batchWindowMs = 50,
        watchTickMs = 5,
    )

    private fun tempFile(name: String): File {
        val dir = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "engine-${System.nanoTime()}",
        )
        dir.mkdirs()
        return File(dir, name)
    }

    private fun TestScope.engine(
        journal: JournalStore? = null,
        history: HistoryStore? = null,
    ) = TransferEngine(
        scope = backgroundScope,
        journal = journal,
        history = history,
        timings = fastTimings,
        clock = { testScheduler.currentTime },
    )

    private fun TestScope.collectEvents(engine: TransferEngine): List<EngineEvent> {
        val events = ArrayList<EngineEvent>()
        backgroundScope.launch { engine.events.collect { events.add(it) } }
        return events
    }

    // ----- Happy path and A8 ------------------------------------------------

    @Test
    fun aBatchCompletesAndProducesExactlyOneSummary() = runTest {
        val engine = engine()
        val events = collectEvents(engine)
        val session = FakeSession()
        engine.onSessionConnected(session)

        val batch = engine.enqueue(
            listOf(FakeSession.file("a.jpg"), FakeSession.file("b.jpg"), FakeSession.file("c.jpg")),
        )
        advanceUntilIdle()

        assertTrue(engine.items.value.all { it.state == TransferState.COMPLETED })
        val summaries = events.filterIsInstance<EngineEvent.BatchCompleted>()
        assertEquals("§9.6: never one popup per file", 1, summaries.size)
        assertEquals(batch, summaries.first().summary.batchId)
        assertEquals(3, summaries.first().summary.sent)
        assertFalse(summaries.first().summary.hasFailures)
    }

    @Test
    fun skippedIsReportedSeparatelyFromFailed() = runTest {
        val engine = engine()
        val events = collectEvents(engine)
        val session = FakeSession()
        session.behave("dupe.jpg", FakeSession.Behaviour.ALREADY_PRESENT)
        session.behave("broken.jpg", FakeSession.Behaviour.FAIL)
        engine.onSessionConnected(session)

        engine.enqueue(
            listOf(
                FakeSession.file("ok.jpg"),
                FakeSession.file("dupe.jpg"),
                FakeSession.file("broken.jpg"),
            ),
        )
        advanceUntilIdle()

        val byName = engine.items.value.associateBy { it.file.displayName }
        assertEquals(TransferState.COMPLETED, byName.getValue("ok.jpg").state)
        assertEquals(TransferState.SKIPPED, byName.getValue("dupe.jpg").state)
        assertEquals(TransferState.FAILED, byName.getValue("broken.jpg").state)

        val summary = events.filterIsInstance<EngineEvent.BatchCompleted>().single().summary
        assertEquals(1, summary.sent)
        assertEquals(1, summary.skipped)
        assertEquals(1, summary.failed)
        assertTrue(summary.hasFailures)
    }

    // ----- A2: pause / cancel never blocks others ---------------------------

    @Test
    fun cancellingOneInFlightFileLetsTheRestComplete() = runTest {
        val engine = engine()
        val session = FakeSession()
        // The OEM stack that never answers a cancel — INV-1(b)'s reason to exist.
        session.behave("stuck.bin", FakeSession.Behaviour.HANG)
        engine.onSessionConnected(session)

        engine.enqueue(
            listOf(FakeSession.file("stuck.bin"), FakeSession.file("next.jpg"), FakeSession.file("last.jpg")),
        )
        // Let the worker pick up the hanging file.
        testScheduler.advanceTimeBy(20)
        val stuck = engine.items.value.first { it.file.displayName == "stuck.bin" }
        assertEquals(TransferState.IN_PROGRESS, stuck.state)

        engine.cancel(stuck.id)
        advanceUntilIdle()

        val byName = engine.items.value.associateBy { it.file.displayName }
        assertEquals(TransferState.CANCELLED, byName.getValue("stuck.bin").state)
        assertEquals(TransferState.COMPLETED, byName.getValue("next.jpg").state)
        assertEquals(TransferState.COMPLETED, byName.getValue("last.jpg").state)
        assertTrue("the cancel reached the transport", session.cancelCalls.isNotEmpty())
        assertTrue(
            "nothing may be left stuck QUEUED (A2)",
            engine.items.value.none { it.state == TransferState.QUEUED },
        )
    }

    @Test
    fun pausingOneFileLeavesItResumableAndTheOthersUntouched() = runTest {
        val engine = engine()
        val session = FakeSession()
        session.behave("big.mp4", FakeSession.Behaviour.HANG)
        engine.onSessionConnected(session)

        engine.enqueue(listOf(FakeSession.file("big.mp4", size = 4096), FakeSession.file("small.jpg")))
        testScheduler.advanceTimeBy(20)
        val big = engine.items.value.first { it.file.displayName == "big.mp4" }
        // Some bytes moved before the pause.
        engine.queue.setProgress(big.id, 1024, 500_000)

        engine.pause(big.id)
        advanceUntilIdle()

        val paused = engine.queue.item(big.id)!!
        assertEquals(TransferState.PAUSED, paused.state)
        assertEquals("resume must continue from what moved", 1024L, paused.resumeOffset)
        assertEquals(0L, paused.speedBps)
        assertTrue(session.pauseCalls.isNotEmpty())
        assertEquals(
            TransferState.COMPLETED,
            engine.items.value.first { it.file.displayName == "small.jpg" }.state,
        )
    }

    // ----- INV-1: never hang ------------------------------------------------

    @Test
    fun aTransportThatNeverAnswersIsBoundedByTheIdleWatchdog() = runTest {
        val engine = engine()
        val session = FakeSession()
        session.behave("ghost.bin", FakeSession.Behaviour.HANG)
        engine.onSessionConnected(session)

        engine.enqueue(listOf(FakeSession.file("ghost.bin"), FakeSession.file("after.jpg")))
        advanceUntilIdle()

        // No pause, no cancel, no session loss: the bounded await still ends,
        // the item fails after its retries, and the queue keeps moving.
        val byName = engine.items.value.associateBy { it.file.displayName }
        assertEquals(TransferState.FAILED, byName.getValue("ghost.bin").state)
        assertEquals("No progress", byName.getValue("ghost.bin").lastError)
        assertEquals(TransferState.COMPLETED, byName.getValue("after.jpg").state)
    }

    // ----- §9.3 retry -------------------------------------------------------

    @Test
    fun aFlakyFileIsRetriedUpToThreeTimesAndThenSucceeds() = runTest {
        val engine = engine()
        val session = FakeSession()
        session.failuresBeforeSuccess = 2
        session.behave("flaky.jpg", FakeSession.Behaviour.FLAKY)
        engine.onSessionConnected(session)

        engine.enqueue(listOf(FakeSession.file("flaky.jpg")))
        advanceUntilIdle()

        val item = engine.items.value.single()
        assertEquals(TransferState.COMPLETED, item.state)
        assertEquals(3, session.attemptsFor("flaky.jpg"))
        assertEquals(2, item.retryCount)
    }

    @Test
    fun aPermanentFailureStopsAfterExactlyThreeRetries() = runTest {
        val engine = engine()
        val events = collectEvents(engine)
        val session = FakeSession()
        session.behave("dead.bin", FakeSession.Behaviour.FAIL)
        engine.onSessionConnected(session)

        engine.enqueue(listOf(FakeSession.file("dead.bin")))
        advanceUntilIdle()

        assertEquals(TransferState.FAILED, engine.items.value.single().state)
        // The first attempt plus three retries (§9.3).
        assertEquals(4, session.attemptsFor("dead.bin"))
        assertEquals(1, events.filterIsInstance<EngineEvent.ItemFailed>().size)
    }

    // ----- A3 / INV-3: session loss and restart -----------------------------

    @Test
    fun losingASessionPausesEverythingAndFailsNothing() = runTest {
        val engine = engine()
        val session = FakeSession()
        session.behave("big.mp4", FakeSession.Behaviour.HANG)
        engine.onSessionConnected(session)
        engine.enqueue(listOf(FakeSession.file("big.mp4", 4096), FakeSession.file("queued.jpg")))
        testScheduler.advanceTimeBy(20)

        val big = engine.items.value.first { it.file.displayName == "big.mp4" }
        engine.queue.setProgress(big.id, 2048, 500_000)
        session.isAlive = false
        engine.onSessionLost("Connection lost")
        advanceUntilIdle()

        assertTrue(engine.items.value.all { it.state == TransferState.PAUSED })
        assertTrue(engine.items.value.none { it.state == TransferState.FAILED })
        assertEquals(2048L, engine.queue.item(big.id)!!.resumeOffset)
        assertTrue(engine.session.value is SessionState.Closed)
    }

    @Test
    fun aNewSessionRequeuesAndFinishesTheInterruptedBatch() = runTest {
        val engine = engine()
        val first = FakeSession()
        first.behave("big.mp4", FakeSession.Behaviour.HANG)
        engine.onSessionConnected(first)
        engine.enqueue(listOf(FakeSession.file("big.mp4", 4096), FakeSession.file("queued.jpg")))
        testScheduler.advanceTimeBy(20)

        first.isAlive = false
        engine.onSessionLost("Connection lost")
        advanceUntilIdle()

        // A3: start a new session immediately — everything resumes by itself.
        engine.onSessionConnected(FakeSession(peerId = "peer-2", peerName = "Pixel 7X"))
        advanceUntilIdle()

        assertTrue(engine.items.value.all { it.state == TransferState.COMPLETED })
        assertTrue(engine.session.value is SessionState.Connected)
    }

    @Test
    fun aDeliberatePauseSurvivesAReconnect() = runTest {
        val engine = engine()
        val session = FakeSession()
        session.behave("held.mp4", FakeSession.Behaviour.HANG)
        engine.onSessionConnected(session)
        engine.enqueue(listOf(FakeSession.file("held.mp4"), FakeSession.file("other.jpg")))
        testScheduler.advanceTimeBy(20)

        val held = engine.items.value.first { it.file.displayName == "held.mp4" }
        engine.pause(held.id)
        advanceUntilIdle()

        session.isAlive = false
        engine.onSessionLost("Connection lost")
        engine.onSessionConnected(FakeSession(peerId = "peer-3"))
        advanceUntilIdle()

        // INV-3 re-queues what the CONNECTION paused, not what the USER did.
        assertEquals(TransferState.PAUSED, engine.queue.item(held.id)!!.state)

        engine.resume(held.id)
        session.released.complete(Unit)
        advanceUntilIdle()
        assertEquals(TransferState.COMPLETED, engine.queue.item(held.id)!!.state)
    }

    // ----- §9.6 history and §9.7 recovery -----------------------------------

    @Test
    fun everyTerminalItemGoesThroughTheOneCompletePath() = runTest {
        val history = HistoryStore(tempFile("history.json"))
        val engine = engine(history = history)
        val session = FakeSession()
        session.behave("dupe.jpg", FakeSession.Behaviour.ALREADY_PRESENT)
        session.behave("dead.bin", FakeSession.Behaviour.FAIL)
        engine.onSessionConnected(session)

        engine.enqueue(
            listOf(FakeSession.file("ok.jpg"), FakeSession.file("dupe.jpg"), FakeSession.file("dead.bin")),
        )
        advanceUntilIdle()

        val rows = history.read().associateBy { it.name }
        assertEquals(3, rows.size)
        assertEquals(TransferState.COMPLETED, rows.getValue("ok.jpg").state)
        assertEquals(TransferState.SKIPPED, rows.getValue("dupe.jpg").state)
        assertEquals(TransferState.FAILED, rows.getValue("dead.bin").state)
        assertEquals("Ravi's Redmi", rows.getValue("ok.jpg").peerName)
    }

    @Test
    fun anInterruptedBatchIsJournaledAndOfferedBackAsPaused() = runTest {
        val journalFile = tempFile("journal.json")
        val journal = JournalStore(journalFile)
        val engine = engine(journal = journal)
        val session = FakeSession()
        session.behave("big.mp4", FakeSession.Behaviour.HANG)
        engine.onSessionConnected(session)
        engine.enqueue(listOf(FakeSession.file("big.mp4", 4096), FakeSession.file("queued.jpg")))
        testScheduler.advanceTimeBy(20)

        assertTrue("the journal is written on every transition", journalFile.exists())

        // A fresh process: the queue is empty until the user answers §9.7.
        val restarted = engine(journal = JournalStore(journalFile))
        val pending = restarted.pendingResume()
        assertNotNull(pending)
        assertEquals("Ravi's Redmi", pending!!.peerName)
        assertEquals(2, pending.remainingItems)
        assertTrue("nothing resumes before the user answers", restarted.items.value.isEmpty())

        assertEquals(2, restarted.restoreJournal())
        assertTrue(
            "an interrupted item never comes back IN_PROGRESS",
            restarted.items.value.all { it.state == TransferState.PAUSED },
        )

        restarted.discardJournal()
        assertNull(restarted.pendingResume())
    }

    // ----- §3.6 shared items ------------------------------------------------

    @Test
    fun sharedItemsAreHeldUntilAPeerExists() = runTest {
        val engine = engine()
        assertFalse(engine.hasPendingShare())

        engine.holdShare(listOf(FakeSession.file("shared.pdf")))
        assertTrue(engine.hasPendingShare())

        val held = engine.takePendingShare()
        assertEquals(1, held.size)
        assertFalse("taking the batch clears it", engine.hasPendingShare())

        engine.onSessionConnected(FakeSession())
        engine.enqueue(held)
        advanceUntilIdle()
        assertEquals(TransferState.COMPLETED, engine.items.value.single().state)
    }

    // ----- §20.3 events vs state -------------------------------------------

    @Test
    fun afreshEngineNeverAnnouncesAClosedConnection() = runTest {
        val engine = engine()
        assertTrue(
            "\"never connected\" must be distinguishable from \"was connected, now closed\"",
            engine.session.value is SessionState.NeverConnected,
        )
        engine.onSessionLost("Connection lost")
        assertTrue(
            "a loss with no prior session must not fabricate a Closed state",
            engine.session.value is SessionState.NeverConnected,
        )
    }
}
