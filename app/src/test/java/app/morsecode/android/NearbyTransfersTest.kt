package app.morsecode.android

import app.morsecode.android.core.model.SendResult
import app.morsecode.android.core.network.NearbyPayloads
import app.morsecode.android.core.network.NearbyTransfers
import app.morsecode.android.core.network.PlayServices
import app.morsecode.android.core.network.Protocol
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §11.3 without Play Services: the bookkeeping that decides whether a Nearby
 * transfer hangs, resumes or is abandoned is pure Kotlin, so all of it is
 * tested here — the SDK is only the pipe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NearbyTransfersTest {

    private fun transfers() = NearbyTransfers()

    @Test
    fun successCompletesTheWaiterForThatFile() = runBlocking {
        val transfers = transfers()
        val waiter = transfers.register("file-1")
        transfers.bindPayload(77L, "file-1")

        var progressSeen = 0L
        assertNull(
            transfers.onUpdate(77L, NearbyTransfers.PayloadStatus.IN_PROGRESS, 512, 1024) { _, sent, _ ->
                progressSeen = sent
            },
        )
        assertEquals(512L, progressSeen)
        assertFalse(waiter.isCompleted)

        transfers.onUpdate(77L, NearbyTransfers.PayloadStatus.SUCCESS, 1024, 1024) { _, _, _ -> }
        assertTrue(withTimeout(1000) { waiter.await() } is SendResult.Completed)
    }

    @Test
    fun failureIsFailedAndCancelDependsOnWhichFlagWasSet() = runBlocking {
        val transfers = transfers()

        val failing = transfers.register("f-fail")
        transfers.bindPayload(1L, "f-fail")
        transfers.onUpdate(1L, NearbyTransfers.PayloadStatus.FAILURE, 0, 10) { _, _, _ -> }
        assertTrue(withTimeout(1000) { failing.await() } is SendResult.Failed)

        // The SDK reports one CANCELED for both cases; the flag is the only
        // way to tell a pause from a cancel, and getting it wrong would stop
        // INV-3 ever resuming the file.
        val paused = transfers.register("f-pause")
        transfers.bindPayload(2L, "f-pause")
        transfers.markPaused("f-pause")
        transfers.onUpdate(2L, NearbyTransfers.PayloadStatus.CANCELED, 5, 10) { _, _, _ -> }
        assertTrue(withTimeout(1000) { paused.await() } is SendResult.Paused)

        val cancelled = transfers.register("f-cancel")
        transfers.bindPayload(3L, "f-cancel")
        transfers.markCancelled("f-cancel")
        transfers.onUpdate(3L, NearbyTransfers.PayloadStatus.CANCELED, 5, 10) { _, _, _ -> }
        assertTrue(withTimeout(1000) { cancelled.await() } is SendResult.Cancelled)
    }

    @Test
    fun aCancelWithNoFlagIsTreatedAsAPauseNotALoss() = runBlocking {
        // If the peer cancelled, the safe reading is "pausable": PAUSED work
        // is resumable, CANCELLED work is gone for good (§9.1).
        val transfers = transfers()
        val waiter = transfers.register("f")
        transfers.bindPayload(9L, "f")
        transfers.onUpdate(9L, NearbyTransfers.PayloadStatus.CANCELED, 1, 10) { _, _, _ -> }
        assertTrue(withTimeout(1000) { waiter.await() } is SendResult.Paused)
    }

    @Test
    fun theSessionEndRuleFailsEveryWaiterAndClearsEveryMap() = runBlocking {
        val transfers = transfers()
        val a = transfers.register("a")
        val b = transfers.register("b")
        transfers.bindPayload(1L, "a")
        transfers.bindPayload(2L, "b")
        transfers.markPaused("a")
        assertEquals(2, transfers.pendingCount)

        // §11.3: fail every outgoing waiter and clear the maps FIRST. This is
        // what makes INV-1 (never hang) hold on this transport.
        assertEquals(2, transfers.failAllAndClear())

        assertTrue(withTimeout(1000) { a.await() } is SendResult.Failed)
        assertEquals("session closed", (withTimeout(1000) { b.await() } as SendResult.Failed).error)
        assertEquals(0, transfers.pendingCount)
        assertNull(transfers.fileFor(1L))
        assertNull(transfers.payloadFor("a"))
        assertFalse(transfers.isInterrupted("a"))
    }

    @Test
    fun updatesForAnUnknownPayloadAreIgnored() {
        val transfers = transfers()
        assertNull(
            transfers.onUpdate(404L, NearbyTransfers.PayloadStatus.SUCCESS, 1, 1) { _, _, _ ->
                throw AssertionError("progress must not fire for an unknown payload")
            },
        )
    }

    @Test
    fun completingOneFileLeavesTheOthersAlone() = runBlocking {
        // INV-2 on this transport: one file's outcome must not disturb another.
        val transfers = transfers()
        val kept = transfers.register("keep")
        transfers.bindPayload(1L, "keep")
        transfers.register("done")
        transfers.bindPayload(2L, "done")

        transfers.complete("done", SendResult.Completed)

        assertFalse(kept.isCompleted)
        assertEquals("keep", transfers.fileFor(1L))
        assertEquals(1, transfers.pendingCount)
    }

    // ----- Metadata and availability ---------------------------------------

    @Test
    fun theMetadataPayloadIsTheSameMetaVocabularyAsLan() {
        val encoded = NearbyPayloads.metadata(
            fileId = "f1",
            name = "clip.mp4",
            size = 144_000_000,
            sha256 = "abc",
            relativePath = "Movies/clip.mp4",
            mime = "video/mp4",
        )
        val parsed = NearbyPayloads.parse(encoded.toByteArray())
        assertNotNull(parsed)
        assertTrue(NearbyPayloads.isMetadata(parsed!!))
        assertEquals(Protocol.META, parsed.type)
        assertEquals("clip.mp4", parsed.string("name"))
        assertEquals(144_000_000L, parsed.long("size"))
        assertEquals("abc", parsed.string("sha256"))
        assertEquals("Movies/clip.mp4", parsed.string("relativePath"))

        // A foreign endpoint can send anything; a crash is not an answer.
        assertNull(NearbyPayloads.parse("not json".toByteArray()))
        assertNull(NearbyPayloads.parse(ByteArray(0)))
    }

    @Test
    fun aDeviceWithoutPlayServicesDegradesToLanWithTheDoctorLine() {
        assertEquals(PlayServices.Availability.AVAILABLE, PlayServices.classify(0))
        assertEquals(PlayServices.Availability.OUTDATED, PlayServices.classify(2))
        assertEquals(PlayServices.Availability.OUTDATED, PlayServices.classify(18))
        assertEquals(PlayServices.Availability.UNAVAILABLE, PlayServices.classify(1))
        assertEquals(PlayServices.Availability.UNAVAILABLE, PlayServices.classify(9))

        // §6.15's exact wording for the amber case.
        assertEquals(
            "Play Services old" to "Nearby Connections may be slower",
            PlayServices.doctorLine(PlayServices.Availability.OUTDATED),
        )
        assertEquals(
            "Wi-Fi LAN only on this device",
            PlayServices.doctorLine(PlayServices.Availability.UNAVAILABLE).second,
        )
    }
}
