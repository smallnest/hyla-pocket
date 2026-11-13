package app.hylapocket.data

import android.content.Context
import app.hylapocket.core.Endpoint
import app.hylapocket.core.ListedSession
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A hylad a person saved: where it listens, and the password its HylaPocket listener takes.
 *
 * A tailcat host is its pair code: [host] is `tailcat://` and the code, and there is no password,
 * because the code that reaches the host is the one that admits. A network host's password sits
 * with it for older hylads; this one has none.
 */
data class SavedHost(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = Endpoint.DEFAULT_PORT,
    val plaintext: Boolean = true,
    val token: String = "",
) {
    fun endpoint(): Endpoint = Endpoint(host, port, plaintext)
}

/** What this phone remembers between runs. */
class HostStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hylapocket", Context.MODE_PRIVATE)

    fun list(): List<SavedHost> {
        val raw = prefs.getString(KEY_HOSTS, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    SavedHost(
                        id = o.optString("id"),
                        label = o.optString("label"),
                        host = o.optString("host"),
                        port = o.optInt("port", Endpoint.DEFAULT_PORT),
                        plaintext = o.optBoolean("plaintext", true),
                        token = o.optString("token"),
                    ),
                )
            }
        }.filter { it.id.isNotBlank() && it.host.isNotBlank() }
    }

    fun upsert(host: SavedHost) {
        write(list().filter { it.id != host.id } + host)
    }

    fun delete(id: String) {
        write(list().filter { it.id != id })
        prefs.edit().remove(lastKey(id)).remove(panesKey(id)).remove(sessionsKey(id)).apply()
    }

    /**
     * The session list this host had last time, so the list can draw it while the connection is
     * still opening instead of showing an empty screen for a second.
     */
    fun cachedSessions(hostId: String): List<ListedSession> {
        val raw = prefs.getString(sessionsKey(hostId), null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                add(ListedSession(id, o.optString("label")))
            }
        }
    }

    fun setCachedSessions(hostId: String, sessions: List<ListedSession>) {
        val arr = JSONArray()
        sessions.forEach { arr.put(JSONObject().put("id", it.id).put("label", it.label)) }
        prefs.edit().putString(sessionsKey(hostId), arr.toString()).apply()
    }

    fun lastSession(hostId: String): String? = prefs.getString(lastKey(hostId), null)

    fun setLastSession(hostId: String, sessionId: String?) {
        prefs.edit().apply {
            if (sessionId.isNullOrBlank()) remove(lastKey(hostId)) else putString(lastKey(hostId), sessionId)
        }.apply()
    }

    /** Host the app should reconnect to when it starts after being killed. */
    fun lastHost(): String? = prefs.getString(KEY_LAST_HOST, null)

    fun setLastHost(id: String?) {
        prefs.edit().apply {
            if (id.isNullOrBlank()) remove(KEY_LAST_HOST) else putString(KEY_LAST_HOST, id)
        }.apply()
    }

    fun find(id: String): SavedHost? = list().firstOrNull { it.id == id }

    /** The pane the phone last showed in [sessionId], so reopening the session goes back to it. */
    fun lastPane(hostId: String, sessionId: String): String? =
        panes(hostId).optString(sessionId).takeIf { it.isNotEmpty() }

    /**
     * Remembers the pane shown in [sessionId]. Entries for sessions missing from [alive] are
     * dropped, so destroyed sessions do not pile up.
     */
    fun setLastPane(hostId: String, sessionId: String, blockId: String, alive: Collection<String>) {
        val current = panes(hostId)
        val next = JSONObject()
        current.keys().forEach { key -> if (key in alive) next.put(key, current.getString(key)) }
        next.put(sessionId, blockId)
        prefs.edit().putString(panesKey(hostId), next.toString()).apply()
    }

    private fun panes(hostId: String): JSONObject =
        JSONObject(prefs.getString(panesKey(hostId), null) ?: "{}")

    /** Whether the terminal uses the downloaded font, rather than the phone's monospace. */
    fun downloadedFont(): Boolean = prefs.getBoolean(KEY_FONT_DOWNLOADED, false)

    fun setDownloadedFont(on: Boolean) {
        prefs.edit().putBoolean(KEY_FONT_DOWNLOADED, on).apply()
    }

    fun fontScale(): Float = prefs.getFloat(KEY_FONT, 1f)

    fun setFontScale(scale: Float) {
        prefs.edit().putFloat(KEY_FONT, scale.coerceIn(0.7f, 1.8f)).apply()
    }

    private fun write(hosts: List<SavedHost>) {
        val arr = JSONArray()
        hosts.forEach { h ->
            arr.put(
                JSONObject()
                    .put("id", h.id)
                    .put("label", h.label)
                    .put("host", h.host)
                    .put("port", h.port)
                    .put("plaintext", h.plaintext)
                    .put("token", h.token),
            )
        }
        prefs.edit().putString(KEY_HOSTS, arr.toString()).apply()
    }

    companion object {
        private const val KEY_HOSTS = "hosts"
        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_FONT = "font_scale"
        private const val KEY_FONT_DOWNLOADED = "font_downloaded"

        fun newId(): String = UUID.randomUUID().toString()

        private fun lastKey(id: String) = "last:$id"
        private fun panesKey(id: String) = "panes:$id"
        private fun sessionsKey(id: String) = "sessions:$id"
    }
}
