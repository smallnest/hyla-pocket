package io.rpcx.hylapocket.diagnostics

import android.util.Log

/**
 * What the pane's stream did, for `adb logcat -s HylaPocket`.
 *
 * The debug build keeps no journal on the phone: a report of the problem is the log's, and the log
 * of a debug build is where the app is run from.
 */
object Diagnostics {
    fun event(tag: String, message: String) {
        Log.i(tag, message)
    }
}
