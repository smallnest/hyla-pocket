package io.rpcx.hylapocket.tailcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException
import java.util.Base64

class TailcatSocketErrorTest {
    private fun sampleBeaconPair(): String {
        val relay = "relay.example:7474"
        val raw = byteArrayOf(1) + ByteArray(64) { 7 } +
            byteArrayOf((relay.length shr 8).toByte(), relay.length.toByte()) + relay.toByteArray() +
            byteArrayOf(0, 0)
        return "beacontower://bt1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    @Test
    fun `connectErrorMessage omits tailcat prefix for beacontower hosts`() {
        val beacon = sampleBeaconPair()
        assertEquals(
            "beacontower: relay: host not found",
            TailcatSocket.connectErrorMessage(beacon, 7297, "beacontower: relay: host not found"),
        )
        assertEquals(
            "beacontower: cannot reach the host: connection refused",
            TailcatSocket.connectErrorMessage(beacon, 7297, "connection refused"),
        )
        assertEquals(
            "beacontower: cannot reach the host",
            TailcatSocket.connectErrorMessage(beacon, 7297, ""),
        )
        assertEquals(
            "beacontower: cannot reach the host: bad code",
            TailcatSocket.connectErrorMessage("beacontower://bt1_short", 7297, "bad code"),
        )
    }

    @Test
    fun `connectErrorMessage keeps tailcat prefix for tailcat hosts`() {
        val tc = "tailcat://tcAQB0Gb8yZ3QvJm2Kp9Wx_Lr4TdNcEsFaHb3Vm6Uy7"
        val msg = TailcatSocket.connectErrorMessage(tc, 7297, "finding the relay")
        assertTrue(msg.startsWith("tailcat: cannot reach the host on port 7297: "))
    }

    @Test
    fun `closed beacon socket reports beacontower instead of tailcat`() {
        val beacon = sampleBeaconPair()
        val socket = TailcatSocket(TailcatSocketFactory(beacon), beacon)
        socket.close()
        try {
            socket.getInputStream()
            throw AssertionError("expected SocketException on closed socket")
        } catch (expected: SocketException) {
            assertTrue(expected.message.orEmpty().startsWith("beacontower:"))
            assertFalse(expected.message.orEmpty().contains("tailcat"))
        }
    }
}
