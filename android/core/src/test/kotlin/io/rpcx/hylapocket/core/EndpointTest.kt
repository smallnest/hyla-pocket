package io.rpcx.hylapocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointTest {
    @Test
    fun `an address without a port uses the default`() {
        assertEquals(Endpoint("192.0.2.10", 7297, true), Endpoint.parse("192.0.2.10"))
    }

    @Test
    fun `a port in the address wins`() {
        assertEquals(Endpoint("studio", 7300, true), Endpoint.parse("studio:7300"))
    }

    @Test
    fun `a scheme sets TLS and leaves the host alone`() {
        assertEquals(Endpoint("box.example", 7297, false), Endpoint.parse("https://box.example"))
        assertEquals("wss://box.example:7297/api/control", Endpoint.parse("https://box.example")!!.ws("/api/control"))
    }

    @Test
    fun `an IPv6 address keeps its brackets`() {
        val e = Endpoint.parse("[fd7a::1]:7297")!!
        assertEquals(Endpoint("[fd7a::1]", 7297, true), e)
        assertEquals("ws://[fd7a::1]:7297/api/data", e.ws("/api/data"))
    }

    @Test
    fun `blank and broken addresses are not addresses`() {
        for (text in listOf("", "  ", "http://", "host:0", "host:70000")) {
            assertNull(text, Endpoint.parse(text))
        }
    }

    @Test
    fun `a secure port makes a bare address TLS`() {
        assertFalse(Endpoint.parse("studio:443")!!.plaintext)
        assertTrue(Endpoint.parse("studio:7297")!!.plaintext)
    }
}
