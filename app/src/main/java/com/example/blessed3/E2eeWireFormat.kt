package com.example.blessed3

/**
 * In-band wire prefixes for E2EE (server `content`, relay JSON, BLE [BlePacket] bodies).
 * Metadata fields (from, to, messageId) stay plaintext.
 */
object E2eeWireFormat {
    const val KEY_ANNOUNCE_PREFIX = "E2EE_KEY1:"
    const val MSG_PREFIX = "E2EE_MSG1:"

    fun isKeyAnnounce(content: String): Boolean = content.startsWith(KEY_ANNOUNCE_PREFIX)
    fun isEncryptedMessage(content: String): Boolean = content.startsWith(MSG_PREFIX)
}
