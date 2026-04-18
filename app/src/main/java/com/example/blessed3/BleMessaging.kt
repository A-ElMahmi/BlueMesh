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
     */
    fun sendTransport(context: Context, text: String): Boolean {
        val peer = MessagingConnectionState.currentPeer ?: run {
            Log.d(TAG, "sendTransport: no current peer")
            return false
        }
        Log.d(TAG, "sendTransport [${peer.role}] \"$text\"")
        when (peer.role) {
            MessagingConnectionState.Role.WE_ARE_CENTRAL ->
                BluetoothHandler.sendBytes(BlePacket(BlePacket.TYPE_MSG, text).toBytes())
            MessagingConnectionState.Role.WE_ARE_PERIPHERAL ->
                BluetoothServer.getInstance(context).sendBytes(BlePacket(BlePacket.TYPE_MSG, text).toBytes())
            MessagingConnectionState.Role.WE_ARE_INTERNET -> {
                val destAppId = peer.peerAppId ?: run {
                    Log.d(TAG, "sendTransport internet: peerAppId null")
                    return false
                }
                val msgId = UUID.randomUUID().toString()
                scope.launch {
                    if (NetworkUtils.hasInternet(context) && ServerClient.serverReachable.value) {
                        ServerClient.postMessage(msgId, DeviceIdentity.appId, destAppId, text)
                    } else {
                        RelayManager.flood(destAppId, text, msgId)
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
