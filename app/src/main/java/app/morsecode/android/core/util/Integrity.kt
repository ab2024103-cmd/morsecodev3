package app.morsecode.android.core.util

import java.io.InputStream
import java.security.MessageDigest

/**
 * §9.4 INTEGRITY AND DE-DUPLICATION.
 *
 * The sender SHA-256s files up to PREHASH_LIMIT (256 MB, tier-scaled) and
 * caches the result per item; larger files skip the pre-hash entirely. The
 * receiver compares that hash to decide "already present" → SKIPPED, which is
 * reported distinctly from FAILED everywhere (§9.6).
 *
 * Verification after a receive: size always, sha256 except on the lowest tier,
 * where the size-only check is logged rather than silently downgraded (§13).
 */
object Integrity {

    /** §9.4 PREHASH_LIMIT. [DeviceTier.preHashLimitBytes] scales it down. */
    const val PREHASH_LIMIT_BYTES = 256L * 1024 * 1024

    private const val BUFFER = 64 * 1024

    fun shouldPreHash(sizeBytes: Long, limitBytes: Long = PREHASH_LIMIT_BYTES): Boolean =
        sizeBytes in 1..limitBytes

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
        return hex(digest.digest())
    }

    fun sha256(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * The post-receive check. Size is compared on every tier; the hash is
     * compared when both sides have one and the tier allows it.
     *
     * Returns null when the file verifies, or the reason it did not — callers
     * turn that into FAILED with a message the user can act on (§6.19).
     */
    fun verify(
        expectedSize: Long,
        actualSize: Long,
        expectedSha: String?,
        actualSha: String?,
    ): String? {
        if (expectedSize != actualSize) {
            return "size mismatch: expected $expectedSize, got $actualSize"
        }
        if (expectedSha != null && actualSha != null && !expectedSha.equals(actualSha, true)) {
            return "checksum mismatch"
        }
        return null
    }

    /**
     * §9.4 resume: the receiver reports the length of its `.part` file and the
     * sender seeks there. An offset past the end of the file, or a negative
     * one, restarts from zero rather than seeking into nowhere.
     */
    fun resumeOffsetFor(partLength: Long, totalBytes: Long): Long = when {
        partLength <= 0 -> 0
        partLength >= totalBytes -> 0
        else -> partLength
    }

    /** Bytes still to move for an item resuming at [resumeOffset]. */
    fun remainingBytes(totalBytes: Long, resumeOffset: Long): Long =
        (totalBytes - resumeOffsetFor(resumeOffset, totalBytes)).coerceAtLeast(0)

    /**
     * The chunk sequence a resumed send continues from: whole chunks already
     * written are not re-sent, and the seq numbering continues rather than
     * restarting, so the receiver's ordering check still holds (§11.2).
     */
    fun resumeSequence(resumeOffset: Long, chunkSize: Int): Int {
        if (resumeOffset <= 0 || chunkSize <= 0) return 0
        return (resumeOffset / chunkSize).toInt()
    }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            if (value < 0x10) out.append('0')
            out.append(Integer.toHexString(value))
        }
        return out.toString()
    }
}
