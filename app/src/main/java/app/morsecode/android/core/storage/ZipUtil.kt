package app.morsecode.android.core.storage

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * §7.2 streamed archives, used by `/download-folder`, `/download-zip` and by
 * §6.9's "a selected folder is transferred as one archive, named
 * `<folder>.zip`".
 *
 * The rules, all from §7.2:
 *  - built THROUGH A PIPE as it is sent, never fully assembled first — nothing
 *    is buffered to disk or memory, so a 40 GB folder starts downloading
 *    immediately (§20.10 calls the alternative a defect);
 *  - ZIP64-capable, so >4 GB and >65 535 entries work;
 *  - the UTF-8 name flag is set, so non-ASCII names survive;
 *  - generation ABORTS the moment a broken pipe says the client is gone.
 *
 * `java.util.zip.ZipOutputStream` writes ZIP64 extra fields automatically when
 * an entry or the archive exceeds the 32-bit limits, which is why entries are
 * written without a pre-declared size.
 */
object ZipUtil {

    /** A file inside the archive: the entry path and how to read its bytes. */
    data class Source(val entryPath: String, val length: Long, val open: () -> InputStream)

    /**
     * Writes [sources] into [output] as it goes.
     *
     * @param onProgress cumulative bytes read from the sources.
     * @return the number of entries written.
     */
    @Throws(IOException::class)
    fun writeTo(
        output: OutputStream,
        sources: List<Source>,
        onProgress: (Long) -> Unit = {},
    ): Int {
        var written = 0L
        var entries = 0
        // The Charset-taking constructor was added after API 23. Android's
        // one-argument ZipOutputStream has used UTF-8 entry names since the
        // platform's ZIP implementation adopted the Java 7 contract, and it
        // works on the Android-6 reference device (§3.2 / A10).
        val zip = ZipOutputStream(output)
        // Stored-with-deflate is the default; media is already compressed, so
        // the level is kept low to spend CPU on throughput rather than ratio.
        zip.setLevel(COMPRESSION_LEVEL)
        try {
            for (source in sources) {
                zip.putNextEntry(ZipEntry(source.entryPath))
                source.open().use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        zip.write(buffer, 0, read)
                        written += read
                        onProgress(written)
                    }
                }
                zip.closeEntry()
                entries++
            }
            zip.finish()
        } catch (broken: IOException) {
            // The client went away mid-archive: stop generating immediately
            // rather than finishing a zip nobody will read (§7.2).
            throw broken
        }
        return entries
    }

    /**
     * Turns a directory into entries, recursively, with paths relative to the
     * folder itself — so `Camera.zip` contains `Camera/IMG_1.jpg`, which is
     * what makes §21.2 case 11's "structure reconstructed exactly" true.
     */
    fun sourcesOf(directory: File, includeRoot: Boolean = true): List<Source> {
        val root = directory.absoluteFile
        val prefix = if (includeRoot) root.name + "/" else ""
        val out = ArrayList<Source>()
        walk(root, prefix, out)
        return out
    }

    private fun walk(dir: File, prefix: String, out: ArrayList<Source>) {
        val children = dir.listFiles() ?: return
        for (child in children.sortedBy { it.name.lowercase() }) {
            if (child.isDirectory) {
                walk(child, prefix + child.name + "/", out)
            } else {
                out.add(Source(prefix + child.name, child.length()) { child.inputStream() })
            }
        }
    }

    /** The recursive size a selected folder contributes to the bar (§6.9). */
    fun recursiveSize(directory: File): Long {
        val children = directory.listFiles() ?: return 0
        var total = 0L
        for (child in children) {
            total += if (child.isDirectory) recursiveSize(child) else child.length()
        }
        return total
    }

    /** `<folder>.zip`, and a safe name for a selection archive. */
    fun archiveNameFor(name: String): String = "${name.trim().ifEmpty { "files" }}.zip"

    private const val BUFFER = 64 * 1024
    private const val COMPRESSION_LEVEL = 1
}
