package app.hylapocket.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamDecoderTest {
    @Test
    fun `bytes that are not the client's messages are the screen's output`() {
        val d = StreamDecoder(0uL)
        val bytes = "hello\r\n\u001b[1;32mgreen\u001b[0m".toByteArray()
        val feeds = d.feed(bytes)
        assertEquals(1, feeds.size)
        assertEquals(String(bytes), String((feeds.single() as TerminalFeed.Output).bytes))
    }

    @Test
    fun `a message split across two reads is held until it is whole`() {
        val d = StreamDecoder(0uL)
        // The nonce is the decoder's own; a message of another one is a program's, and is kept.
        val apc = "\u001b_rex1;0;{\"type\":\"resize\"}\u001b\\".toByteArray()
        val first = d.feed(apc.copyOfRange(0, 10))
        val second = d.feed(apc.copyOfRange(10, apc.size))
        val output = (first + second).filterIsInstance<TerminalFeed.Output>().map { String(it.bytes) }.joinToString("")
        assertEquals("", output)
    }

    @Test
    fun `a message of another nonce is a program's, and is kept`() {
        val d = StreamDecoder(7uL)
        val bytes = "\u001b_rex1;abc;{}\\".toByteArray()
        assertEquals(String(bytes), String((d.feed(bytes).single() as TerminalFeed.Output).bytes))
    }
}
