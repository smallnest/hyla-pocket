package io.rpcx.hylapocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class BeaconCodeTest {
    private fun code(relay: String, token: String = "tok"): String {
        val raw = byteArrayOf(1) + ByteArray(64) { 7 } +
            byteArrayOf((relay.length shr 8).toByte(), relay.length.toByte()) + relay.toByteArray() +
            byteArrayOf((token.length shr 8).toByte(), token.length.toByte()) + token.toByteArray()
        return "bt1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    private val relay = "relay.example:7474"
    private val pair = code(relay)

    @Test
    fun aBeaconCodeIsARelayedHostAddressedByItsCode() {
        for (text in listOf(pair, "beacontower://$pair")) {
            val e = parseHostAddress(text)!!
            assertTrue(e.beacon)
            assertTrue(e.relayed)
            assertEquals(pair, e.pairCode)
            assertEquals("beacontower://$pair", e.host)
            assertEquals(pair, e.addressText())
            assertEquals("ws://127.0.0.1:7297/pocket/api/control", e.ws("/pocket/api/control"))
            assertEquals(relay, e.relayAddress)
        }
        assertEquals(parseHostAddress(pair), parseHostAddress(HostCodec.encode(HostCode(pair, "studio"))))
        assertEquals("", parseHostEntry(pair)?.token)
    }

    @Test
    fun theCodeNamesItsRelayAsTheHostShowsIt() {
        assertEquals(relay, BeaconCode.relay(pair))
        assertEquals("relay.example", BeaconCode.relayLabel(code("relay.example:7474", token = "")))
        assertEquals("wss://gt.example", BeaconCode.relayLabel(code("wss://gt.example/ws")))
        assertNull(BeaconCode.relay("bt1_!!!"))
        assertNull(BeaconCode.relay("tc" + pair.substring(3)))
    }

    @Test
    fun aBeaconCodeIsWhatNoOtherCodeIs() {
        val tailcat = "tc" + "aB3dE6gH9jK2mN5pQ8sT1vW4yZ7bC0fI3lO6rU9x"
        assertTrue(HostCodec.pairOf(tailcat)!!.startsWith("tailcat://"))
        assertTrue(HostCodec.pairOf(pair)!!.startsWith("beacontower://"))
        assertNull(HostCodec.pairOf("bt1_"))
        assertNull(HostCodec.pairOf("bt1_has space"))
    }
}
