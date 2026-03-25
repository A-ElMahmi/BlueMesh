package com.example.blessed3

import android.content.Context

/**
 * Role-aware facade for sending messages and disconnecting.
 * Switches between the central path (BluetoothHandler) and the peripheral path (BluetoothServer)
 * depending on MessagingConnectionState.
 */
object BleMessaging {

    fun send(context: Context, text: String) {
        val bytes = BlePacket(BlePacket.TYPE_MSG, text).toBytes()
        val peer = MessagingConnectionState.currentPeer ?: return
        when (peer.role) {
            MessagingConnectionState.Role.WE_ARE_CENTRAL ->
                BluetoothHandler.sendBytes(bytes)
            MessagingConnectionState.Role.WE_ARE_PERIPHERAL ->
                BluetoothServer.getInstance(context).sendBytes(bytes)
        }
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
