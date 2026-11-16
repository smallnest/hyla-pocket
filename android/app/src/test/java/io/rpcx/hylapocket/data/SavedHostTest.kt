package io.rpcx.hylapocket.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A saved host is the host it reaches: an edit that changes the way there is another host. */
class SavedHostTest {

    private val code = "tailcat://tcpGFwWCDdiA-GN6bI0aFhmzXYFFwisuhhByT_H4T5bsHC2tuMcWFrWCCpWenRnB8XUJdNDI-TZC9ZOR8mn1X1NUjg2HutwBcfXmFxWCB9YGf8qHQR7WKkvUw4OIgvK242WyxGzL4lgwKbVhQOF2FygaFhToGjYWhpdi5ycGN4LmlvYXMZDZZhZBkg-w"
    private val other = "tailcat://tcpGFwWCA7QokVXnmVmnKsDE7WzxZZxw2A__yw_skbnZNF6TMcR2FrWCBiTdCp-KEZC1504NYqiYBxMtlDRFk0bWHWK4eJcYAXVmFxWCBkd_pb0eWwEp6wyuhLHpSf4lbHs-tJXefkcgirQZsw32FygaFhToGjYWhpdi5ycGN4LmlvYXMZDZZhZBkg-w"

    private fun host(id: String = "h1", address: String = code, token: String = "", label: String = "studio") =
        SavedHost(id = id, label = label, host = address, token = token)

    // The host a connection opened is live, and so is the same host renamed: a name is not an
    // address.
    @Test
    fun sameHostIsTheSameTarget() {
        assertTrue(sameTarget(host(), host()))
        assertTrue(sameTarget(host(), host(label = "laptop")))
        assertTrue(sameTarget(host(address = "studio:7297", token = "k"), host(address = "studio:7297", token = "k")))
    }

    // An edit that keeps the ID and changes the pair code reaches another host, which the running
    // connection does not know: it is no longer live.
    @Test
    fun anotherCodeIsNotTheSameTarget() {
        assertFalse(sameTarget(host(), host(address = other)))
        assertFalse(sameTarget(host(address = "studio:7297"), host(address = "studio:7298")))
        assertFalse(sameTarget(host(address = "studio:7297", token = "k"), host(address = "studio:7297", token = "j")))
    }

    // Two hosts, or none, are no target of a connection.
    @Test
    fun anotherHostIsNoTarget() {
        assertFalse(sameTarget(host(id = "h1"), host(id = "h2")))
        assertFalse(sameTarget(null, host()))
        assertFalse(sameTarget(host(), null))
        assertFalse(sameTarget(null, null))
    }
}
