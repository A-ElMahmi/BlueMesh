package com.example.blessed3

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class KnownPeer(
    val appId: String,
    val displayName: String,
    val lastSeenMs: Long,
    val unreadCount: Int = 0
)

/**
 * Persists a list of peers this device has chatted with before.
 * Identified by stable appId (not MAC address or device name).
 * Sorted most-recent first.
 */
object KnownPeers {
    private const val PREFS_NAME = "known_peers"
    private const val KEY_PEERS = "peers"

    private lateinit var prefs: SharedPreferences
    private val _peersFlow = MutableStateFlow<List<KnownPeer>>(emptyList())
    val peersFlow: StateFlow<List<KnownPeer>> = _peersFlow.asStateFlow()

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _peersFlow.value = loadAll()
    }

    /**
     * Upserts peer; preserves existing [KnownPeer.unreadCount] when updating the same appId.
     */
    fun save(peer: KnownPeer) {
        val normalized = peer.copy(appId = peer.appId.lowercase())
        val current = _peersFlow.value.toMutableList()
        val idx = current.indexOfFirst { it.appId == normalized.appId }
        val merged = if (idx >= 0) {
            normalized.copy(unreadCount = current[idx].unreadCount)
        } else normalized
        if (idx >= 0) current[idx] = merged else current.add(merged)
        val sorted = current.sortedByDescending { it.lastSeenMs }
        persist(sorted)
    }

    fun incrementUnread(appId: String) {
        val id = appId.lowercase()
        val current = _peersFlow.value.toMutableList()
        val idx = current.indexOfFirst { it.appId == id }
        if (idx < 0) {
            current.add(
                KnownPeer(
                    appId = id,
                    displayName = id,
                    lastSeenMs = System.currentTimeMillis(),
                    unreadCount = 1
                )
            )
        } else {
            val p = current[idx]
            current[idx] = p.copy(unreadCount = p.unreadCount + 1)
        }
        val sorted = current.sortedByDescending { it.lastSeenMs }
        persist(sorted)
    }

    fun clearUnread(appId: String) {
        val id = appId.lowercase()
        val current = _peersFlow.value.toMutableList()
        val idx = current.indexOfFirst { it.appId == id }
        if (idx < 0) return
        current[idx] = current[idx].copy(unreadCount = 0)
        persist(current.sortedByDescending { it.lastSeenMs })
    }

    fun getAll(): List<KnownPeer> = _peersFlow.value

    private fun persist(peers: List<KnownPeer>) {
        prefs.edit().putString(KEY_PEERS, serialize(peers)).apply()
        _peersFlow.value = peers
    }

    private fun loadAll(): List<KnownPeer> {
        val json = prefs.getString(KEY_PEERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                KnownPeer(
                    appId = obj.getString("appId"),
                    displayName = obj.getString("displayName"),
                    lastSeenMs = obj.getLong("lastSeenMs"),
                    unreadCount = if (obj.has("unreadCount")) obj.getInt("unreadCount") else 0
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun serialize(peers: List<KnownPeer>): String {
        val arr = JSONArray()
        peers.forEach { peer ->
            arr.put(JSONObject().apply {
                put("appId", peer.appId)
                put("displayName", peer.displayName)
                put("lastSeenMs", peer.lastSeenMs)
                put("unreadCount", peer.unreadCount)
            })
        }
        return arr.toString()
    }
}
