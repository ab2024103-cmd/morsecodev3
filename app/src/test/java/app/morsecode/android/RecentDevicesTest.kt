package app.morsecode.android

import androidx.test.core.app.ApplicationProvider
import app.morsecode.android.core.data.RecentDevices
import app.morsecode.android.core.network.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §17.4 recent devices: an id and a name, and nothing that could skip consent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecentDevicesTest {

    private fun store() = RecentDevices(
        ApplicationProvider.getApplicationContext(),
        limit = 3,
    ).also { it.clear() }

    @Test
    fun devicesAreRememberedNewestFirstAndCapped() {
        val recent = store()
        recent.remember("a", "Ravi's Redmi", TransportKind.LAN, "144 MB video", nowMillis = 100)
        recent.remember("b", "Office Laptop", TransportKind.WEB, "WebShare", nowMillis = 200)
        recent.remember("c", "Pixel 7X", TransportKind.NEARBY, "12 files", nowMillis = 300)
        recent.remember("d", "MYA-L10", TransportKind.LAN, nowMillis = 400)

        val all = recent.all()
        assertEquals(3, all.size)
        assertEquals(listOf("d", "c", "b"), all.map { it.deviceId })
        assertEquals("Pixel 7X", all[1].name)
        assertEquals(TransportKind.NEARBY, all[1].transport)
        assertEquals("12 files", all[1].summary)
    }

    @Test
    fun seeingADeviceAgainMovesItUpRatherThanDuplicating() {
        val recent = store()
        recent.remember("a", "Ravi's Redmi", TransportKind.LAN, nowMillis = 100)
        recent.remember("b", "Pixel 7X", TransportKind.LAN, nowMillis = 200)
        recent.remember("a", "Ravi's Redmi", TransportKind.LAN, "2 min ago", nowMillis = 300)

        val all = recent.all()
        assertEquals(2, all.size)
        assertEquals("a", all.first().deviceId)
        assertEquals("2 min ago", all.first().summary)
    }

    @Test
    fun clearAndForgetDoWhatTheySay() {
        val recent = store()
        recent.remember("a", "Ravi's Redmi", TransportKind.LAN)
        recent.remember("b", "Pixel 7X", TransportKind.LAN)
        recent.forget("a")
        assertEquals(listOf("b"), recent.all().map { it.deviceId })
        recent.clear()
        assertTrue(recent.all().isEmpty())
    }

    @Test
    fun nothingStoredCouldEverSkipConsent() {
        // §17.4: "recency shortens discovery, never consent". The entry has an
        // id, a name, a transport, a timestamp and a summary — no token, no
        // key, no "trusted" flag for a future change to lean on.
        val fields = RecentDevices.Entry::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(
            setOf("deviceId", "name", "transport", "lastSeenMillis", "summary"),
            fields,
        )
    }
}
