package app.hylapocket.tailcat

/**
 * The JNI functions of libtailcat.so, by the names the JVM links to: native/tailcat in the Android
 * project makes them, bridge/bridge.go does the work. A stream is an int handle of one TCP
 * connection to a port of a host, made once per pair code's tailcat client, which closes with the
 * last stream that used it.
 *
 * Kotlin calls these on its own threads; [read] blocks until data, the stream's end, or [close]
 * from another thread.
 */
internal object Native {
    const val EOF = -1
    const val FAILED = -2

    /** The handle of a new stream, or -1. The thread that calls it blocks for up to [timeoutMs]. */
    external fun connect(code: String, port: Int, timeoutMs: Int): Int

    /** Bytes read into [b] from [off], [EOF] at the stream's end, [FAILED]. */
    external fun read(id: Int, b: ByteArray, off: Int, len: Int): Int

    /** Bytes written, or -1. */
    external fun write(id: Int, b: ByteArray, off: Int, len: Int): Int

    /** Ends the stream, which wakes a thread blocked in [read]. */
    external fun close(id: Int)

    /** Forgets the stream's handle; after [close] at once, else first closes it. */
    external fun release(id: Int)

    /** Why the last call failed, or "". A JNI exception explains it better; this is for logs. */
    external fun lastError(): String
}
