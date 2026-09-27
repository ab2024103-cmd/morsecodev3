package app.morsecode.android.core.network

import org.json.JSONObject

/**
 * §11.3: "Control messages use the same JSON vocabulary as small byte
 * payloads", and "a small metadata payload (name, size, sha256, relativePath,
 * mime) precedes every file so §9.4's verification is possible".
 *
 * Both are built here, on top of [Protocol], so the Nearby path and the LAN
 * path speak one vocabulary and a change to META cannot land on only one of
 * them.
 */
object NearbyPayloads {

    /** The metadata that precedes a STREAM payload. It IS a META message. */
    fun metadata(
        fileId: String,
        name: String,
        size: Long,
        sha256: String?,
        relativePath: String?,
        mime: String?,
    ): String = Protocol.meta(
        fileId = fileId,
        name = name,
        relativePath = relativePath,
        size = size,
        mime = mime,
        sha256 = sha256,
    ).encode()

    /**
     * Parses a small BYTES payload. Returns null when it is not one of ours,
     * because a foreign endpoint can send anything and a crash is not an
     * acceptable answer to it.
     */
    fun parse(bytes: ByteArray): Protocol.Message? = try {
        Protocol.parse(String(bytes, Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    /** True when this payload is the metadata that precedes a file. */
    fun isMetadata(message: Protocol.Message): Boolean = message.type == Protocol.META

    fun describe(message: Protocol.Message): String {
        val json = JSONObject(message.body.toString())
        return "${json.optString("name")} (${json.optLong("size")} B)"
    }
}
