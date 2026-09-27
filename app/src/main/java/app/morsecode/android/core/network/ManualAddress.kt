package app.morsecode.android.core.network

import app.morsecode.android.core.util.Ids

/**
 * §11.5 MANUAL PAIRING — the ONE manual path in the product, behind the
 * [Manual IP] button on the discovery screen (§6.3).
 *
 * Accepts `host`, `host:port` or a pasted `morsecode://host:port`, defaults to
 * port 33456, validates BEFORE dialling, and produces the exact failure
 * sentence §11.5 asks for.
 *
 * There is no QR scanner and no QR generator anywhere in the product, and this
 * file is not a place to add one.
 */
object ManualAddress {

    data class Target(val host: String, val port: Int) {
        override fun toString(): String = "$host:$port"
    }

    sealed class Result {
        data class Ok(val target: Target) : Result()
        data class Invalid(val reason: String) : Result()
    }

    const val REASON_EMPTY = "Enter an address like 192.168.1.42"
    const val REASON_HOST = "That does not look like an address"
    const val REASON_PORT = "Port must be between 1 and 65535"

    /** "Can't reach <host>:<port>" (§11.5), used when the dial fails. */
    fun unreachable(target: Target): String = "Can't reach $target"

    fun parse(input: String): Result {
        var text = input.trim()
        if (text.isEmpty()) return Result.Invalid(REASON_EMPTY)

        // A pasted deep link is the same thing with a scheme on the front.
        val scheme = "${Ids.LINK_SCHEME}://"
        if (text.startsWith(scheme, ignoreCase = true)) {
            text = text.substring(scheme.length)
        }
        text = text.trim('/').trim()
        if (text.isEmpty()) return Result.Invalid(REASON_EMPTY)

        // IPv6 in brackets: [fe80::1]:33456
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close <= 1) return Result.Invalid(REASON_HOST)
            val host = text.substring(1, close)
            val rest = text.substring(close + 1)
            val port = if (rest.startsWith(":")) {
                parsePort(rest.substring(1)) ?: return Result.Invalid(REASON_PORT)
            } else {
                Ids.PORT_TCP
            }
            return Result.Ok(Target(host, port))
        }

        val parts = text.split(":")
        return when (parts.size) {
            1 -> {
                val host = parts[0]
                if (!isPlausibleHost(host)) Result.Invalid(REASON_HOST)
                else Result.Ok(Target(host, Ids.PORT_TCP))
            }
            2 -> {
                val host = parts[0]
                if (!isPlausibleHost(host)) return Result.Invalid(REASON_HOST)
                val port = parsePort(parts[1]) ?: return Result.Invalid(REASON_PORT)
                Result.Ok(Target(host, port))
            }
            else -> Result.Invalid(REASON_HOST)
        }
    }

    private fun parsePort(text: String): Int? {
        val port = text.trim().toIntOrNull() ?: return null
        return if (port in 1..65535) port else null
    }

    /**
     * A deliberately loose check: an IPv4 literal, or a hostname made of
     * letters, digits, dots and hyphens. It rejects obvious nonsense before a
     * socket is opened (§11.5 "validates before dialling") without trying to
     * out-guess DNS.
     */
    private fun isPlausibleHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (host.startsWith(".") || host.endsWith(".")) return false
        if (host.startsWith("-") || host.endsWith("-")) return false
        return host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
    }
}
