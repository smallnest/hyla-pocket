package io.rpcx.hylapocket.core

/**
 * The endpoint a host's address field takes, as a person types or scans it: a network address
 * (`studio:7297`), or a pair code with or without its scheme or a whole `hyla://pair?` code,
 * which is hylad's listener behind tailcat or BeaconTower. Null when it is neither.
 */
fun parseHostAddress(text: String): Endpoint? {
    val pair = HostCodec.pairOf(text) ?: return Endpoint.parse(text)
    // A pair code names no port but the listener's default one.
    return Endpoint(pair)
}

/** [endpoint] as its address field shows it: less the scheme when it is a pair code. */
fun Endpoint.addressText(): String = if (relayed) pairCode ?: host else "${hostName()}:$port"

/**
 * [endpoint] as a status line names it. A host behind a relay is reached by its code, not by an
 * address, so its port - the tunnel's, the same for every host - is left out, and the code is cut
 * to what tells two hosts apart: the whole of it is longer than the line.
 */
fun Endpoint.labelText(): String = when {
    tailcat -> HostCodec.SCHEME + shortCode()
    beacon -> HostCodec.BEACON_SCHEME + shortCode()
    else -> "${hostName()}:$port"
}

/** The pair code's beginning, as a label shows it, or the host when it is no pair code. */
fun Endpoint.shortCode(): String {
    val code = pairCode ?: return hostName()
    return if (code.length <= SHORT_CODE + 1) code else code.take(SHORT_CODE) + "…"
}

private const val SHORT_CODE = 12
