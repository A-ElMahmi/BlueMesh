package com.example.blessed3

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY
import android.content.Context
import android.util.Log
import com.welie.blessed.BluetoothCentral
import com.welie.blessed.BluetoothPeripheralManager
import com.welie.blessed.GattStatus
import com.welie.blessed.ReadResponse
import timber.log.Timber
import java.util.UUID

internal class BleService(
    private val peripheralManager: BluetoothPeripheralManager,
    val context: Context
) {

    val service = BluetoothGattService(SERVICE_UUID, SERVICE_TYPE_PRIMARY)

    private val measurement = BluetoothGattCharacteristic(
        MEASUREMENT_CHARACTERISTIC_UUID,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
        BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
    )

    val cccDescriptor: BluetoothGattDescriptor
        get() = BluetoothGattDescriptor(
            CCC_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )

    val cudDescriptor: BluetoothGattDescriptor
        get() = BluetoothGattDescriptor(
            CUD_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )

    init {
        service.addCharacteristic(measurement)
        measurement.addDescriptor(cccDescriptor)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    fun notifyCharacteristicChanged(value: ByteArray?, characteristic: BluetoothGattCharacteristic) {
        peripheralManager.notifyCharacteristicChanged(value!!, characteristic)
    }

    fun notifyCharacteristicChanged(value: ByteArray, central: BluetoothCentral, characteristic: BluetoothGattCharacteristic) {
        peripheralManager.notifyCharacteristicChanged(value, central, characteristic)
    }

    fun noCentralsConnected(): Boolean {
        return peripheralManager.connectedCentrals.isEmpty()
    }

    // ── GATT callbacks ──────────────────────────────────────────────────────────

    fun onCharacteristicRead(central: BluetoothCentral, characteristic: BluetoothGattCharacteristic): ReadResponse {
        return ReadResponse(GattStatus.REQUEST_NOT_SUPPORTED, ByteArray(0))
    }

    fun onCharacteristicWrite(
        central: BluetoothCentral,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): GattStatus {
        val packet = BlePacket.fromBytes(value) ?: run {
            Log.d("BleMsg", "PERIPHERAL recv: failed to parse packet (${value.size} bytes)")
            return GattStatus.SUCCESS
        }
        Log.d("BleMsg", "PERIPHERAL recv type=${packet.type} body=\"${packet.body}\"")
        when (packet.type) {
            BlePacket.TYPE_MSG ->
                MessageBus.add(ChatMessage(packet.body, isFromMe = false))
            BlePacket.TYPE_HANDSHAKE -> {
                val centralAppId = packet.body
                if (centralAppId.isNotEmpty()) {
                    MessagingConnectionState.setConnectedAsPeripheral(
                        centralAddress = central.address,
                        centralName = central.name,
                        peerAppId = centralAppId
                    )
                    KnownPeers.save(
                        KnownPeer(
                            appId = centralAppId,
                            displayName = central.name.ifBlank { centralAppId },
                            lastSeenMs = System.currentTimeMillis()
                        )
                    )
                }
            }
            BlePacket.TYPE_RELAY -> {
                val relay = RelayPacket.fromJson(packet.body)
                if (relay != null) RelayManager.onReceived(relay)
            }
            BlePacket.TYPE_DISCONNECT -> {
                MessagingConnectionState.clear()
                MessageBus.clear()
            }
        }
        return GattStatus.SUCCESS
    }

    fun onCharacteristicWriteCompleted(central: BluetoothCentral, characteristic: BluetoothGattCharacteristic, value: ByteArray) {}

    fun onDescriptorRead(central: BluetoothCentral, descriptor: BluetoothGattDescriptor): ReadResponse {
        return ReadResponse(GattStatus.REQUEST_NOT_SUPPORTED, ByteArray(0))
    }

    fun onDescriptorWrite(central: BluetoothCentral, descriptor: BluetoothGattDescriptor, value: ByteArray): GattStatus {
        return GattStatus.SUCCESS
    }

    fun onNotifyingEnabled(central: BluetoothCentral, characteristic: BluetoothGattCharacteristic) {}

    fun onNotifyingDisabled(central: BluetoothCentral, characteristic: BluetoothGattCharacteristic) {}

    fun onNotificationSent(central: BluetoothCentral, value: ByteArray, characteristic: BluetoothGattCharacteristic, status: GattStatus) {}

    fun onCentralConnected(central: BluetoothCentral) {}

    fun onCentralDisconnected(central: BluetoothCentral) {}

    // ── Sending ─────────────────────────────────────────────────────────────────

    fun sendToCentral(central: BluetoothCentral, bytes: ByteArray) {
        notifyCharacteristicChanged(bytes, central, measurement)
        Timber.i("Packet sent to central %s (%d bytes)", central.address, bytes.size)
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
        val MEASUREMENT_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")
        val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val CUD_DESCRIPTOR_UUID: UUID = UUID.fromString("00002901-0000-1000-8000-00805f9b34fb")
    }
}
