package com.example.blessed3

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Stable identity for this app instance.
 *
 * Generates a random 8-byte (16 hex char) ID on first launch and persists it.
 * This ID is embedded in BLE advertisement scan responses so peers can
 * identify us across MAC address rotations without requiring a GATT connection.
 */
object DeviceIdentity {
    private const val PREFS_NAME = "device_identity"
    private const val KEY_APP_ID = "app_id"

    private var _appId: String = ""

    val appId: String
        get() = _appId

    /** Raw 8 bytes suitable for use in BLE service data advertisement. */
    val appIdBytes: ByteArray
        get() = _appId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun initialize(context: Context) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _appId = prefs.getString(KEY_APP_ID, null) ?: generateAndSave(prefs)
    }

    private fun generateAndSave(prefs: SharedPreferences): String {
        val uuid = UUID.randomUUID()
        val msb = uuid.mostSignificantBits
        val bytes = ByteArray(8) { i -> ((msb ushr ((7 - i) * 8)) and 0xFF).toByte() }
        val id = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_APP_ID, id).apply()
        return id
    }
}
