package io.rpcx.hylapocket.ui

import android.view.KeyEvent

/**
 * The Android key a key row's press names, or 0 for a name no key of this app's has. The names are
 * the ones the rows are built with, and the keys are what the terminal's encoder turns into the
 * bytes a program reads: the app writes those, since hylad has no method for a key of its own.
 */
internal fun androidKeyCode(name: String): Int = when (name) {
    "escape" -> KeyEvent.KEYCODE_ESCAPE
    "tab" -> KeyEvent.KEYCODE_TAB
    "enter", "return" -> KeyEvent.KEYCODE_ENTER
    "backspace" -> KeyEvent.KEYCODE_DEL
    "delete" -> KeyEvent.KEYCODE_FORWARD_DEL
    "insert" -> KeyEvent.KEYCODE_INSERT
    "space" -> KeyEvent.KEYCODE_SPACE
    "up" -> KeyEvent.KEYCODE_DPAD_UP
    "down" -> KeyEvent.KEYCODE_DPAD_DOWN
    "left" -> KeyEvent.KEYCODE_DPAD_LEFT
    "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
    "home" -> KeyEvent.KEYCODE_MOVE_HOME
    "end" -> KeyEvent.KEYCODE_MOVE_END
    "page_up" -> KeyEvent.KEYCODE_PAGE_UP
    "page_down" -> KeyEvent.KEYCODE_PAGE_DOWN
    "minus" -> KeyEvent.KEYCODE_MINUS
    "equal" -> KeyEvent.KEYCODE_EQUALS
    "bracket_left" -> KeyEvent.KEYCODE_LEFT_BRACKET
    "bracket_right" -> KeyEvent.KEYCODE_RIGHT_BRACKET
    "backslash" -> KeyEvent.KEYCODE_BACKSLASH
    "semicolon" -> KeyEvent.KEYCODE_SEMICOLON
    "quote" -> KeyEvent.KEYCODE_APOSTROPHE
    "comma" -> KeyEvent.KEYCODE_COMMA
    "period" -> KeyEvent.KEYCODE_PERIOD
    "slash" -> KeyEvent.KEYCODE_SLASH
    "backquote" -> KeyEvent.KEYCODE_GRAVE
    else -> when {
        name.length == 5 && name.startsWith("key_") -> letterKey(name[4])
        name.length == 7 && name.startsWith("digit_") -> digitKey(name[6])
        else -> 0
    }
}

private fun letterKey(c: Char): Int = when (c) {
    in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
    else -> 0
}

private fun digitKey(c: Char): Int = when (c) {
    in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
    else -> 0
}

/** The modifiers a press names, as android.view.KeyEvent carries them. */
internal fun metaState(mods: String): Int {
    var state = 0
    for (mod in mods.split('+')) {
        state = state or when (mod) {
            "shift" -> KeyEvent.META_SHIFT_ON
            "alt" -> KeyEvent.META_ALT_ON
            "ctrl" -> KeyEvent.META_CTRL_ON
            else -> 0
        }
    }
    return state
}
