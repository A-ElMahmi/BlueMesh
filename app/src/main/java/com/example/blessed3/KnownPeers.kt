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
    val lastSeenMs: Long
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

    fun save(peer: KnownPeer) {
        val current = _peersFlow.value.toMutableList()
        val idx = current.indexOfFirst { it.appId == peer.appId }
        if (idx >= 0) current[idx] = peer else current.add(0, peer)
        val sorted = current.sortedByDescending { it.lastSeenMs }
        prefs.edit().putString(KEY_PEERS, serialize(sorted)).apply()
        _peersFlow.value = sorted
    }

    fun getAll(): List<KnownPeer> = _peersFlow.value

    private fun loadAll(): List<KnownPeer> {
        val json = prefs.getString(KEY_PEERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                KnownPeer(
                    appId = obj.getString("appId"),
                    displayName = obj.getString("displayName"),
                    lastSeenMs = obj.getLong("lastSeenMs")
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
            })
        }
        return arr.toString()
    }
}
