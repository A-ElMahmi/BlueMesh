package com.example.blessed3

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY
import android.content.Context
import com.welie.blessed.BluetoothCentral
import com.welie.blessed.BluetoothPeripheralManager
import com.welie.blessed.GattStatus
import com.welie.blessed.ReadResponse
import android.util.Log
import timber.log.Timber
import java.util.UUID

internal class HeartRateService(peripheralManager: BluetoothPeripheralManager, val context: Context) :
    BaseService(
        peripheralManager,
        BluetoothGattService(HRS_SERVICE_UUID, SERVICE_TYPE_PRIMARY),
        "Counter"
    ) {

    private val measurement = BluetoothGattCharacteristic(
        HEARTRATE_MEASUREMENT_CHARACTERISTIC_UUID,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        0
    )

    private val newChar = BluetoothGattCharacteristic(
        NEW_CHARACTERISTIC_UUID,
        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
        BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
    )

    init {
        service.addCharacteristic(measurement)
        measurement.addDescriptor(cccDescriptor)

        service.addCharacteristic(newChar)
    }

    override fun onCentralDisconnected(central: BluetoothCentral) {
        if (noCentralsConnected()) {
            stopNotifying()
        }
    }

    override fun onNotifyingEnabled(central: BluetoothCentral, characteristic: BluetoothGattCharacteristic) {
        // Don't send anything automatically; wait for user to type a message
    }

    override fun onNotifyingDisabled(central: BluetoothCentral, characteristic: BluetoothGattCharacteristic) {
        if (characteristic.uuid == HEARTRATE_MEASUREMENT_CHARACTERISTIC_UUID) {
            stopNotifying()
        }
    }

    override fun onCharacteristicRead(
        central: BluetoothCentral,
        characteristic: BluetoothGattCharacteristic
    ): ReadResponse {
        return ReadResponse(GattStatus.SUCCESS, "Alhamdolilah. This is a really long message that should be broken up into multiple packets. Will it arrive? Bismillah".toByteArray())
    }

    override fun onCharacteristicWrite(
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
            BlePacket.TYPE_DISCONNECT -> {
                MessagingConnectionState.clear()
                MessageBus.clear()
            }
        }
        return GattStatus.SUCCESS
    }

    fun sendToCentral(central: BluetoothCentral, bytes: ByteArray) {
        notifyCharacteristicChanged(bytes, central, measurement)
        Timber.i("Packet sent to central %s (%d bytes)", central.address, bytes.size)
    }

    private fun stopNotifying() {
        // nothing to cancel; notifications are only sent on user request
    }

    companion object {
        val HRS_SERVICE_UUID: UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
        private val HEARTRATE_MEASUREMENT_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")
        private val NEW_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A39-0000-1000-8000-00805f9b34fb")
    }
}