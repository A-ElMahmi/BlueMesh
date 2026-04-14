package com.example.blessed3

import android.content.Context
import android.content.SharedPreferences

/**
 * Peer's long-term EC public key (X.509 SubjectPublicKeyInfo, Base64) keyed by lowercase [appId].
 * Public keys only — not secret material.
 */
object PeerPublicKeyStore {

    private const val PREFS = "e2ee_peer_public_keys"
    private lateinit var prefs: SharedPreferences

    fun initialize(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun get(peerAppId: String): String? = prefs.getString(peerAppId.lowercase(), null)

    fun has(peerAppId: String): Boolean = get(peerAppId) != null

    /**
     * Stores first-seen key only (v1 MITM model). Returns true if a new key was stored.
     */
    fun putIfAbsent(peerAppId: String, publicKeySpkiBase64: String): Boolean {
        val k = peerAppId.lowercase()
        if (prefs.contains(k)) return false
        prefs.edit().putString(k, publicKeySpkiBase64).apply()
        return true
    }

    /** Force update (e.g. tests); production uses [putIfAbsent]. */
    fun put(peerAppId: String, publicKeySpkiBase64: String) {
        prefs.edit().putString(peerAppId.lowercase(), publicKeySpkiBase64).apply()
    }
}
