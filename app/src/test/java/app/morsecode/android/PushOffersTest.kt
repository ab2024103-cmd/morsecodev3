package app.morsecode.android

import app.morsecode.android.core.model.SendResult
import app.morsecode.android.core.network.WebPeerTransport
import app.morsecode.android.core.network.TransportKind
import app.morsecode.android.core.webshare.PushOffers
import app.morsecode.android.core.webshare.WebSessions
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
 * §7.8 push (phone → browser) and §11.4's WebPeerTransport.
 *
 * The offer's state machine decides whether the phone's row reads Completed,
 * Cancelled or Failed, so it is tested on its own — the SSE frame and the
 * download are just how the answer arrives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PushOffersTest {

    private fun offers(now: () -> Long = { 1000L }) = PushOffers(clock = now)

    @Test
    fun anAcceptedOfferCompletesTheSendWhenTheDownloadFinishes() = runBlocking {
        val registry = offers()
        val (offer, waiter) = registry.offer("s1", "f1", "clip.mp4", "/tmp/clip.mp4", 144_000_000)

        assertEquals(PushOffers.State.OFFERED, offer.state)
        assertFalse("the send waits for the browser", waiter.isCompleted)
        assertEquals(listOf(offer.id), registry.pendingFor("s1").map { it.id })

        registry.accepted(offer.id)
        assertEquals(PushOffers.State.DOWNLOADING, registry.get(offer.id)?.state)
        assertFalse("accepting is not delivering", waiter.isCompleted)

        registry.delivered(offer.id)
        assertTrue(withTimeout(1000) { waiter.await() } is SendResult.Completed)
        assertTrue("a delivered offer is no longer pending", registry.pendingFor("s1").isEmpty())
    }

    @Test
    fun dismissIsCancelledNotFailed() = runBlocking {
        val registry = offers()
        val (offer, waiter) = registry.offer("s1", "f1", "a.pdf", "/tmp/a.pdf", 1000)
        registry.dismissed(offer.id)
        // §9.1: cancelled work is the user's decision; failed work is a defect.
        assertTrue(withTimeout(1000) { waiter.await() } is SendResult.Cancelled)
    }

    @Test
    fun anUnansweredOfferExpiresRatherThanHangingTheWorker() = runBlocking {
        var now = 1_000L
        val registry = offers { now }
        val (_, waiter) = registry.offer("s1", "f1", "a.pdf", "/tmp/a.pdf", 1000)

        assertEquals(0, registry.expireOlderThan(60_000))
        assertFalse(waiter.isCompleted)

        now += 61_000
        assertEquals(1, registry.expireOlderThan(60_000))
        // INV-1 reaches this transport too: the waiter always completes.
        val result = withTimeout(1000) { waiter.await() }
        assertTrue(result is SendResult.Failed)
        assertEquals("the browser did not answer", (result as SendResult.Failed).error)
    }

    @Test
    fun revokingASessionCancelsEverythingItWasOffered() = runBlocking {
        val registry = offers()
        val (_, first) = registry.offer("s1", "f1", "a.pdf", "/tmp/a.pdf", 1000)
        val (_, second) = registry.offer("s1", "f2", "b.pdf", "/tmp/b.pdf", 1000)
        val (_, other) = registry.offer("s2", "f3", "c.pdf", "/tmp/c.pdf", 1000)

        registry.cancelSession("s1", "session revoked")
        assertTrue(withTimeout(1000) { first.await() } is SendResult.Failed)
        assertTrue(withTimeout(1000) { second.await() } is SendResult.Failed)
        assertFalse("another session is untouched", other.isCompleted)
    }

    @Test
    fun stoppingWebShareCompletesEveryWaiter() = runBlocking {
        val registry = offers()
        val (_, waiter) = registry.offer("s1", "f1", "a.pdf", "/tmp/a.pdf", 1000)
        registry.clear()
        assertTrue(withTimeout(1000) { waiter.await() } is SendResult.Failed)
    }

    @Test
    fun offersAreNeverVisibleToAnotherSession() {
        val registry = offers()
        registry.offer("s1", "f1", "a.pdf", "/tmp/a.pdf", 1000)
        assertTrue(registry.pendingFor("s2").isEmpty())
    }

    // ----- §11.4 the transport ---------------------------------------------

    @Test
    fun anAcceptedBrowserBecomesAPeerAndALostOneStopsBeingOne() {
        val sessions = WebSessions()
        val found = ArrayList<String>()
        val lost = ArrayList<String>()
        val transport = WebPeerTransport(
            sessions = sessions,
            offers = offers(),
            onPeerFound = { found.add(it.deviceId + "|" + it.transport) },
            onPeerLost = { lost.add(it) },
        )

        val pending = sessions.open("Chrome", "192.168.1.88")
        transport.publish(sessions.active.value)
        assertTrue("a pending browser is not a peer", found.isEmpty())

        val accepted = sessions.accept(pending.id)!!
        transport.publish(sessions.active.value)
        // §11.1: the same list as phones, with a WEB badge (G11).
        assertEquals(listOf("web:${accepted.id}|${TransportKind.WEB}"), found)

        sessions.revoke(accepted.id)
        transport.publish(sessions.active.value)
        assertEquals(listOf("web:${accepted.id}"), lost)
    }

    @Test
    fun connectingToABrowserPeerYieldsASessionTheEngineCanDrive() = runBlocking {
        val sessions = WebSessions()
        val registry = offers()
        val transport = WebPeerTransport(sessions, registry, {}, {})
        val accepted = sessions.accept(sessions.open("Chrome", "1.2.3.4").id)!!

        val session = transport.connect("web:${accepted.id}")
        assertNotNull(session)
        assertEquals(TransportKind.WEB, session!!.transport)
        assertEquals("Chrome", session.peerName)
        assertTrue(session.isAlive)

        // A pending or unknown browser is not connectable.
        assertNull(transport.connect("web:nope"))
    }
}
