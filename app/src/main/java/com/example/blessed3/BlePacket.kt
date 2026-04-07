package com.example.blessed3

import org.json.JSONObject

/**
 * Structured envelope for all BLE messages.
 *
 * Wire format: UTF-8 JSON  →  {"type":"msg","body":"hello"}
 *
 * Adding a new packet type: add a TYPE_* constant and handle it in
 * HeartRateService.onCharacteristicWrite (peripheral receive side) and
 * BluetoothHandler.onCharacteristicUpdate (central receive side).
 */
data class BlePacket(val type: String, val body: String = "") {

    fun toBytes(): ByteArray =
        JSONObject().apply {
            put("type", type)
            put("body", body)
        }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        const val TYPE_MSG = "msg"
        const val TYPE_DISCONNECT = "disconnect"
        const val TYPE_HANDSHAKE = "handshake"
        const val TYPE_RELAY = "relay"

        fun fromBytes(bytes: ByteArray): BlePacket? = try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            BlePacket(
                type = json.getString("type"),
                body = json.optString("body", "")
            )
        } catch (e: Exception) {
            null
        }
    }
}
