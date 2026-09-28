package app.morsecode.android

import app.morsecode.android.core.network.RoundRobinChunkScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** §10.2: frame turns are explicit, bounded and remove failed peers safely. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoundRobinChunkSchedulerTest {

    @Test
    fun aPeerGetsAtMostItsConfiguredWindowBeforeTheWaitingPeerGetsATurn() = runTest {
        val scheduler = RoundRobinChunkScheduler(listOf("a", "b"), windowChunks = 1)
        val order = ArrayList<String>()

        val first = scheduler.acquire("a")!!
        order.add("a")
        val b = async {
            val permit = scheduler.acquire("b")!!
            order.add("b")
            permit.release()
        }
        yield()

        first.release()
        b.await()
        val second = scheduler.acquire("a")!!
        order.add("a")
        second.release()

        assertEquals(listOf("a", "b", "a"), order)
    }

    @Test
    fun removingTheReservedPeerWakesTheNextPeerInsteadOfStrandingTheWindow() = runTest {
        val scheduler = RoundRobinChunkScheduler(listOf("a", "b"), windowChunks = 1)
        val first = scheduler.acquire("a")!!
        val b = async { scheduler.acquire("b") }
        yield()

        first.release() // b now owns the next fair turn
        scheduler.remove("a")
        val bPermit = b.await()
        bPermit?.release()

        assertNull(scheduler.acquire("a"))
    }
}
