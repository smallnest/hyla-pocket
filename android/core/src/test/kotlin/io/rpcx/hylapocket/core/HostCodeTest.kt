package io.rpcx.hylapocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostCodeTest {
    private val pair = "tcAQB0Gb8yZ3QvJm2Kp9Wx_" + "Lr4TdNcEsFaHb3Vm6Uy7"
    private val code = HostCode("tailcat://$pair", "studio")

    @Test
    fun aCodeRoundTrips() {
        assertEquals(code, HostCodec.decode(HostCodec.encode(code)))
    }

    @Test
    fun theSchemeIsCaseInsensitiveAndSurroundingSpaceIgnored() {
        val text = HostCodec.encode(code)
        assertEquals(code, HostCodec.decode("\n  $text  \n"))
        assertEquals(code, HostCodec.decode("HYLA://PAIR?" + text.substringAfter('?')))
    }

    @Test
    fun aPairCodeIsTakenWithOrWithoutItsScheme() {
        assertEquals(code, HostCodec.decode(HostCodec.encode(HostCode(pair, "studio"))))
        assertEquals("tailcat://$pair", HostCodec.pairOf(pair))
        assertEquals("tailcat://$pair", HostCodec.pairOf("tailcat://$pair"))
        assertEquals("tailcat://$pair", HostCodec.pairOf(HostCodec.encode(code)))
    }

    @Test
    fun noNameMeansNoName() {
        assertNull(HostCodec.decode(HostCodec.encode(code.copy(label = null)))?.label)
    }

    @Test
    fun brokenOrForeignTextIsNoHost() {
        for (text in listOf(
            "",
            "hello",
            "192.0.2.10:7297",
            "https://example.com",
            "hyla://pair",
            "hyla://pair?",
            "hyla://pair?code=",
            "hyla://pair?code=abc",
            "hyla://pair?name=studio",
            "hyla://pair?code=t c12345678901234567890",
        )) {
            assertNull(text, HostCodec.decode(text))
        }
    }

    @Test
    fun pairOfTakesAPairCodeOrNothing() {
        assertNull(HostCodec.pairOf("studio.local:7297"))
        assertNull(HostCodec.pairOf("what is this"))
        assertNull(HostCodec.pairOf("https://example.com"))
    }
}
