package app.morsecode.android

import app.morsecode.android.core.network.Framing
import app.morsecode.android.core.util.Integrity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * §9.4 integrity and the resume-offset maths §21.1 names explicitly.
 */
class IntegrityTest {

    @Test
    fun sha256MatchesTheKnownValueForAnEmptyInput() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Integrity.sha256(ByteArray(0)),
        )
        assertEquals(
            Integrity.sha256("morsecode".toByteArray()),
            Integrity.sha256(ByteArrayInputStream("morsecode".toByteArray())),
        )
    }

    @Test
    fun theTwoHundredAndFiftySixMegabyteLimitDecidesWhoIsPreHashed() {
        val limit = Integrity.PREHASH_LIMIT_BYTES
        assertEquals(256L * 1024 * 1024, limit)
        assertTrue(Integrity.shouldPreHash(1))
        assertTrue(Integrity.shouldPreHash(limit))
        assertFalse("a file over the limit skips the pre-hash", Integrity.shouldPreHash(limit + 1))
        assertFalse(Integrity.shouldPreHash(0))
        // The low tier's smaller limit is honoured through the same call.
        assertFalse(Integrity.shouldPreHash(100L * 1024 * 1024, limitBytes = 64L * 1024 * 1024))
    }

    @Test
    fun verificationChecksSizeAlwaysAndTheHashWhenBothSidesHaveOne() {
        assertNull(Integrity.verify(100, 100, "abc", "ABC"))
        assertNull("size-only check is valid on the lowest tier", Integrity.verify(100, 100, "abc", null))
        assertNotNull(Integrity.verify(100, 99, "abc", "abc"))
        assertTrue(Integrity.verify(100, 99, null, null)!!.contains("size mismatch"))
        assertTrue(Integrity.verify(100, 100, "abc", "def")!!.contains("checksum mismatch"))
    }

    @Test
    fun resumeOffsetsAreClampedToSomethingSeekable() {
        assertEquals(0L, Integrity.resumeOffsetFor(partLength = 0, totalBytes = 1000))
        assertEquals(0L, Integrity.resumeOffsetFor(partLength = -5, totalBytes = 1000))
        assertEquals(400L, Integrity.resumeOffsetFor(partLength = 400, totalBytes = 1000))
        // A part at or past the full size means "start again", never a seek
        // past the end of the file.
        assertEquals(0L, Integrity.resumeOffsetFor(partLength = 1000, totalBytes = 1000))
        assertEquals(0L, Integrity.resumeOffsetFor(partLength = 1200, totalBytes = 1000))
    }

    @Test
    fun remainingBytesFollowTheClampedOffset() {
        assertEquals(600L, Integrity.remainingBytes(totalBytes = 1000, resumeOffset = 400))
        assertEquals(1000L, Integrity.remainingBytes(totalBytes = 1000, resumeOffset = 0))
        assertEquals(1000L, Integrity.remainingBytes(totalBytes = 1000, resumeOffset = 5000))
    }

    @Test
    fun theResumedChunkSequenceContinuesRatherThanRestarting() {
        val chunk = Framing.CHUNK_SIZE
        assertEquals(0, Integrity.resumeSequence(0, chunk))
        assertEquals(0, Integrity.resumeSequence(chunk - 1L, chunk))
        assertEquals(1, Integrity.resumeSequence(chunk.toLong(), chunk))
        assertEquals(4, Integrity.resumeSequence(4L * chunk + 10, chunk))
        // A 144 MB video resuming at 48.9 MB: 195 whole chunks are already in
        // the .part file, so the next frame is seq 195.
        assertEquals(195, Integrity.resumeSequence(48_900_000L, chunk))
    }
}
