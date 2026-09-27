package app.morsecode.android.core.network

import app.morsecode.android.core.util.Ids
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/**
 * §11.2 LAN framing:
 *
 *     magic "MRSC" (4 B) | seq int32 | len int32 | payload | CRC32 int32
 *
 * Chunk size is 256 KB and a frame claiming more than 4× that is rejected
 * outright. Every field is big-endian, written and read here and nowhere else.
 *
 * **THE CRITICAL FRAMING RULE (A6).** The first line of a DATA connection — the
 * JSON header — must be read BYTE BY BYTE from the raw `InputStream` until
 * '\n'. Wrapping a socket that will carry binary chunks in a `BufferedReader`
 * first makes its 8 KB pre-read swallow the opening MRSC frame, and the
 * transfer dies with "chunk magic mismatch — framing slipped". [readHeaderLine]
 * is the only sanctioned way to consume that header, and it consumes exactly
 * the header and not one byte more.
 */
object Framing {

    /** §11.2 chunk size. */
    const val CHUNK_SIZE = 256 * 1024

    /** A frame longer than this is a framing slip, not a big chunk (§11.2). */
    const val MAX_FRAME_PAYLOAD = 4 * CHUNK_SIZE

    val MAGIC: ByteArray = Ids.CHUNK_MAGIC.toByteArray(Charsets.US_ASCII)

    const val HEADER_SIZE = 4 + 4 + 4
    const val TRAILER_SIZE = 4

    /** One decoded chunk. */
    data class Frame(val seq: Int, val payload: ByteArray) {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return seq == other.seq && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = 31 * seq + payload.contentHashCode()
    }

    class FramingException(message: String) : IOException(message)

    fun crc32(payload: ByteArray, offset: Int = 0, length: Int = payload.size): Int {
        val crc = CRC32()
        crc.update(payload, offset, length)
        return crc.value.toInt()
    }

    fun encode(seq: Int, payload: ByteArray, length: Int = payload.size): ByteArray {
        require(length in 0..MAX_FRAME_PAYLOAD) { "frame payload out of range: $length" }
        val out = ByteArray(HEADER_SIZE + length + TRAILER_SIZE)
        System.arraycopy(MAGIC, 0, out, 0, 4)
        writeInt(out, 4, seq)
        writeInt(out, 8, length)
        System.arraycopy(payload, 0, out, HEADER_SIZE, length)
        writeInt(out, HEADER_SIZE + length, crc32(payload, 0, length))
        return out
    }

    fun writeFrame(output: OutputStream, seq: Int, payload: ByteArray, length: Int = payload.size) {
        output.write(encode(seq, payload, length))
        // §11.2: progress advances only after a successful flush.
        output.flush()
    }

    /**
     * Reads exactly one frame. A magic mismatch is reported as such rather than
     * being resynchronised: a slipped stream cannot be recovered by hunting for
     * the next "MRSC", it can only produce a silently corrupt file (§9.4).
     */
    fun readFrame(input: InputStream): Frame {
        val header = readFully(input, HEADER_SIZE)
        for (i in 0 until 4) {
            if (header[i] != MAGIC[i]) {
                throw FramingException(
                    "chunk magic mismatch — framing slipped (expected ${Ids.CHUNK_MAGIC})",
                )
            }
        }
        val seq = readInt(header, 4)
        val length = readInt(header, 8)
        if (length < 0 || length > MAX_FRAME_PAYLOAD) {
            throw FramingException("frame length $length out of range")
        }
        val payload = readFully(input, length)
        val expected = readInt(readFully(input, TRAILER_SIZE), 0)
        val actual = crc32(payload)
        if (expected != actual) {
            // §9.4: a CRC mismatch FAILS the file. It is never "repaired" by
            // appending, which would yield a correctly-sized corrupt file.
            throw FramingException("CRC mismatch on chunk seq=$seq")
        }
        return Frame(seq, payload)
    }

    /**
     * Reads the DATA connection's JSON header line byte by byte, stopping at
     * '\n' and leaving the very next byte — the first byte of the first MRSC
     * frame — unread. This is the A6 regression guard.
     *
     * A `BufferedReader` is permitted only on pure-text branches (HELLO
     * handling), never here.
     */
    fun readHeaderLine(input: InputStream, maxBytes: Int = 64 * 1024): String {
        val buffer = ByteArrayOutputStream(256)
        while (true) {
            val byte = input.read()
            if (byte == -1) {
                if (buffer.size() == 0) throw EOFException("stream closed before header")
                break
            }
            if (byte == '\n'.code) break
            if (buffer.size() >= maxBytes) throw FramingException("header line too long")
            buffer.write(byte)
        }
        // Tolerate CRLF without consuming anything that is not part of the line.
        val bytes = buffer.toByteArray()
        val end = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) {
            bytes.size - 1
        } else {
            bytes.size
        }
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    fun writeHeaderLine(output: OutputStream, header: String) {
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write('\n'.code)
        output.flush()
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(data, read, length - read)
            if (count == -1) throw EOFException("stream ended after $read of $length bytes")
            read += count
        }
        return data
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun readInt(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 24) or
            ((source[offset + 1].toInt() and 0xFF) shl 16) or
            ((source[offset + 2].toInt() and 0xFF) shl 8) or
            (source[offset + 3].toInt() and 0xFF)
}
