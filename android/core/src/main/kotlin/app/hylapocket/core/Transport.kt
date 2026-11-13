package app.hylapocket.core

import java.net.Socket

/**
 * Where a host's connections go. The app's client speaks HTTP, WebSocket and TCP to hylad through
 * one of these; a plain TCP transport reaches an address, and the app's reaches the host by its
 * pair code over tailcat. Here the core names only what it needs of a socket, so that its tests
 * run over a loopback server.
 */
interface Transport {
    /**
     * A socket to [port] on the host whose pair code is [pair] ("tailcat://tc…" or "tc…"), not
     * yet connected: the caller connects it, as [java.net.Socket.connect] does, and closes it.
     */
    fun socket(pair: String, port: Int): Socket
}
