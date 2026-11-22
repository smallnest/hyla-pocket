package io.rpcx.hylapocket.core

import java.util.Base64

/**
 * The parts of a BeaconTower pair code the app shows, read as hyla's `ParsePairCode` reads them:
 * a version byte, the host's public key, the pre-shared key, the relay's address and its token,
 * all in base64url without padding.
 */
object BeaconCode {
    private const val PREFIX = "bt1_"

    /** The relay's address: `relay.example:7474`, or a `ws://`, `wss://`, `tls://` or `tcp://` URL; null when unreadable. */
    fun relay(code: String): String? {
        val raw = runCatching { Base64.getUrlDecoder().decode(code.trim().removePrefix(PREFIX)) }.getOrNull() ?: return null
        if (raw.size < 67) return null
        val len = ((raw[65].toInt() and 0xff) shl 8) or (raw[66].toInt() and 0xff)
        if (len == 0 || 67 + len > raw.size) return null
        return String(raw, 67, len, Charsets.UTF_8).trim().ifEmpty { null }
    }

    /** The relay as the host shows it: the port dropped for a bare address, the path dropped for a URL. */
    fun relayLabel(code: String): String? {
        val relay = relay(code) ?: return null
        val scheme = relay.substringBefore("://", "").lowercase()
        if (scheme.isEmpty()) return relay.substringBefore(':')
        val rest = relay.substringAfter("://")
        return when (scheme) {
            "ws", "wss", "tls", "tcp" -> "$scheme://" + rest.substringBefore('/')
            else -> rest.substringBefore('/')
        }
    }
}
