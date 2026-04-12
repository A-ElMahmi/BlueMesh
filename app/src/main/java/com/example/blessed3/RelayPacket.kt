package com.example.blessed3

import org.json.JSONObject

/**
 * Payload for mesh-relay messages. Travels inside a [BlePacket] with type [BlePacket.TYPE_RELAY].
 *
 * Wire format (stored in BlePacket.body as a JSON string):
 * {
 *   "messageId":        "uuid-string",
 *   "originAppId":      "aabbccdd...",
 *   "destinationAppId": "11223344...",
 *   "content":          "hello"
 * }
 */
data class RelayPacket(
    val messageId: String,
    val originAppId: String,
    val destinationAppId: String,
    val content: String
) {

    fun toJson(): String = JSONObject().apply {
        put("messageId", messageId)
        put("originAppId", originAppId)
        put("destinationAppId", destinationAppId)
        put("content", content)
    }.toString()

    companion object {
        fun fromJson(json: String): RelayPacket? = try {
            val obj = JSONObject(json)
            RelayPacket(
                messageId = obj.getString("messageId"),
                originAppId = obj.getString("originAppId"),
                destinationAppId = obj.getString("destinationAppId"),
                content = obj.getString("content")
            )
        } catch (e: Exception) {
            null
        }
    }
}
