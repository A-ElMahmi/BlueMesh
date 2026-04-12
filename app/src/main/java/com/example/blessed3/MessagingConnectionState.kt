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
 * peerAppId is the stable 8-byte hex identity from the peer's advertisement/handshake.
 * It is null until we receive it (peripheral side gets it from the handshake packet).
 */
object MessagingConnectionState {

    enum class Role {
        /** We connected to the peer (we are central, they are peripheral). */
        WE_ARE_CENTRAL,
        /** The peer connected to us (we are peripheral, they are central). */
        WE_ARE_PERIPHERAL,
        /** No direct BLE connection; messages routed via server (internet gateway). */
        WE_ARE_INTERNET
    }

    data class Connected(
        val peerAddress: String,
        val peerName: String,
        val role: Role,
        val peerAppId: String? = null
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

    fun setConnectedAsCentral(peerAddress: String, peerName: String, peerAppId: String? = null) {
        _state.value = Connected(
            peerAddress = normalizeAddress(peerAddress) ?: peerAddress,
            peerName = peerName,
            role = Role.WE_ARE_CENTRAL,
            peerAppId = peerAppId
        )
    }

    fun setConnectedAsPeripheral(centralAddress: String, centralName: String, peerAppId: String? = null) {
        _state.value = Connected(
            peerAddress = normalizeAddress(centralAddress) ?: centralAddress,
            peerName = centralName,
            role = Role.WE_ARE_PERIPHERAL,
            peerAppId = peerAppId
        )
    }

    /** Open an internet-routed chat session to an out-of-range peer. */
    fun setConnectedAsInternet(targetAppId: String, targetName: String) {
        _state.value = Connected(
            peerAddress = targetAppId,
            peerName = targetName,
            role = Role.WE_ARE_INTERNET,
            peerAppId = targetAppId
        )
    }

    /** Called when the peripheral receives the central's handshake packet with their appId. */
    fun updatePeerAppId(appId: String) {
        _state.value = _state.value?.copy(peerAppId = appId)
    }

    fun clearIfPeer(address: String?) {
        if (isPeer(address)) _state.value = null
    }

    fun clear() {
        _state.value = null
    }
}
