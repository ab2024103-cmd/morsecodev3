package app.morsecode.android

import app.morsecode.android.core.storage.Conflicts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §9.5 CONFLICT POLICY — all four policies and "apply to all", which §21.1
 * names as a required unit test.
 */
class ConflictsTest {

    private val taken = setOf("photo.jpg", "photo (1).jpg")

    @Test
    fun noExistingFileMeansJustWriteIt() {
        val decision = Conflicts.decide("Camera/photo.jpg", exists = false, policy = Conflicts.Policy.RENAME)
        assertEquals(Conflicts.Decision.Write("photo.jpg"), decision)
    }

    @Test
    fun allFourPoliciesBehave() {
        assertEquals(
            Conflicts.Decision.Overwrite("photo.jpg"),
            Conflicts.decide("photo.jpg", true, Conflicts.Policy.OVERWRITE, taken),
        )
        assertTrue(
            Conflicts.decide("photo.jpg", true, Conflicts.Policy.SKIP, taken) is Conflicts.Decision.Skip,
        )
        assertEquals(
            // "Keep both" walks past the name already taken.
            Conflicts.Decision.KeepBoth("photo (2).jpg"),
            Conflicts.decide("photo.jpg", true, Conflicts.Policy.RENAME, taken),
        )
        assertEquals(
            Conflicts.Decision.Ask("photo.jpg"),
            Conflicts.decide("photo.jpg", true, Conflicts.Policy.ASK, taken),
        )
    }

    @Test
    fun conflictsAreEvaluatedPerFullRelativePath() {
        // §9.5: AlbumA/img1.jpg and AlbumB/img1.jpg must never collide. The
        // caller passes the full path; only the leaf becomes the file name.
        assertEquals("img1.jpg", Conflicts.fileNameOf("AlbumA/img1.jpg"))
        assertEquals("img1.jpg", Conflicts.fileNameOf("AlbumB/img1.jpg"))
        assertEquals("loose.txt", Conflicts.fileNameOf("loose.txt"))
    }

    @Test
    fun anIdenticalFileIsAlreadyPresentWhateverThePolicySays() {
        val decision = Conflicts.decide(
            relativePath = "photo.jpg",
            exists = true,
            policy = Conflicts.Policy.OVERWRITE,
            takenNames = taken,
            incomingSize = 1024,
            existingSize = 1024,
            incomingSha = "ABCD",
            existingSha = "abcd",
        )
        assertTrue(decision is Conflicts.Decision.Skip)
        assertEquals(
            "identical file already present on receiver",
            (decision as Conflicts.Decision.Skip).reason,
        )
    }

    @Test
    fun sameSizeWithoutHashesIsAConflictNotADuplicate() {
        // §9.4 wants sha equality; equal sizes alone prove nothing.
        assertFalse(Conflicts.isAlreadyPresent(1024, 1024, null, null))
        assertFalse(Conflicts.isAlreadyPresent(1024, 2048, "a", "a"))
        assertTrue(Conflicts.isAlreadyPresent(1024, 1024, "a", "A"))
    }

    @Test
    fun keepBothNumbersUpwardAndKeepsTheExtension() {
        assertEquals("photo (1).jpg", Conflicts.nextFreeName("photo.jpg", setOf("photo.jpg")))
        assertEquals(
            "photo (3).jpg",
            Conflicts.nextFreeName("photo.jpg", setOf("photo.jpg", "photo (1).jpg", "photo (2).jpg")),
        )
        assertEquals("free.jpg", Conflicts.nextFreeName("free.jpg", setOf("photo.jpg")))
        // Dots in the stem must not become the extension.
        assertEquals(
            "archive.tar (1).gz",
            Conflicts.nextFreeName("archive.tar.gz", setOf("archive.tar.gz")),
        )
        // No extension at all.
        assertEquals("README (1)", Conflicts.nextFreeName("README", setOf("README")))
    }

    @Test
    fun applyToAllIsRememberedPerBatchAndNeverLeaksIntoTheNext() {
        val policy = Conflicts.BatchPolicy(Conflicts.Policy.RENAME)
        assertEquals(Conflicts.Policy.RENAME, policy.policyFor("batch-1"))

        policy.applyToAll("batch-1", Conflicts.Policy.OVERWRITE)
        assertEquals(Conflicts.Policy.OVERWRITE, policy.policyFor("batch-1"))
        assertTrue(policy.hasChoiceFor("batch-1"))

        // A different batch still gets the default — a decision about holiday
        // photos must not overwrite next week's documents.
        assertEquals(Conflicts.Policy.RENAME, policy.policyFor("batch-2"))
        assertFalse(policy.hasChoiceFor("batch-2"))

        policy.forget("batch-1")
        assertEquals(Conflicts.Policy.RENAME, policy.policyFor("batch-1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun applyToAllRefusesAskAsAChoice() {
        Conflicts.BatchPolicy(Conflicts.Policy.RENAME).applyToAll("batch-1", Conflicts.Policy.ASK)
    }
}
