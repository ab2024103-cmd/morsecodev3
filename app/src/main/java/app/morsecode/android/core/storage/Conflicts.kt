package app.morsecode.android.core.storage

/**
 * §9.5 CONFLICT POLICY, evaluated on the RECEIVER, per FULL RELATIVE PATH — so
 * `AlbumA/img1.jpg` and `AlbumB/img1.jpg` never collide — and before the first
 * byte hits the destination.
 *
 * Detection order is name → size → sha256, with the hash computed only when it
 * is actually needed (and skipped on the lowest tier with a log line).
 *
 * This object is pure: it decides, it does not write. That is what makes all
 * four policies and "apply to all" testable without a filesystem.
 */
object Conflicts {

    /** The four choices in the dialog; [RENAME] is the §6.13 default. */
    enum class Policy { ASK, OVERWRITE, SKIP, RENAME }

    /** What the receiver should do with one incoming file. */
    sealed class Decision {
        /** Write to [name], replacing whatever is there. */
        data class Overwrite(val name: String) : Decision()

        /** Do not write; the sender marks the item SKIPPED, never FAILED (§9.6). */
        data class Skip(val reason: String) : Decision()

        /** Write under a new name: photo.jpg → photo (1).jpg → photo (2).jpg. */
        data class KeepBoth(val name: String) : Decision()

        /** Nothing is there; write it as it is. */
        data class Write(val name: String) : Decision()

        /** Raise the dialog. Other items in the batch keep moving (§9.5). */
        data class Ask(val name: String) : Decision()
    }

    /**
     * An identical file (same size AND same hash) already at the destination is
     * "already present" regardless of policy: there is nothing to decide, and
     * the sender is told to SKIP (§9.4).
     */
    fun isAlreadyPresent(
        incomingSize: Long,
        existingSize: Long?,
        incomingSha: String?,
        existingSha: String?,
    ): Boolean {
        if (existingSize == null) return false
        if (incomingSize != existingSize) return false
        // Same name and same size with no hash on either side is NOT proof;
        // §9.4 wants sha equality, so without it the file is a conflict, not a
        // duplicate.
        if (incomingSha == null || existingSha == null) return false
        return incomingSha.equals(existingSha, ignoreCase = true)
    }

    /**
     * @param exists whether [relativePath]'s name is taken at the destination.
     * @param takenNames every name already present in the destination folder,
     *   used to pick the first free "(n)" suffix.
     */
    fun decide(
        relativePath: String,
        exists: Boolean,
        policy: Policy,
        takenNames: Set<String> = emptySet(),
        incomingSize: Long = -1,
        existingSize: Long? = null,
        incomingSha: String? = null,
        existingSha: String? = null,
    ): Decision {
        val name = fileNameOf(relativePath)
        if (!exists) return Decision.Write(name)

        if (isAlreadyPresent(incomingSize, existingSize, incomingSha, existingSha)) {
            return Decision.Skip("identical file already present on receiver")
        }

        return when (policy) {
            Policy.OVERWRITE -> Decision.Overwrite(name)
            Policy.SKIP -> Decision.Skip("a file with this name already exists")
            Policy.RENAME -> Decision.KeepBoth(nextFreeName(name, takenNames))
            Policy.ASK -> Decision.Ask(name)
        }
    }

    /**
     * "Keep both" naming: photo.jpg → photo (1).jpg → photo (2).jpg, and the
     * extension is preserved even when the stem itself contains dots.
     */
    fun nextFreeName(name: String, takenNames: Set<String>): String {
        if (name !in takenNames) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$stem ($index)$extension"
            if (candidate !in takenNames) return candidate
            index++
        }
    }

    fun fileNameOf(relativePath: String): String =
        relativePath.substringAfterLast('/').ifEmpty { relativePath }

    /**
     * "Apply to all remaining conflicts in this batch" (§9.5). The remembered
     * choice is per batch and never leaks into the next one — a decision made
     * about holiday photos must not silently overwrite next week's documents.
     */
    class BatchPolicy(default: Policy) {

        private val appliedToAll = HashMap<String, Policy>()

        /** §6.13's setting can change mid-session; the next batch honours it. */
        private var default: Policy = default

        fun setDefault(policy: Policy) {
            default = policy
        }

        fun defaultPolicy(): Policy = default

        fun policyFor(batchId: String): Policy = appliedToAll[batchId] ?: default

        fun applyToAll(batchId: String, policy: Policy) {
            require(policy != Policy.ASK) { "\"apply to all\" needs a concrete choice" }
            appliedToAll[batchId] = policy
        }

        fun forget(batchId: String) {
            appliedToAll.remove(batchId)
        }

        fun hasChoiceFor(batchId: String): Boolean = appliedToAll.containsKey(batchId)
    }
}
