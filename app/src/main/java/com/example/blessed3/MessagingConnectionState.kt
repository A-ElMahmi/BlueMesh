package com.example.blessed3

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for the current BLE "messaging peer" connection.
 *
 * We maintain at most one active connection for 2-way messaging:
 * - Either we are CENTRAL (we connected to them) → we send via write, receive via notify
 * - Or we are PERIPHERAL (they connected to us) → we send via notify, receive via write
 *
 * Device identity uses Bluetooth MAC address (normalized uppercase) so we can
 * detect "already connected" when the user on the peripheral side tries to scan:
 * the device they see is the same central already connected to us.
 */
object MessagingConnectionState {

    enum class Role {
        /** We connected to the peer (we are central, they are peripheral). */
        WE_ARE_CENTRAL,
        /** The peer connected to us (we are peripheral, they are central). */
        WE_ARE_PERIPHERAL
    }

    data class Connected(
        val peerAddress: String,
        val peerName: String,
        val role: Role
    ) {
        fun displayLabel(): String = peerName.ifBlank { peerAddress }
    }

    private val _state = MutableStateFlow<Connected?>(null)
    val state: StateFlow<Connected?> = _state.asStateFlow()

    val isConnected: Boolean
        get() = _state.value != null

    val currentPeer: Connected?
        get() = _state.value

    /** Normalize address for comparison (blessed uses uppercase). */
    fun normalizeAddress(address: String?): String? =
        address?.uppercase()?.takeIf { it.isNotBlank() }

    /** True if the given address is our current peer (any role). */
    fun isPeer(address: String?): Boolean {
        val a = normalizeAddress(address) ?: return false
        return _state.value?.peerAddress == a
    }

    fun setConnectedAsCentral(peerAddress: String, peerName: String) {
        _state.value = Connected(
            peerAddress = normalizeAddress(peerAddress) ?: peerAddress,
            peerName = peerName,
            role = Role.WE_ARE_CENTRAL
        )
    }

    fun setConnectedAsPeripheral(centralAddress: String, centralName: String) {
        _state.value = Connected(
            peerAddress = normalizeAddress(centralAddress) ?: centralAddress,
            peerName = centralName,
            role = Role.WE_ARE_PERIPHERAL
        )
    }

    fun clearIfPeer(address: String?) {
        if (isPeer(address)) _state.value = null
    }

    fun clear() {
        _state.value = null
    }
}
