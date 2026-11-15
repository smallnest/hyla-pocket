package io.rpcx.hylapocket.core

/**
 * The endpoint a host's address field takes, as a person types or scans it: a network address
 * (`studio:7297`), or a tailcat pair code with or without its scheme or a whole `hyla://pair?`
 * code, which is hylad's tailcat listener. Null when it is neither.
 */
fun parseHostAddress(text: String): Endpoint? {
    val pair = HostCodec.pairOf(text) ?: return Endpoint.parse(text)
    return Endpoint(pair)
}

/** [endpoint] as its address field shows it: less the scheme when it is a tailcat pair code. */
fun Endpoint.addressText(): String = if (tailcat) pairCode ?: host else "${hostName()}:$port"

/**
 * [endpoint] as a status line names it. A tailcat host is reached by its code, not by an address, so
 * its port - the tunnel's, the same for every host - is left out, and the code is cut to what tells
 * two hosts apart: the whole of it is longer than the line.
 */
fun Endpoint.labelText(): String =
    if (tailcat) HostCodec.SCHEME + shortCode() else "${hostName()}:$port"

/** The pair code's beginning, as a label shows it, or the host when it is no pair code. */
fun Endpoint.shortCode(): String {
    val code = pairCode ?: return hostName()
    return if (code.length <= SHORT_CODE + 1) code else code.take(SHORT_CODE) + "…"
}

private const val SHORT_CODE = 12
