package app.morsecode.android.core.media

import android.os.Environment

/**
 * §6.9.1 ADDRESS BAR. "Every path segment is an individual 48 dp tap target
 * separated by chevrons; tapping a segment jumps there... The bar keeps its
 * full height even at the storage root with zero segments."
 *
 * The parsing is here so the bar, the WebShare breadcrumb (§7.5) and the tests
 * all agree on what a segment is and where it jumps to.
 */
object PathSegments {

    data class Segment(val label: String, val path: String)

    /** The device's shared-storage root, shown as one labelled segment. */
    fun storageRoot(): String = try {
        Environment.getExternalStorageDirectory().absolutePath
    } catch (e: Exception) {
        "/storage/emulated/0"
    }

    /**
     * Splits a path into jumpable segments, each carrying the absolute path it
     * navigates to. The root itself is always the first segment, so the bar is
     * never empty and "up one level" always has somewhere to go.
     */
    fun of(path: String, root: String = storageRoot()): List<Segment> {
        val clean = path.trimEnd('/')
        val cleanRoot = root.trimEnd('/')
        if (clean.isEmpty() || clean == cleanRoot) {
            return listOf(Segment(ROOT_LABEL, cleanRoot))
        }
        if (!clean.startsWith("$cleanRoot/")) {
            // A volume outside shared storage (SD card, USB-OTG): show it whole.
            val parts = clean.split('/').filter { it.isNotEmpty() }
            var built = ""
            return parts.map { part ->
                built = "$built/$part"
                Segment(part, built)
            }
        }
        val segments = ArrayList<Segment>()
        segments.add(Segment(ROOT_LABEL, cleanRoot))
        var built = cleanRoot
        for (part in clean.removePrefix("$cleanRoot/").split('/')) {
            if (part.isEmpty()) continue
            built = "$built/$part"
            segments.add(Segment(part, built))
        }
        return segments
    }

    /** "↑ up one level"; null at the root, where there is nothing above. */
    fun parentOf(path: String, root: String = storageRoot()): String? {
        val clean = path.trimEnd('/')
        val cleanRoot = root.trimEnd('/')
        if (clean == cleanRoot || clean.length <= cleanRoot.length) return null
        val parent = clean.substringBeforeLast('/', cleanRoot)
        return if (parent.isEmpty()) cleanRoot else parent
    }

    /** What the bar prints: the real absolute path, never a prettified one. */
    fun display(path: String): String = path.trimEnd('/')

    const val ROOT_LABEL = "Internal storage"
}
