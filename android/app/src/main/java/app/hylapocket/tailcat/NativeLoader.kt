package app.hylapocket.tailcat

/** Loads libtailcat.so once, before any [Native] call. */
internal object NativeLoader {
    init {
        System.loadLibrary("tailcat")
    }

    fun ensureLoaded() = Unit
}
