package io.rpcx.hylapocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAddressTest {
    private val pair = HostCodec.withScheme("tcQ4Lq8sG8GrCx9Tu9Px_Gq2IsWbZjWmMx1Qs3Vk1_2")
    private val bare = HostCodec.bare(pair)

    @Test
    fun aPairCodeIsATailcatHostWithoutItsScheme() {
        val e = parseHostAddress(bare)!!
        assertTrue(e.tailcat)
        assertEquals(bare, e.pairCode)
        assertEquals(pair, e.host)
        assertEquals(bare, e.addressText())
        assertEquals(parseHostAddress(pair), e)
        assertEquals(e, parseHostAddress(HostCodec.encode(HostCode(bare, "studio"))))
    }

    @Test
    fun aTailcatHostsControlUrlNamesLoopbackAndTheHostsPort() {
        val e = parseHostAddress(bare)!!
        assertEquals("ws://127.0.0.1:7297/api/control", e.ws("/api/control"))
    }

    @Test
    fun aNetworkAddressIsNotATailcatHost() {
        val e = parseHostAddress("studio:7297")!!
        assertEquals(false, e.tailcat)
        assertNull(e.pairCode)
        assertEquals("studio:7297", e.addressText())
        assertEquals("ws://studio:7297/api/control", e.ws("/api/control"))
    }

    @Test
    fun neitherAnAddressNorAPairCodeIsNoHost() {
        for (text in listOf("", "hyla://pair?name=x", "http://", "tailcat://")) {
            assertNull(text, parseHostAddress(text))
        }
    }

    @Test
    fun aScannedCodeCarriesTheAddressAndThePassword() {
        val entry = parseHostEntry("tcp://192.168.1.5:7297?token=abc123")!!
        assertEquals("192.168.1.5", entry.endpoint.host)
        assertEquals(7297, entry.endpoint.port)
        assertTrue(entry.endpoint.plaintext)
        assertEquals("abc123", entry.token)
        // A typed address, or a pair code, carries no password of its own.
        assertEquals("", parseHostEntry("studio:7297")?.token)
        assertEquals("", parseHostEntry(pair)?.token)
        assertNull(parseHostEntry("tcp://?token=abc"))
    }

    /** A status line names a tailcat host by its code's beginning, and leaves the tunnel's port out. */
    @Test
    fun aTailcatHostsLabelIsItsCodeWithoutThePort() {
        val e = parseHostAddress(bare)!!
        assertEquals("tailcat://" + bare.take(12) + "…", e.labelText())
        assertEquals("studio:7297", parseHostAddress("studio:7297")!!.labelText())
        // The shortest code a pair code may be is still cut to the label's span.
        val shortest = parseHostAddress("tc1234567890abcd")!!
        assertEquals("tailcat://tc1234567890…", shortest.labelText())
    }

    /** The QR code's line is a hyla://pair URL, whose query names the code: it is no token. */
    @Test
    fun aScannedHostCodeIsTheAddressAndItsOwnPassword() {
        val url = HostCodec.encode(HostCode(bare, "studio"))
        val entry = parseHostEntry(url)!!
        assertTrue(entry.endpoint.tailcat)
        assertEquals(bare, entry.endpoint.pairCode)
        assertEquals("", entry.token)
        // And a code with no name is read the same.
        assertEquals(entry.endpoint, parseHostEntry("hyla://pair?code=$bare")?.endpoint)
    }
}
