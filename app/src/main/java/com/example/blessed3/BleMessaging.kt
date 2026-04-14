package com.example.blessed3

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Role-aware facade for sending payloads on the active transport link.
 * History is appended by [ChatTransportCoordinator] / inbound handlers.
 */
private const val TAG = "BleMsg"

object BleMessaging {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Sends one chat payload on the current [MessagingConnectionState] link. Returns false if no link.
     * [payload] is ciphertext or key-announce wire string (never user plaintext on the wire).
     */
    fun sendTransport(context: Context, payload: String): Boolean {
        val peer = MessagingConnectionState.currentPeer ?: run {
            Log.d(TAG, "sendTransport: no current peer")
            return false
        }
        Log.d(TAG, "sendTransport [${peer.role}] len=${payload.length}")
        when (peer.role) {
            MessagingConnectionState.Role.WE_ARE_CENTRAL ->
                BluetoothHandler.sendBytes(BlePacket(BlePacket.TYPE_MSG, payload).toBytes())
            MessagingConnectionState.Role.WE_ARE_PERIPHERAL ->
                BluetoothServer.getInstance(context).sendBytes(BlePacket(BlePacket.TYPE_MSG, payload).toBytes())
            MessagingConnectionState.Role.WE_ARE_INTERNET -> {
                val destAppId = peer.peerAppId ?: run {
                    Log.d(TAG, "sendTransport internet: peerAppId null")
                    return false
                }
                val msgId = UUID.randomUUID().toString()
                scope.launch {
                    if (NetworkUtils.hasInternet(context)) {
                        ServerClient.postMessage(msgId, DeviceIdentity.appId, destAppId, payload)
                    } else {
                        RelayManager.flood(destAppId, payload, msgId)
                    }
                }
            }
        }
        return true
    }

    /** Announces our long-term public key: BLE uses [BlePacket.TYPE_HANDSHAKE] JSON; server/relay use [E2eeWireFormat.KEY_ANNOUNCE_PREFIX]. */
    fun trySendKeyAnnounce(context: Context): Boolean {
        val peer = MessagingConnectionState.currentPeer ?: return false
        val handshakeJson = HandshakePayload.toJson(DeviceIdentity.appId, E2eeIdentity.publicKeySpkiBase64())
        when (peer.role) {
            MessagingConnectionState.Role.WE_ARE_CENTRAL ->
                BluetoothHandler.sendBytes(BlePacket(BlePacket.TYPE_HANDSHAKE, handshakeJson).toBytes())
            MessagingConnectionState.Role.WE_ARE_PERIPHERAL ->
                BluetoothServer.getInstance(context).sendBytes(
                    BlePacket(BlePacket.TYPE_HANDSHAKE, handshakeJson).toBytes()
                )
            MessagingConnectionState.Role.WE_ARE_INTERNET -> {
                val dest = peer.peerAppId ?: return false
                val wire = ChatPayloadCrypto.buildKeyAnnounceWire()
                val msgId = UUID.randomUUID().toString()
                scope.launch {
                    if (NetworkUtils.hasInternet(context)) {
                        ServerClient.postMessage(msgId, DeviceIdentity.appId, dest, wire)
                    } else {
                        RelayManager.flood(dest, wire, msgId)
                    }
                }
            }
        }
        return true
    }

    /** @deprecated Prefer [ChatTransportCoordinator.teardownAllTransport]. */
    fun disconnect(context: Context) {
        val peer = MessagingConnectionState.currentPeer ?: return
        val disconnectBytes = BlePacket(BlePacket.TYPE_DISCONNECT).toBytes()
        when (peer.role) {
            MessagingConnectionState.Role.WE_ARE_CENTRAL ->
                BluetoothHandler.sendBytesAndDisconnect(disconnectBytes)
            MessagingConnectionState.Role.WE_ARE_PERIPHERAL ->
                BluetoothServer.getInstance(context).sendBytesAndDisconnect(disconnectBytes)
            MessagingConnectionState.Role.WE_ARE_INTERNET ->
                MessagingConnectionState.clear()
        }
    }
}
