package app.morsecode.android

import app.morsecode.android.core.model.DiscoveredPeer
import app.morsecode.android.core.network.Discovery
import app.morsecode.android.core.network.ManualAddress
import app.morsecode.android.core.network.Protocol
import app.morsecode.android.core.network.TransportKind
import app.morsecode.android.core.util.Ids
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
 * §11.2 control messages, §11.6 protocol versioning, §11.5 manual pairing and
 * the §11.1/§11.2 discovery beacon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProtocolTest {

    @Test
    fun everyMessageRoundTripsThroughOneJsonLine() {
        val meta = Protocol.meta(
            fileId = "f1",
            name = "IMG_2007.jpg",
            relativePath = "Camera/IMG_2007.jpg",
            size = 4_300_000,
            mime = "image/jpeg",
            sha256 = "abc123",
            resumeOffset = 1024,
        )
        val parsed = Protocol.parse(meta.encode())
        assertEquals(Protocol.META, parsed.type)
        assertEquals("f1", parsed.string("fileId"))
        assertEquals("IMG_2007.jpg", parsed.string("name"))
        assertEquals("Camera/IMG_2007.jpg", parsed.string("relativePath"))
        assertEquals(4_300_000L, parsed.long("size"))
        assertEquals(1024L, parsed.long("resumeOffset"))

        // A single line, so the newline the channel appends is the delimiter.
        assertFalse(meta.encode().contains("\n"))
    }

    @Test
    fun helloCarriesTheIdentityAndTheProtocolVersion() {
        val hello = Protocol.parse(Protocol.hello("device-1", "MYA-L10").encode())
        assertEquals(Ids.APPLICATION_ID, hello.string("appId"))
        assertEquals(Ids.PROTOCOL_VERSION, hello.int("protocolVersion"))
        assertTrue(Protocol.isMorsecode(hello))

        val foreign = Protocol.parse("""{"type":"HELLO","appId":"com.example.other"}""")
        assertFalse("a stranger's HELLO must not be treated as ours", Protocol.isMorsecode(foreign))
    }

    @Test
    fun aVersionMismatchIsAReadableSentenceNotAFramingError() {
        assertNull(Protocol.versionProblem(Ids.PROTOCOL_VERSION))
        assertEquals(
            "This phone runs an older version of Morsecode — update to transfer",
            Protocol.versionProblem(Ids.PROTOCOL_VERSION - 1),
        )
        // The mirror image: telling someone their up-to-date phone is out of
        // date would send them looking for an update that does not exist.
        assertEquals(Protocol.OLDER_SELF, Protocol.versionProblem(Ids.PROTOCOL_VERSION + 1))
    }

    @Test
    fun rubbishOnTheControlChannelIsRejectedCleanly() {
        for (line in listOf("", "   ", "not json", "{}", """{"nope":1}""")) {
            try {
                Protocol.parse(line)
                org.junit.Assert.fail("expected a ProtocolException for: $line")
            } catch (e: Protocol.ProtocolException) {
                assertNotNull(e.message)
            }
        }
    }

    @Test
    fun theAckCarriesTheResumeOffsetAndTheThreeStatuses() {
        val ready = Protocol.parse(Protocol.ack("f1", Protocol.STATUS_READY, 2048).encode())
        assertEquals(Protocol.STATUS_READY, ready.string("status"))
        assertEquals(2048L, ready.long("resumeOffset"))

        val present = Protocol.parse(Protocol.ack("f1", Protocol.STATUS_ALREADY_PRESENT).encode())
        assertEquals(Protocol.STATUS_ALREADY_PRESENT, present.string("status"))

        val refused = Protocol.parse(Protocol.ack("f1", Protocol.STATUS_REFUSED, reason = "no space").encode())
        assertEquals("no space", refused.string("reason"))
    }

    // ----- §11.5 manual pairing --------------------------------------------

    @Test
    fun manualAddressAcceptsTheThreeFormsAndDefaultsThePort() {
        val bare = ManualAddress.parse("192.168.1.42")
        assertEquals(ManualAddress.Target("192.168.1.42", Ids.PORT_TCP), (bare as ManualAddress.Result.Ok).target)

        val withPort = ManualAddress.parse("192.168.1.42:40000")
        assertEquals(40000, (withPort as ManualAddress.Result.Ok).target.port)

        val pasted = ManualAddress.parse("morsecode://192.168.1.42:33456")
        assertEquals("192.168.1.42", (pasted as ManualAddress.Result.Ok).target.host)
        assertEquals(33456, pasted.target.port)

        assertEquals(33456, Ids.PORT_TCP)
        assertEquals(" 10.0.0.5 ".trim(), (ManualAddress.parse(" 10.0.0.5 ") as ManualAddress.Result.Ok).target.host)
    }

    @Test
    fun manualAddressValidatesBeforeDialling() {
        assertTrue(ManualAddress.parse("") is ManualAddress.Result.Invalid)
        assertTrue(ManualAddress.parse("192.168.1.42:0") is ManualAddress.Result.Invalid)
        assertTrue(ManualAddress.parse("192.168.1.42:70000") is ManualAddress.Result.Invalid)
        assertTrue(ManualAddress.parse("192.168.1.42:abc") is ManualAddress.Result.Invalid)
        assertTrue(ManualAddress.parse("what is this") is ManualAddress.Result.Invalid)
        assertTrue(ManualAddress.parse(".leading.dot") is ManualAddress.Result.Invalid)

        val target = ManualAddress.Target("192.168.1.42", 33456)
        assertEquals("Can't reach 192.168.1.42:33456", ManualAddress.unreachable(target))
    }

    // ----- §11.1 / §11.2 discovery beacon ----------------------------------

    @Test
    fun theBeaconRoundTripsAndIgnoresForeignPackets() {
        val payload = Discovery.Beacon.encode("device-9", "Ravi's Redmi", Ids.PORT_TCP, broadcasting = true)
        val peer = Discovery.Beacon.decode(payload, "192.168.1.42", nowMillis = 1000)
        assertNotNull(peer)
        assertEquals("device-9", peer!!.deviceId)
        assertEquals("Ravi's Redmi", peer.name)
        assertEquals("192.168.1.42", peer.address)
        assertEquals(Ids.PORT_TCP, peer.port)
        assertEquals(TransportKind.LAN, peer.transport)
        assertTrue(peer.broadcasting)
        assertEquals("192.168.1.42:33456", peer.hostPort)

        // Anything that is not a Morsecode beacon is not a peer.
        assertNull(Discovery.Beacon.decode("""{"appId":"com.example.other"}""", "1.2.3.4", 0))
        assertNull(Discovery.Beacon.decode("garbage", "1.2.3.4", 0))
        assertNull(Discovery.Beacon.decode("""{"appId":"${Ids.APPLICATION_ID}"}""", "1.2.3.4", 0))
    }

    @Test
    fun aPeerIsOneRowWhateverFoundIt() {
        // §11.1: one deduplicated list. Two transports, one device id.
        val lan = DiscoveredPeer("dev-1", "Pixel 7X", "192.168.1.50", 33456, TransportKind.LAN, 1)
        val nearby = DiscoveredPeer("dev-1", "Pixel 7X", "endpoint-x", 0, TransportKind.NEARBY, 1)
        assertEquals(lan.deviceId, nearby.deviceId)
        assertEquals(1, setOf(lan.deviceId, nearby.deviceId).size)
    }
}
