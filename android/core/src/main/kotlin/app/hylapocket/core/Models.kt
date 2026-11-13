package app.hylapocket.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Where hylad listens: a host, a port, and whether the connection is plain or TLS. The address is
 * typed the way a person types it - `192.0.2.10`, `studio:7297`, `https://box.example` - and the
 * port it binds names is used when the address names none.
 *
 * A [tailcat] endpoint is hylad's tailcat listener: its [host] is `tailcat://` and the pair code,
 * there is no port to reach, and the scheme of [ws] is "tailcat" rather than ws or wss. Where the
 * code is a secret, a tailcat endpoint's is in it.
 */
data class Endpoint(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val plaintext: Boolean = true,
) {
    val tailcat: Boolean get() = host.startsWith("tailcat://", ignoreCase = true)

    /** The pair code of a tailcat endpoint, without its scheme; null for any other endpoint. */
    val pairCode: String? get() = if (tailcat) HostCodec.bare(host) else null

    fun ws(path: String): String {
        // A tailcat URL names no host and no port: its authority is the pair code, which the
        // transport reads and nothing on the network resolves.
        if (tailcat) return "tailcat://" + pairCode + path
        return wsScheme() + "://${normalizedHost()}:$port$path"
    }

    fun httpBase(): String {
        val scheme = if (plaintext) "http" else "https"
        return "$scheme://${normalizedHost()}:$port"
    }

    private fun wsScheme(): String = if (plaintext) "ws" else "wss"

    /** The name as a URL shows it: no scheme, no path, brackets kept around an IPv6 address. */
    fun hostName(): String = normalizedHost()

    private fun normalizedHost(): String {
        var h = host.trim()
        for (prefix in listOf("http://", "https://", "ws://", "wss://")) {
            if (h.startsWith(prefix)) h = h.removePrefix(prefix)
        }
        h = h.trimEnd('/')
        val slash = h.indexOf('/')
        if (slash >= 0) h = h.substring(0, slash)
        if (h.startsWith('[') && h.contains(']')) return h.substring(0, h.indexOf(']') + 1)
        val colon = h.lastIndexOf(':')
        if (colon > 0 && h.substring(colon + 1).all { it.isDigit() }) return h.substring(0, colon)
        return h
    }

    /**
     * Reads an address as a person types it. A `host:port` with a port of its own wins over
     * [defaultPort], and `https://` or simply a secure port makes it TLS.
     */
    companion object {
        /** The port hylad's `listen = tcp://:7297` example uses. */
        const val DEFAULT_PORT = 7297

        val SECURE_PORTS = setOf(443, 8443)

        fun parse(input: String, defaultPort: Int = DEFAULT_PORT): Endpoint? {
            var text = input.trim()
            if (text.isEmpty()) return null
            if (text.startsWith("tailcat://", ignoreCase = true)) text = text.substring("tailcat://".length)
            // A pair code is a tailcat host, which no network scheme opens: a URL here is a
            // different address than this field takes.
            if (text.lowercase().startsWith("hyla:")) return null
            var plaintext = true
            var scheme = false
            for (prefix in listOf("http://", "https://", "ws://", "wss://", "tcp://")) {
                if (text.startsWith(prefix, ignoreCase = true)) {
                    if (prefix.contains("s://")) plaintext = false
                    text = text.substring(prefix.length)
                    scheme = true
                    break
                }
            }
            if (!scheme && HostCodec.pairOf(text) != null) return Endpoint(HostCodec.withScheme(text))
            text = text.trimEnd('/')
            val slash = text.indexOf('/')
            if (slash >= 0) text = text.substring(0, slash)
            if (text.isEmpty()) return null
            val (host, port) = if (text.startsWith('[')) {
                val close = text.indexOf(']')
                if (close < 0) return null
                val rest = text.substring(close + 1)
                val p = rest.removePrefix(":").takeIf { it.isNotEmpty() }?.toIntOrNull()
                text.substring(0, close + 1) to p
            } else {
                val colon = text.lastIndexOf(':')
                if (colon > 0 && text.substring(colon + 1).all { it.isDigit() }) {
                    text.substring(0, colon) to text.substring(colon + 1).toIntOrNull()
                } else {
                    text to null
                }
            }
            if (host.isBlank()) return null
            // A port that is written but unusable is not an address, rather than another host.
            if (port != null && port !in 1..65535) return null
            val chosen = port ?: defaultPort
            return Endpoint(host, chosen, plaintext && chosen !in SECURE_PORTS)
        }
    }
}

data class ListedSession(val id: String, val label: String)

data class BlockInfo(
    val id: String,
    val label: String,
    val creator: String?,
    val flavor: String?,
    val owner: String?,
)

data class WindowInfo(
    val id: String,
    val label: String,
    val focusedBlockId: String?,
    val zoomedBlockId: String?,
    val blocks: List<BlockInfo>,
) {
    /** The pane this tab shows on the phone: its focused block, or its first one. */
    fun focusedBlock(): BlockInfo? = blocks.firstOrNull { it.id == focusedBlockId } ?: blocks.firstOrNull()

    fun focusedIndex(): Int = focusedBlock()?.let { blocks.indexOf(it) } ?: -1
}

data class SessionView(
    val revision: Long,
    val sessionId: String,
    val label: String,
    val activeWindowId: String?,
    val windows: List<WindowInfo>,
) {
    fun activeWindow(): WindowInfo? =
        windows.firstOrNull { it.id == activeWindowId } ?: windows.firstOrNull()

    fun focusedBlock(): BlockInfo? = activeWindow()?.focusedBlock()

    /**
     * The pane to show when this session is opened: the one the phone showed last time, if it
     * still exists, otherwise the one hylad has focused, which is where the desktop is. Asked for a
     * tab, [windowId], it is a pane of that tab: the remembered one if it is there, else the tab's
     * own focus. Choosing the pane up front lets one focus call switch the tab and the pane.
     */
    fun paneToShow(remembered: String?, windowId: String? = null): BlockInfo? {
        val tab = windowId?.let { id -> windows.firstOrNull { it.id == id } }
        if (tab != null) return remembered?.let { id -> tab.blocks.firstOrNull { it.id == id } } ?: tab.focusedBlock()
        return remembered?.let { id -> allBlocks().firstOrNull { it.id == id } } ?: focusedBlock()
    }

    fun allBlocks(): List<BlockInfo> = windows.flatMap { it.blocks }

    /**
     * This view as the phone shows it, on its own [place] - the pane it chose - rather than on the
     * tab and pane hylad has active, which is the desktop's too. Kept while the pane exists; once
     * it closes, hylad's choice is used again.
     */
    fun placedAt(place: String?): SessionView {
        if (place == null) return this
        val window = windows.firstOrNull { w -> w.blocks.any { it.id == place } } ?: return this
        return copy(
            activeWindowId = window.id,
            windows = windows.map { if (it.id == window.id) it.copy(focusedBlockId = place) else it },
        )
    }
}

data class ProgramRecord(
    val id: String,
    val state: String,
    val kind: String?,
    val app: String?,
    val title: String?,
    val msg: String?,
    val progress: Int?,
)

fun summarizeStatus(records: List<ProgramRecord>): ProgramRecord? {
    if (records.isEmpty()) return null
    val rank = mapOf("blocked" to 0, "error" to 1, "working" to 2, "done" to 3, "idle" to 4)
    return records.minBy { rank[it.state] ?: 9 }
}

fun parseView(obj: JSONObject): SessionView {
    val windowsJson = obj.optJSONArray("windows") ?: JSONArray()
    val windows = buildList {
        for (i in 0 until windowsJson.length()) {
            val w = windowsJson.optJSONObject(i) ?: continue
            val blocks = ArrayList<BlockInfo>()
            val layers = w.optJSONArray("layers") ?: JSONArray()
            for (li in 0 until layers.length()) {
                val layer = layers.optJSONObject(li) ?: continue
                val arr = layer.optJSONArray("blocks") ?: JSONArray()
                for (bi in 0 until arr.length()) {
                    val b = arr.optJSONObject(bi) ?: continue
                    val id = b.optNullableString("block_id") ?: continue
                    blocks.add(
                        BlockInfo(
                            id = id,
                            label = b.optNullableString("label") ?: shortId(id),
                            creator = b.optNullableString("creator_name"),
                            flavor = b.optNullableString("flavor"),
                            owner = b.optNullableString("owner"),
                        ),
                    )
                }
            }
            val id = w.optNullableString("window_id") ?: continue
            add(
                WindowInfo(
                    id = id,
                    label = w.optNullableString("label") ?: shortId(id),
                    focusedBlockId = w.optNullableString("focused_block_id"),
                    zoomedBlockId = w.optNullableString("zoomed_block_id"),
                    blocks = blocks,
                ),
            )
        }
    }
    val sessionId = obj.optNullableString("session_id") ?: ""
    return SessionView(
        revision = obj.optLong("revision"),
        sessionId = sessionId,
        label = obj.optNullableString("label") ?: shortId(sessionId),
        activeWindowId = obj.optNullableString("active_window_id"),
        windows = windows,
    )
}

fun parseSessions(array: JSONArray?): List<ListedSession> {
    if (array == null) return emptyList()
    val seen = LinkedHashMap<String, ListedSession>()
    for (i in 0 until array.length()) {
        val o = array.optJSONObject(i) ?: continue
        val id = o.optNullableString("session_id") ?: continue
        // hylad can list a session twice while one is being moved; the list is drawn with the id as
        // its key, so a repeat is a crash rather than a repeated row.
        seen[id] = ListedSession(id, o.optNullableString("label") ?: shortId(id))
    }
    return seen.values.toList()
}

/** What a pane runs: the app a program said it is, else the foreground process. */
fun paneProgram(status: ProgramRecord?, foreground: String?): String? =
    status?.app?.takeIf { it.isNotBlank() } ?: foreground

/**
 * The marks the app draws for a pane. A program the app knows gets its own; a shell, or nothing at
 * all, gets the terminal prompt; anything else gets its initial on the tile.
 */
enum class ProgramMark { Shell, Pi, Claude, Codex, Gemini, Cursor, OpenCode, Copilot, Vim, Emacs, Docker, Node, Python, Git, Ssh, Top, Other }

private val SHELLS = setOf("sh", "bash", "zsh", "fish", "nu", "nushell", "dash", "ksh", "tcsh", "csh", "login", "shell")

/** The mark for a program name, matched by its leading word so `Claude Code` and `claude` agree. */
fun programMark(program: String?): ProgramMark {
    val name = program?.trim()?.removePrefix("-")?.substringAfterLast('/')?.lowercase()
        ?.substringBefore(' ')?.takeIf { it.isNotEmpty() } ?: return ProgramMark.Shell
    return when (name) {
        in SHELLS -> ProgramMark.Shell
        "pi" -> ProgramMark.Pi
        "claude" -> ProgramMark.Claude
        "codex" -> ProgramMark.Codex
        "gemini" -> ProgramMark.Gemini
        "cursor", "cursor-agent" -> ProgramMark.Cursor
        "opencode" -> ProgramMark.OpenCode
        "copilot", "gh-copilot" -> ProgramMark.Copilot
        "vim", "nvim", "vi", "hx", "helix" -> ProgramMark.Vim
        "emacs" -> ProgramMark.Emacs
        "docker", "podman" -> ProgramMark.Docker
        "node", "bun", "deno", "npm", "pnpm", "yarn" -> ProgramMark.Node
        "python", "python3", "ipython", "uv" -> ProgramMark.Python
        "git", "lazygit", "tig", "jj" -> ProgramMark.Git
        "ssh", "mosh", "et" -> ProgramMark.Ssh
        "top", "htop", "btop" -> ProgramMark.Top
        else -> ProgramMark.Other
    }
}

fun shortId(id: String): String {
    val tail = id.substringAfterLast(':').substringAfterLast('-')
    return if (tail.length >= 4) tail.takeLast(6) else id.takeLast(6)
}

fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key)
}

class HylaCallException(val code: String, val status: Int, message: String) : Exception(message)
