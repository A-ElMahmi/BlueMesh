package com.example.blessed3

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Role-aware facade for sending messages and disconnecting.
 * Routes through BLE central, BLE peripheral, or internet gateway
 * depending on [MessagingConnectionState].
 */
private const val TAG = "BleMsg"

object BleMessaging {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun send(context: Context, text: String) {
        val peer = MessagingConnectionState.currentPeer ?: run {
            Log.d(TAG, "SEND blocked: no current peer")
            return
        }
        Log.d(TAG, "SEND [${peer.role}] \"$text\"")
        when (peer.role) {
            MessagingConnectionState.Role.WE_ARE_CENTRAL ->
                BluetoothHandler.sendBytes(BlePacket(BlePacket.TYPE_MSG, text).toBytes())
            MessagingConnectionState.Role.WE_ARE_PERIPHERAL ->
                BluetoothServer.getInstance(context).sendBytes(BlePacket(BlePacket.TYPE_MSG, text).toBytes())
            MessagingConnectionState.Role.WE_ARE_INTERNET -> {
                val destAppId = peer.peerAppId ?: run {
                    Log.d(TAG, "SEND internet blocked: peerAppId is null")
                    return
                }
                val msgId = UUID.randomUUID().toString()
                if (NetworkUtils.hasInternet(context)) {
                    scope.launch {
                        ServerClient.postMessage(msgId, DeviceIdentity.appId, destAppId, text)
                    }
                } else {
                    RelayManager.flood(destAppId, text, msgId)
                }
            }
        }
        MessageBus.add(ChatMessage(text, isFromMe = true))
    }

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
