package app.morsecode.android

import app.morsecode.android.core.network.Framing
import app.morsecode.android.core.util.Ids
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * A6: "LAN transfer never fails with chunk magic mismatch (byte-by-byte header
 * read present and covered by a unit test)".
 *
 * This is that test. It covers the framing codec both ways and, critically,
 * the header read: the last test reproduces the actual production defect by
 * wrapping the stream in a BufferedReader and showing that the first MRSC
 * frame is then gone — which is why §11.2 forbids it.
 */
class FramingTest {

    private fun payload(size: Int, seed: Int = 7): ByteArray =
        ByteArray(size) { index -> ((index * 31 + seed) and 0xFF).toByte() }

    @Test
    fun theMagicIsTheSpecifiedFourBytes() {
        assertEquals("MRSC", Ids.CHUNK_MAGIC)
        assertArrayEquals("MRSC".toByteArray(Charsets.US_ASCII), Framing.MAGIC)
        assertEquals(256 * 1024, Framing.CHUNK_SIZE)
    }

    @Test
    fun encodeThenDecodeRoundTripsEveryFrame() {
        val frames = listOf(
            0 to payload(0),
            1 to payload(1),
            2 to payload(Framing.CHUNK_SIZE),
            3 to payload(5_000, seed = 13),
        )
        val stream = ByteArrayOutputStream()
        for ((seq, data) in frames) Framing.writeFrame(stream, seq, data)

        val input = ByteArrayInputStream(stream.toByteArray())
        for ((seq, data) in frames) {
            val frame = Framing.readFrame(input)
            assertEquals(seq, frame.seq)
            assertArrayEquals(data, frame.payload)
        }
        assertEquals(-1, input.read())
    }

    @Test
    fun aFlippedByteIsCaughtByTheCrcAndFailsTheFile() {
        val encoded = Framing.encode(9, payload(2048))
        // Corrupt one byte of the payload, leaving the length intact — the
        // case that would otherwise produce a correctly-sized corrupt file.
        encoded[Framing.HEADER_SIZE + 100] = (encoded[Framing.HEADER_SIZE + 100] + 1).toByte()
        try {
            Framing.readFrame(ByteArrayInputStream(encoded))
            fail("a CRC mismatch must fail the frame, never be repaired (§9.4)")
        } catch (e: Framing.FramingException) {
            assertTrue(e.message!!.contains("CRC mismatch"))
        }
    }

    @Test
    fun aSlippedStreamIsReportedAsAMagicMismatch() {
        val encoded = Framing.encode(1, payload(64))
        // Drop one leading byte: the classic "framing slipped" state.
        val slipped = encoded.copyOfRange(1, encoded.size)
        try {
            Framing.readFrame(ByteArrayInputStream(slipped))
            fail("expected a magic mismatch")
        } catch (e: Framing.FramingException) {
            assertTrue(e.message!!.contains("chunk magic mismatch"))
        }
    }

    @Test
    fun anImplausibleLengthIsRejectedRatherThanAllocated() {
        val header = ByteArray(Framing.HEADER_SIZE)
        System.arraycopy(Framing.MAGIC, 0, header, 0, 4)
        // seq 0, length 64 MB — far over 4x the chunk size.
        header[8] = 0x04
        try {
            Framing.readFrame(ByteArrayInputStream(header))
            fail("expected the length guard to fire")
        } catch (e: Framing.FramingException) {
            assertTrue(e.message!!.contains("out of range"))
        }
    }

    @Test
    fun theHeaderIsReadByteByByteAndLeavesTheFirstFrameIntact() {
        // Exactly the DATA connection's shape: one JSON line, then chunks.
        val header = """{"name":"IMG_2007.jpg","size":4300000,"resumeOffset":0}"""
        val body = payload(1024, seed = 3)
        val stream = ByteArrayOutputStream()
        Framing.writeHeaderLine(stream, header)
        Framing.writeFrame(stream, 0, body)

        val input = ByteArrayInputStream(stream.toByteArray())
        assertEquals(header, Framing.readHeaderLine(input))

        // The very next byte must still be the first byte of "MRSC".
        val frame = Framing.readFrame(input)
        assertEquals(0, frame.seq)
        assertArrayEquals(body, frame.payload)
    }

    @Test
    fun aBufferedReaderWouldHaveSwallowedTheFirstFrame() {
        // The regression this rule exists for, demonstrated rather than
        // asserted in a comment: BufferedReader pre-reads up to 8 KB, so the
        // frame that followed the header is gone from the stream.
        val header = """{"name":"clip.mp4"}"""
        val stream = ByteArrayOutputStream()
        Framing.writeHeaderLine(stream, header)
        Framing.writeFrame(stream, 0, payload(2048))

        val input = ByteArrayInputStream(stream.toByteArray())
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        assertEquals(header, reader.readLine())

        // Nothing is left for the framing reader: the pre-read ate it.
        assertEquals(0, input.available())
        try {
            Framing.readFrame(input)
            fail("expected the stream to be empty after a BufferedReader pre-read")
        } catch (e: java.io.EOFException) {
            assertTrue(e.message!!.contains("stream ended"))
        }
    }

    @Test
    fun headerReadingToleratesCrlfAndRefusesARunawayLine() {
        val input = ByteArrayInputStream("{\"a\":1}\r\nrest".toByteArray())
        assertEquals("{\"a\":1}", Framing.readHeaderLine(input))

        val runaway = ByteArrayInputStream(ByteArray(4096) { 'x'.code.toByte() })
        try {
            Framing.readHeaderLine(runaway, maxBytes = 128)
            fail("expected the header length guard to fire")
        } catch (e: Framing.FramingException) {
            assertTrue(e.message!!.contains("header line too long"))
        }
    }
}
