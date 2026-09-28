package app.morsecode.android

import androidx.test.core.app.ApplicationProvider
import app.morsecode.android.core.media.TrashStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §6.9: local Files deletes are undoable and have a finite retention period. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TrashStoreTest {

    @Test
    fun trashMovesThenUndoRestoresTheOriginalFile() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "trash-undo.txt").apply { writeText("keep me") }
        val store = TrashStore(context)
        val record = store.trash(source.absolutePath, source.name)

        assertNotNull(record)
        assertFalse(source.exists())
        assertEquals(1, store.entries().size)

        assertNotNull(store.restore(record!!.id))
        assertTrue(source.exists())
        assertEquals("keep me", source.readText())
        assertTrue(store.entries().isEmpty())
        source.delete()
    }

    @Test
    fun expiredEntriesArePurgedAutomatically() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var now = 10_000_000_000L
        val source = File(context.cacheDir, "trash-expired.txt").apply { writeText("old") }
        val store = TrashStore(context, clock = { now })
        val record = store.trash(source.absolutePath, source.name)!!

        now += 31L * 24L * 60L * 60L * 1000L
        assertEquals(1, store.purgeExpired())
        assertTrue(store.entries().none { it.id == record.id })
    }
}
