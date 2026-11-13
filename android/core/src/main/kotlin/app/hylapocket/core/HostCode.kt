package app.hylapocket.core

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A host as its QR code carries it: the pair code of hylad's tailcat listener, which is both
 * where the host is and the secret that admits a client, and an optional name.
 */
data class HostCode(
    val pair: String,
    val label: String? = null,
)

/**
 * The QR code on the hylad machine: `hyla://pair?code=…&name=…`, which `hyla hosts pocket qr`
 * prints. The connection runs through tailcat, hylad's relay (WireGuard end to end, a public DERP
 * relay only to find the peer), so there is no address to reach and no second password.
 */
object HostCodec {
    private const val PREFIX = "hyla://pair?"
    const val SCHEME = "tailcat://"

    /**
     * The pair code as a host holds it: with its scheme, "tailcat://tc…", as it is also the
     * address the connection goes through.
     */
    fun withScheme(pair: String): String =
        if (pair.trim().startsWith(SCHEME, ignoreCase = true)) pair.trim() else SCHEME + bare(pair)

    /** The pair code without its scheme: "tc…", as `hyla hosts tailcat code` prints it and tailcat makes it. */
    fun bare(pair: String): String = pair.trim().let { if (it.startsWith(SCHEME, ignoreCase = true)) it.substring(SCHEME.length) else it }

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
        if (!isPairCode(pair)) return null
        return HostCode(SCHEME + pair, params["name"]?.trim()?.takeIf { it.isNotEmpty() })
    }

    /**
     * The pair code in the text, with its scheme, or null. A scanned code may be anything; a pair
     * code is what tailcat makes of a host's conn info, "tc" and base64url, and long.
     */
    fun pairOf(text: String): String? {
        decode(text)?.let { return it.pair }
        val pair = bare(text)
        return if (isPairCode(pair)) SCHEME + pair else null
    }

    private fun isPairCode(s: String): Boolean =
        s.length >= 16 && s.startsWith("tc") && s.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
