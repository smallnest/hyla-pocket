package io.rpcx.hylapocket.data

/**
 * The appearance the app draws in: the phone's own, or what the user chose in Settings. The
 * terminal's colours follow it too, since they come from the same palette.
 */
enum class ThemeMode(val value: String) {
    System("system"),
    Light("light"),
    Dark("dark"),
    ;

    companion object {
        /** The mode a stored value names; one it does not know, or none, is the system's. */
        fun parse(value: String?): ThemeMode = entries.firstOrNull { it.value == value } ?: System
    }
}
