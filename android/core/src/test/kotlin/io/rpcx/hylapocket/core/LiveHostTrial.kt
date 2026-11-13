package io.rpcx.hylapocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Talks to the hylad HYLA_POCKET_HOST names, with HYLA_POCKET_TOKEN, when they are set: it
 * bootstraps, lists the sessions, opens one, and reads the first bytes of its pane's stream.
 */
class LiveHostTrial {
    @Test
    fun live() = runBlocking {
        val host = System.getenv("HYLA_POCKET_HOST")
        val token = System.getenv("HYLA_POCKET_TOKEN")
        assumeTrue(host != null && token != null)
        val endpoint = Endpoint.parse(host!!)!!
        val probe = HylaClient(endpoint, token)
        probe.connect()
        println("session.list: " + probe.call("session.list"))
        probe.close()

        val session = HylaSession(endpoint, token, trace = { println("trace: " + it) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            session.start(scope)
            val state = withTimeout(15_000) { session.state.first { it.phase == Phase.Live } }
            println("live: sessions=" + state.sessions.map { it.id to it.label })
            session.createShell("trial")
            val withShell = withTimeout(15_000) {
                session.state.first { s -> s.sessions.any { it.id == s.activeSessionId } && s.view != null }
            }
            println("view: " + withShell.view)
            val block = withShell.view!!.focusedBlock()!!
            val feed = withTimeout(15_000) { session.watchPane(block.id).first() }
            println("feed: " + feed::class.simpleName)
        } finally {
            session.stop()
            scope.cancel()
        }
    }
}
