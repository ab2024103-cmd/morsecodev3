package app.morsecode.android.core.webshare

/**
 * §7.7's chunk arithmetic, which §21.1 names as a required unit test
 * ("chunked-upload index math").
 *
 * "Files are sliced client-side into 4 MB chunks and POSTed sequentially with
 * a file id and chunk index, so pause means 'stop sending chunks' and resume
 * means 'ask /api/upload-status for the last index and continue' — never
 * restart from zero."
 *
 * The browser and the server both derive their indices from these rules, so an
 * off-by-one cannot land on only one side.
 */
object UploadMath {

    /** §7.7: 4 MB. The same constant the site's `CHUNK` uses. */
    const val CHUNK_BYTES = 4 * 1024 * 1024

    /** How many chunks a file of [sizeBytes] becomes. An empty file is one. */
    fun chunkCount(sizeBytes: Long, chunkBytes: Int = CHUNK_BYTES): Int {
        if (sizeBytes <= 0) return 1
        val chunks = (sizeBytes + chunkBytes - 1) / chunkBytes
        return chunks.toInt().coerceAtLeast(1)
    }

    /** The byte range of one chunk, clamped to the file. */
    fun rangeOf(index: Int, sizeBytes: Long, chunkBytes: Int = CHUNK_BYTES): LongRange {
        val start = index.toLong() * chunkBytes
        if (start >= sizeBytes) return LongRange.EMPTY
        val endExclusive = minOf(sizeBytes, start + chunkBytes)
        return start until endExclusive
    }

    /** True for the chunk that finishes the file, which the server publishes on. */
    fun isLast(index: Int, sizeBytes: Long, chunkBytes: Int = CHUNK_BYTES): Boolean =
        index == chunkCount(sizeBytes, chunkBytes) - 1

    /**
     * Where a resumed upload continues. The server reports how many chunks it
     * has, and that number IS the next index — never zero, and never one past
     * the end.
     */
    fun resumeIndex(receivedChunks: Int, sizeBytes: Long, chunkBytes: Int = CHUNK_BYTES): Int =
        receivedChunks.coerceIn(0, chunkCount(sizeBytes, chunkBytes))

    /** Bytes already accepted for [receivedChunks], for the progress bar. */
    fun bytesFor(receivedChunks: Int, sizeBytes: Long, chunkBytes: Int = CHUNK_BYTES): Long =
        (receivedChunks.toLong() * chunkBytes).coerceIn(0, sizeBytes.coerceAtLeast(0))

    /** True once every chunk has landed. */
    fun isComplete(receivedChunks: Int, sizeBytes: Long, chunkBytes: Int = CHUNK_BYTES): Boolean =
        receivedChunks >= chunkCount(sizeBytes, chunkBytes)
}
