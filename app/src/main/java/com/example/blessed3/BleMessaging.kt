package com.example.blessed3

import android.content.Context
import android.util.Log

/**
 * Role-aware facade for sending messages and disconnecting.
 * Switches between the central path (BluetoothHandler) and the peripheral path (BluetoothServer)
 * depending on MessagingConnectionState.
 */
private const val TAG = "BleMsg"

object BleMessaging {

    fun send(context: Context, text: String) {
        val bytes = BlePacket(BlePacket.TYPE_MSG, text).toBytes()
        val peer = MessagingConnectionState.currentPeer ?: run {
            Log.d(TAG, "SEND blocked: no current peer")
            return
        }
        Log.d(TAG, "SEND [${peer.role}] \"$text\"")
        when (peer.role) {
            MessagingConnectionState.Role.WE_ARE_CENTRAL ->
                BluetoothHandler.sendBytes(bytes)
            MessagingConnectionState.Role.WE_ARE_PERIPHERAL ->
                BluetoothServer.getInstance(context).sendBytes(bytes)
        }
        MessageBus.add(ChatMessage(text, isFromMe = true))
    }

    fun disconnect(context: Context) {
        val peer = MessagingConnectionState.currentPeer ?: return
        val disconnectBytes = BlePacket(BlePacket.TYPE_DISCONNECT).toBytes()
        when (peer.role) {
            MessagingConnectionState.Role.WE_ARE_CENTRAL ->
                // Disconnect fires inside onCharacteristicWrite once the packet is confirmed received
                BluetoothHandler.sendBytesAndDisconnect(disconnectBytes)
            MessagingConnectionState.Role.WE_ARE_PERIPHERAL ->
                // Disconnect fires inside onNotificationSent once the packet is delivered
                BluetoothServer.getInstance(context).sendBytesAndDisconnect(disconnectBytes)
        }
    }
}
