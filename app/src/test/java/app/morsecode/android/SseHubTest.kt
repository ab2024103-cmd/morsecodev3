package app.morsecode.android

import app.morsecode.android.core.webshare.SseHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §7.8: events are held and pushed; they are not timed HTTP snapshots. */
class SseHubTest {

    @Test
    fun oneOpenStreamReceivesInitialAndPublishedFramesWithoutReconnecting() {
        var payload = "initial"
        val hub = SseHub(snapshot = { payload }, heartbeatMillis = 60_000L)
        val stream = hub.open("browser-1")
        val initial = readFrame(stream)
        assertEquals("data: initial\n\n", initial)
        assertEquals(1, hub.clientCount("browser-1"))

        payload = "offer-changed"
        hub.publish("browser-1")
        val update = readFrame(stream)
        assertEquals("data: offer-changed\n\n", update)
        assertTrue(hub.clientCount("browser-1") == 1)
        stream.close()
        hub.closeAll()
    }

    private fun readFrame(stream: java.io.InputStream): String {
        val bytes = ArrayList<Byte>()
        var previous = -1
        while (true) {
            val next = stream.read()
            if (next < 0) break
            bytes.add(next.toByte())
            if (previous == '\n'.code && next == '\n'.code) break
            previous = next
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
