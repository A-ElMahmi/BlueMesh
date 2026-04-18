package com.example.blessed3

import org.json.JSONObject

/**
 * BLE [BlePacket.TYPE_HANDSHAKE] body and internet key-announce JSON shape.
 * Legacy: body is exactly 8 hex chars (appId only).
 */
object HandshakePayload {

    private const val LEGACY_HEX_APP_ID_LEN = 8

    private const val JSON_APP_ID = "appId"
    private const val JSON_PUB = "publicKey"

    data class Parsed(val appId: String, val publicKeySpkiBase64: String?)

    /** True if [body] looks like extended JSON handshake. */
    fun isExtendedJson(body: String): Boolean =
        body.startsWith("{") && body.contains(JSON_APP_ID)

    fun parse(body: String): Parsed? {
        if (!isExtendedJson(body)) {
            val raw = body.trim().lowercase()
            if (raw.length == LEGACY_HEX_APP_ID_LEN && raw.all { it in '0'..'9' || it in 'a'..'f' }) {
                return Parsed(appId = raw, publicKeySpkiBase64 = null)
            }
            return null
        }
        return try {
            val o = JSONObject(body)
            val id = o.getString(JSON_APP_ID).trim().lowercase()
            val pk = o.optString(JSON_PUB, "").trim().ifBlank { null }
            Parsed(appId = id, publicKeySpkiBase64 = pk)
        } catch (_: Exception) {
            null
        }
    }

    fun toJson(appId: String, publicKeySpkiBase64: String): String =
        JSONObject().apply {
            put(JSON_APP_ID, appId.lowercase())
            put(JSON_PUB, publicKeySpkiBase64)
        }.toString()
}