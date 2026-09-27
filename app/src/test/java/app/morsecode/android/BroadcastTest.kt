package app.morsecode.android

import app.morsecode.android.core.model.EngineEvent
import app.morsecode.android.core.model.PeerDelivery
import app.morsecode.android.core.model.PeerSessionState
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.model.TransferState
import app.morsecode.android.core.transfer.BroadcastEngine
import app.morsecode.android.core.transfer.BroadcastStats
import app.morsecode.android.core.transfer.TransferEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §10's engine and §10.4's formulas — A9's arithmetic and its invariants.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BroadcastTest {

    private val fastTimings = TransferEngine.Timings(
        maxRetries = 0,
        retryDelayMs = 5,
        terminalFallbackMs = 30,
        idleTimeoutMs = 200,
        batchWindowMs = 50,
        watchTickMs = 5,
    )

    private fun TestScope.engine() = BroadcastEngine(
        scope = backgroundScope,
        timings = fastTimings,
        clock = { testScheduler.currentTime },
    )

    private fun TestScope.settle(millis: Long = 5_000) {
        testScheduler.advanceTimeBy(millis)
        testScheduler.runCurrent()
    }

    private fun mb(value: Double): Long = (value * 1024 * 1024).toLong()

    // ----- §10.4 formulas, against the mock's own numbers -------------------

    @Test
    fun theTilesReadExactlyAsTheMocksDo() {
        // The deck's broadcast sheet: 3 peers, a 212 MB batch, 636 MB to send.
        val items = listOf(
            item("a", mb(100.0)),
            item("b", mb(100.0)),
            item("c", mb(12.0)),
        )
        val deliveries = listOf(
            delivery("p1", sent = mb(50.0), speed = mb(4.7)),
            delivery("p2", sent = mb(30.0), speed = mb(4.7)),
            delivery("p3", sent = mb(20.0), speed = mb(4.8)),
        )

        val running = BroadcastStats.running(items, deliveries)
        assertEquals(3, running.peers)
        assertEquals(mb(212.0), running.batchBytes)
        assertEquals("TO SEND MB = BATCH MB × PEERS", mb(636.0), running.toSendBytes)
        assertEquals(mb(100.0), running.sentBytes)
        assertEquals(mb(14.2), running.throughputBps)

        assertEquals(
            listOf("3" to "PEERS", "212 MB" to "BATCH MB", "636 MB" to "TO SEND MB"),
            BroadcastStats.runningTiles(running),
        )

        val complete = BroadcastStats.complete(items, deliveries, elapsedMillis = 45_000)
        assertEquals(3, complete.phones)
        assertEquals(3, complete.filesEach)
        assertEquals("deliveries = files × accepted peers", 9, complete.deliveries)
        assertEquals(
            listOf("3" to "PHONES", "3" to "FILES EACH", "100 MB" to "MB SENT"),
            BroadcastStats.completeTiles(complete),
        )
    }

    @Test
    fun aRejectedPeerIsNotCountedAsATarget() {
        val items = listOf(item("a", mb(10.0)))
        val deliveries = listOf(
            delivery("p1"),
            delivery("p2"),
            delivery("p3").copy(sessionState = PeerSessionState.REJECTED),
        )
        val running = BroadcastStats.running(items, deliveries)
        assertEquals(2, running.peers)
        assertEquals(mb(20.0), running.toSendBytes)
        assertEquals(2, BroadcastStats.complete(items, deliveries, 1000).deliveries)
    }

    @Test
    fun theCompletionHeaderDegradesRatherThanLying() {
        val items = listOf(item("a", mb(10.0)))
        val allGood = listOf(delivery("p1", verified = true), delivery("p2", verified = true))
        assertEquals(
            "✓ Broadcast complete — all 2 phones verified",
            BroadcastStats.header(BroadcastStats.complete(items, allGood, 1000)),
        )

        // INV-B4: it may only say "complete" when EVERY accepted peer verified.
        val onePartial = listOf(delivery("p1", verified = true), delivery("p2", verified = false))
        assertEquals(
            "Broadcast finished — 1 of 2 phones verified",
            BroadcastStats.header(BroadcastStats.complete(items, onePartial, 1000)),
        )
    }

    @Test
    fun theHonestyNoteAppearsOnlyWhenTheSpeedIsReallyShared() {
        // §10.6: below ~40 % of the single-peer baseline.
        assertEquals(
            "Speed is shared between 3 phones",
            BroadcastStats.sharedSpeedNote(perPeerBps = mb(2.0), singlePeerBaselineBps = mb(8.0), peers = 3),
        )
        assertNull(BroadcastStats.sharedSpeedNote(mb(6.0), mb(8.0), 3))
        assertNull("one peer is not a shared speed", BroadcastStats.sharedSpeedNote(mb(1.0), mb(8.0), 1))
    }

    @Test
    fun theCapNoteNamesTheRealLimit() {
        assertNull(BroadcastStats.capNote(requested = 3, cap = 4, nearby = false, lowTier = false))
        assertEquals(
            "Nearby supports up to 3 phones — the rest will wait",
            BroadcastStats.capNote(requested = 5, cap = 3, nearby = true, lowTier = false),
        )
        assertEquals(
            "This phone can broadcast to 2 at a time — the rest will wait",
            BroadcastStats.capNote(requested = 4, cap = 2, nearby = false, lowTier = true),
        )
    }

    // ----- §10.3 invariants, driven through the engine ----------------------

    @Test
    fun everyPeerGetsEveryFileAndOneSummaryCoversThemAll() = runTest {
        val engine = engine()
        val events = ArrayList<EngineEvent>()
        backgroundScope.launch { engine.events.collect { events.add(it) } }

        val peers = listOf(FakeSession("p1", "Ravi's Redmi"), FakeSession("p2", "Pixel 7X"), FakeSession("p3", "MYA-L10"))
        engine.start(peers, listOf(FakeSession.file("a.jpg"), FakeSession.file("b.jpg")))
        settle()

        assertEquals(3, engine.deliveries.value.size)
        assertTrue(engine.deliveries.value.all { it.verified })
        assertTrue(engine.deliveries.value.all { it.sessionState == PeerSessionState.DONE })
        // INV-B5: ONE coalesced summary for the whole broadcast.
        val summaries = events.filterIsInstance<EngineEvent.BatchCompleted>()
        assertEquals(1, summaries.size)
        assertEquals(6, summaries.first().summary.sent)
    }

    @Test
    fun aRejectingPeerNeverHarmsTheOthers() = runTest {
        val engine = engine()
        val good = FakeSession("p1", "Ravi's Redmi")
        val alsoGood = FakeSession("p2", "Pixel 7X")
        engine.start(listOf(good, alsoGood), listOf(FakeSession.file("a.jpg")))
        engine.markRejected("p3", "Office Laptop", "declined")
        settle()

        // INV-B1: the rejecting peer is greyed out; the others completed.
        val byId = engine.deliveries.value.associateBy { it.peerId }
        assertEquals(PeerSessionState.REJECTED, byId.getValue("p3").sessionState)
        assertTrue(byId.getValue("p1").verified)
        assertTrue(byId.getValue("p2").verified)
    }

    @Test
    fun aFailingPeerFailsOnlyItself() = runTest {
        val engine = engine()
        val healthy = FakeSession("p1", "Ravi's Redmi")
        val broken = FakeSession("p2", "Pixel 7X")
        broken.defaultBehaviour = FakeSession.Behaviour.FAIL
        engine.start(listOf(healthy, broken), listOf(FakeSession.file("a.jpg"), FakeSession.file("b.jpg")))
        settle()

        val byId = engine.deliveries.value.associateBy { it.peerId }
        assertTrue("the healthy peer still verified", byId.getValue("p1").verified)
        assertEquals(2, byId.getValue("p1").completedCount)
        assertFalse("the broken peer is not verified", byId.getValue("p2").verified)
        assertEquals(2, byId.getValue("p2").failedCount)

        // INV-B4: the header must not claim completion.
        val stats = BroadcastStats.complete(engine.items.value, engine.deliveries.value, 1000)
        assertFalse(stats.allVerified)
        assertEquals("Broadcast finished — 1 of 2 phones verified", BroadcastStats.header(stats))
    }

    @Test
    fun aHangingPeerNeverStallsTheFastOnes() = runTest {
        // INV-B3: no global hang. The stuck peer is bounded by its own
        // watchdog while the others finish.
        val engine = engine()
        val quick = FakeSession("p1", "Ravi's Redmi")
        val stuck = FakeSession("p2", "Pixel 7X")
        stuck.defaultBehaviour = FakeSession.Behaviour.HANG
        engine.start(listOf(quick, stuck), listOf(FakeSession.file("a.jpg")))
        settle()

        val byId = engine.deliveries.value.associateBy { it.peerId }
        assertTrue(byId.getValue("p1").verified)
        assertEquals(TransferState.FAILED, byId.getValue("p2").itemStates.values.first())
    }

    @Test
    fun retryPeerMovesOnlyThatPeersFailedFiles() = runTest {
        val engine = engine()
        val healthy = FakeSession("p1")
        val flaky = FakeSession("p2", "Pixel 7X")
        flaky.defaultBehaviour = FakeSession.Behaviour.FAIL
        engine.start(listOf(healthy, flaky), listOf(FakeSession.file("a.jpg")))
        settle()
        assertEquals(1, engine.deliveries.value.first { it.peerId == "p2" }.failedCount)

        flaky.defaultBehaviour = FakeSession.Behaviour.COMPLETE
        engine.retryPeer("p2")
        settle()

        val byId = engine.deliveries.value.associateBy { it.peerId }
        assertTrue(byId.getValue("p2").verified)
        assertEquals(1, byId.getValue("p1").completedCount)
    }

    @Test
    fun perPeerResumeOffsetsAreTrackedSeparately() = runTest {
        // INV-B2: the offset belongs to (file, peer), not to the file.
        val engine = engine()
        val fast = FakeSession("p1")
        fast.progressSteps = 4
        val slow = FakeSession("p2", "Pixel 7X")
        slow.progressSteps = 2
        engine.start(listOf(fast, slow), listOf(FakeSession.file("a.jpg", size = 4000)))
        settle()

        val offsets = engine.deliveries.value.associate { it.peerId to it.resumeOffsets.values.firstOrNull() }
        assertEquals(4000L, offsets["p1"])
        assertEquals(4000L, offsets["p2"])
        // Each peer carries its OWN map, not a shared one.
        assertEquals(2, engine.deliveries.value.count { it.resumeOffsets.isNotEmpty() })
    }

    // ----- Helpers ----------------------------------------------------------

    private fun item(id: String, size: Long) = TransferItem(
        id = id,
        batchId = "batch-1",
        direction = app.morsecode.android.core.model.Direction.SENDING,
        state = TransferState.QUEUED,
        file = FakeSession.file("$id.mp4", size),
    )

    private fun delivery(
        peerId: String,
        sent: Long = 0,
        speed: Long = 0,
        verified: Boolean = false,
    ) = PeerDelivery(
        peerId = peerId,
        peerName = peerId,
        avatarColor = 0,
        sessionState = PeerSessionState.SENDING,
        bytesSent = sent,
        speedBps = speed,
        verified = verified,
    )
}
