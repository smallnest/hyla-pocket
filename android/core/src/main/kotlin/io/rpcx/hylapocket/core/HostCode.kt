package io.rpcx.hylapocket.core

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A host as its QR code carries it: the pair code of hylad's listener, behind tailcat or
 * BeaconTower, which is both where the host is and the secret that admits a client, and an
 * optional name.
 */
data class HostCode(
    val pair: String,
    val label: String? = null,
)

/**
 * The QR code on the hylad machine: `hyla://pair?code=…&name=…`, which `hyla hosts pocket qr`
 * prints. The connection runs through a relay, so there is no address to reach and no second
 * password: tailcat (WireGuard end to end) or BeaconTower, whose code names its own relay.
 */
object HostCodec {
    private const val PREFIX = "hyla://pair?"
    const val SCHEME = "tailcat://"
    const val BEACON_SCHEME = "beacontower://"

    /**
     * The pair code as a host holds it: with its scheme, "tailcat://tc…" or
     * "beacontower://bt1_…", as it is also the address the connection goes through.
     */
    fun withScheme(pair: String): String = when {
        pair.trim().startsWith(SCHEME, ignoreCase = true) -> pair.trim()
        isBeacon(pair.trim()) -> BEACON_SCHEME + bare(pair)
        else -> SCHEME + bare(pair)
    }

    /** The pair code without its scheme: "tc…" or "bt1_…", as the host's command prints it. */
    fun bare(pair: String): String = pair.trim().let {
        when {
            it.startsWith(SCHEME, ignoreCase = true) -> it.substring(SCHEME.length)
            it.startsWith(BEACON_SCHEME, ignoreCase = true) -> it.substring(BEACON_SCHEME.length)
            else -> it
        }
    }

    /**
     * Whether [s] is a BeaconTower pair code, "bt1_…": its version and kind, then base64url. The
     * rest must be long enough to hold a whole code, so that a truncated one is no code, as
     * hyla's parser, which decodes the rest, rejects it.
     */
    fun isBeacon(s: String): Boolean =
        BEACON.matchEntire(bare(s)) != null

    private val BEACON = Regex("bt1_[A-Za-z0-9_-]{93}[A-Za-z0-9_-]*")

    fun encode(code: HostCode): String {
        val parts = mutableListOf("code=" + URLEncoder.encode(bare(code.pair), "UTF-8"))
        code.label?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += "name=" + URLEncoder.encode(it, "UTF-8") }
        return PREFIX + parts.joinToString("&")
    }

    /** Reads a scanned code, or what a person pasted; null when it is no host code. */
    fun decode(text: String): HostCode? {
        val t = text.trim()
        if (!t.startsWith("hyla://pair", ignoreCase = true)) return null
        val query = t.substringAfter('?', "")
        if (query.isEmpty()) return null
        val params = query.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null else pair.substring(0, i) to runCatching { URLDecoder.decode(pair.substring(i + 1), "UTF-8") }.getOrNull()
        }.toMap()
        val pair = bare(params["code"] ?: return null)
        val scheme = when {
            isPairCode(pair) -> SCHEME
            isBeacon(pair) -> BEACON_SCHEME
            else -> return null
        }
        return HostCode(scheme + pair, params["name"]?.trim()?.takeIf { it.isNotEmpty() })
    }

    /**
     * The pair code in the text, with its scheme, or null. A scanned code may be anything; a pair
     * code is what a relay makes of a host's conn info: tailcat's "tc" and base64url, long, or
     * BeaconTower's "bt1_…", which starts with its version and kind.
     */
    fun pairOf(text: String): String? {
        decode(text)?.let { return it.pair }
        val pair = bare(text)
        return when {
            isPairCode(pair) -> SCHEME + pair
            isBeacon(pair) -> BEACON_SCHEME + pair
            else -> null
        }
    }

    private fun isPairCode(s: String): Boolean =
        s.length >= 16 && s.startsWith("tc") && s.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
