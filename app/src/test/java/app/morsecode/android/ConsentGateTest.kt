package app.morsecode.android

import app.morsecode.android.core.network.ConsentRequests
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §17.2 consent gates and §6.16's behaviour rules.
 *
 * The transport suspends inside `ask`, so these tests are also the proof that
 * nothing can cross the wire before Accept: until someone answers, the call
 * has not returned.
 */
class ConsentGateTest {

    private fun request(id: String, peerId: String = "peer-1") = ConsentRequests.Request(
        id = id,
        kind = ConsentRequests.Kind.PEER,
        title = "Ravi's Redmi",
        detail = "Phone · Wi-Fi LAN · 192.168.1.42",
        peerId = peerId,
    )

    @Test
    fun theTransportWaitsUntilSomeoneAnswers() = runTest {
        val consent = ConsentRequests()
        consent.uiVisible = true

        val answer = async { consent.ask(request("r1")) }
        testScheduler.advanceUntilIdle()

        // Still suspended: nothing has been accepted, so nothing may move.
        assertFalse(answer.isCompleted)
        assertEquals("r1", consent.current.value?.id)

        consent.answer("r1", true)
        assertTrue(withTimeout(1000) { answer.await() })
        assertNull("the dialog is dismissed once answered", consent.current.value)
    }

    @Test
    fun rejectIsAnAnswerTooAndTheQueueMovesOn() = runTest {
        val consent = ConsentRequests()
        consent.uiVisible = true

        val first = async { consent.ask(request("r1", peerId = "a")) }
        testScheduler.advanceUntilIdle()
        val second = async { consent.ask(request("r2", peerId = "b")) }
        testScheduler.advanceUntilIdle()

        // One at a time, in order.
        assertEquals("r1", consent.current.value?.id)
        consent.answer("r1", false)
        assertFalse(withTimeout(1000) { first.await() })

        assertEquals("r2", consent.current.value?.id)
        consent.answer("r2", true)
        assertTrue(withTimeout(1000) { second.await() })
    }

    @Test
    fun answeringTheWrongRequestIsIgnored() = runTest {
        val consent = ConsentRequests()
        consent.uiVisible = true
        val answer = async { consent.ask(request("r1")) }
        testScheduler.advanceUntilIdle()

        consent.answer("some-other-id", true)
        testScheduler.advanceUntilIdle()
        assertFalse("a stale notification action must not accept a new peer", answer.isCompleted)

        consent.answer("r1", false)
        assertFalse(withTimeout(1000) { answer.await() })
    }

    @Test
    fun anAlreadyTrustedPeerInTheSameSessionIsNotReprompted() = runTest {
        val consent = ConsentRequests()
        consent.uiVisible = true

        val first = async { consent.ask(request("r1", peerId = "ravi")) }
        testScheduler.advanceUntilIdle()
        consent.answer("r1", true)
        assertTrue(withTimeout(1000) { first.await() })

        // §6.16: the same peer, same live session — no second prompt.
        val second = consent.ask(request("r2", peerId = "ravi"))
        assertTrue(second)
        assertNull(consent.current.value)

        // ...but a NEW peer always asks.
        val stranger = async { consent.ask(request("r3", peerId = "stranger")) }
        testScheduler.advanceUntilIdle()
        assertEquals("r3", consent.current.value?.id)
        consent.answer("r3", false)
        assertFalse(withTimeout(1000) { stranger.await() })
    }

    @Test
    fun trustDiesWithTheSession() = runTest {
        val consent = ConsentRequests()
        consent.uiVisible = true
        consent.trustInSession("ravi")
        assertTrue(consent.isTrusted("ravi"))

        consent.endSession("ravi")
        assertFalse("a new session must re-prompt (§6.16)", consent.isTrusted("ravi"))

        consent.trustInSession("a")
        consent.trustInSession("b")
        consent.endAllSessions()
        assertFalse(consent.isTrusted("a"))
        assertFalse(consent.isTrusted("b"))
    }

    @Test
    fun aBackgroundedRequestRaisesTheHeadsUpInstead() = runTest {
        val consent = ConsentRequests()
        consent.uiVisible = false
        var raised: ConsentRequests.Request? = null
        consent.onBackgroundRequest = { raised = it }

        val answer = async { consent.ask(request("r1")) }
        testScheduler.advanceUntilIdle()

        assertNotNull("§6.16 [GAP]: a heads-up when the app is backgrounded", raised)
        assertEquals("r1", raised!!.id)

        // The notification's action answers the same gate as the dialog.
        consent.answer("r1", true)
        assertTrue(withTimeout(1000) { answer.await() })
    }

    @Test
    fun tearingDownRejectsEverythingStillPending() = runTest {
        val consent = ConsentRequests()
        consent.uiVisible = true
        val a = async { consent.ask(request("r1", peerId = "a")) }
        testScheduler.advanceUntilIdle()
        val b = async { consent.ask(request("r2", peerId = "b")) }
        testScheduler.advanceUntilIdle()

        consent.rejectAll()

        assertFalse(withTimeout(1000) { a.await() })
        assertFalse(withTimeout(1000) { b.await() })
        assertNull(consent.current.value)
    }
}
