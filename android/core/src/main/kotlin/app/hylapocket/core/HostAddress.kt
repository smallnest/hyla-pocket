package app.hylapocket.core

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
