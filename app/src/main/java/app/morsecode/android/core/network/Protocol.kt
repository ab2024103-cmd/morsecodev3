package app.morsecode.android.core.network

import app.morsecode.android.core.util.Ids
import org.json.JSONObject

/**
 * §11.2 CONTROL PROTOCOL: one TCP connection carrying newline-terminated UTF-8
 * JSON — HELLO → ACCEPT/REJECT → per-file META → ACK, plus PAUSE_REQ,
 * RESUME_REQ, CANCEL, BYE and PING/PONG keepalive.
 *
 * Encoding and parsing live here alone, so both ends of every message are
 * written once and the §11.6 version check happens in one place.
 */
object Protocol {

    const val HELLO = "HELLO"
    const val ACCEPT = "ACCEPT"
    const val REJECT = "REJECT"
    const val META = "META"
    const val ACK = "ACK"
    const val PAUSE_REQ = "PAUSE_REQ"
    const val RESUME_REQ = "RESUME_REQ"
    const val CANCEL = "CANCEL"
    const val BYE = "BYE"
    const val PING = "PING"
    const val PONG = "PONG"

    /** ACK statuses for a META (§9.4). */
    const val STATUS_READY = "ready"
    const val STATUS_ALREADY_PRESENT = "already_present"
    const val STATUS_REFUSED = "refused"

    class ProtocolException(message: String) : IllegalArgumentException(message)

    data class Message(val type: String, val body: JSONObject = JSONObject()) {

        fun string(key: String): String? =
            if (body.has(key) && !body.isNull(key)) body.optString(key) else null

        fun long(key: String, fallback: Long = 0): Long = body.optLong(key, fallback)

        fun int(key: String, fallback: Int = 0): Int = body.optInt(key, fallback)

        fun bool(key: String, fallback: Boolean = false): Boolean = body.optBoolean(key, fallback)

        /** One line of UTF-8 JSON; the newline is added by the channel. */
        fun encode(): String {
            val json = JSONObject(body.toString())
            json.put(KEY_TYPE, type)
            return json.toString()
        }
    }

    fun message(type: String, build: JSONObject.() -> Unit = {}): Message {
        val body = JSONObject()
        body.build()
        return Message(type, body)
    }

    fun parse(line: String): Message {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) throw ProtocolException("empty control line")
        val json = try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            throw ProtocolException("control line is not JSON")
        }
        val type = json.optString(KEY_TYPE)
        if (type.isEmpty()) throw ProtocolException("control line has no type")
        json.remove(KEY_TYPE)
        return Message(type, json)
    }

    // ----- Message builders -------------------------------------------------

    fun hello(deviceId: String, name: String, broadcasting: Boolean = false): Message =
        message(HELLO) {
            put("deviceId", deviceId)
            put("name", name)
            put("appId", Ids.APPLICATION_ID)
            put("protocolVersion", Ids.PROTOCOL_VERSION)
            put("broadcasting", broadcasting)
        }

    fun accept(deviceId: String, name: String): Message = message(ACCEPT) {
        put("deviceId", deviceId)
        put("name", name)
        put("protocolVersion", Ids.PROTOCOL_VERSION)
    }

    fun reject(reason: String): Message = message(REJECT) { put("reason", reason) }

    /** §11.2 per-file META, field for field. */
    fun meta(
        fileId: String,
        name: String,
        relativePath: String?,
        size: Long,
        mime: String?,
        sha256: String?,
        resumeOffset: Long = 0,
    ): Message = message(META) {
        put("fileId", fileId)
        put("name", name)
        put("relativePath", relativePath ?: JSONObject.NULL)
        put("size", size)
        put("mime", mime ?: JSONObject.NULL)
        put("sha256", sha256 ?: JSONObject.NULL)
        put("resumeOffset", resumeOffset)
    }

    /**
     * The receiver's answer to a META. `resumeOffset` is the length of its
     * `.part` file, which the sender seeks to (§11.2 Resume).
     */
    fun ack(
        fileId: String,
        status: String,
        resumeOffset: Long = 0,
        reason: String? = null,
    ): Message = message(ACK) {
        put("fileId", fileId)
        put("status", status)
        put("resumeOffset", resumeOffset)
        put("reason", reason ?: JSONObject.NULL)
    }

    fun pauseRequest(fileId: String): Message = message(PAUSE_REQ) { put("fileId", fileId) }

    fun resumeRequest(fileId: String): Message = message(RESUME_REQ) { put("fileId", fileId) }

    fun cancel(fileId: String): Message = message(CANCEL) { put("fileId", fileId) }

    fun bye(reason: String): Message = message(BYE) { put("reason", reason) }

    fun ping(): Message = message(PING)

    fun pong(): Message = message(PONG)

    /** The header line that opens a DATA connection (§11.2). */
    fun dataHeader(fileId: String, name: String, size: Long, offset: Long): String =
        message("DATA") {
            put("fileId", fileId)
            put("name", name)
            put("size", size)
            put("offset", offset)
        }.encode()

    /** The single status line a receiver writes back when a file finishes. */
    fun dataStatus(ok: Boolean, error: String? = null): String =
        message("DATA_STATUS") {
            put("ok", ok)
            put("error", error ?: JSONObject.NULL)
        }.encode()

    // ----- §11.6 protocol versioning ---------------------------------------

    /**
     * Returns null when the peer is compatible, or the message to SHOW when it
     * is not. §11.6: a mismatch must read as "This phone runs an older version
     * of Morsecode — update to transfer", never as a framing error.
     *
     * A peer that announces a NEWER protocol gets the mirror-image sentence
     * about this phone, because telling the user their up-to-date phone is out
     * of date would send them looking for an update that does not exist.
     */
    fun versionProblem(theirVersion: Int): String? = when {
        theirVersion == Ids.PROTOCOL_VERSION -> null
        theirVersion < Ids.PROTOCOL_VERSION -> OLDER_PEER
        else -> OLDER_SELF
    }

    /** A HELLO from something that is not this product is refused politely. */
    fun isMorsecode(message: Message): Boolean =
        message.string("appId") == Ids.APPLICATION_ID

    const val OLDER_PEER = "This phone runs an older version of Morsecode — update to transfer"
    const val OLDER_SELF = "The other phone runs a newer version of Morsecode — update to transfer"

    private const val KEY_TYPE = "type"
}
