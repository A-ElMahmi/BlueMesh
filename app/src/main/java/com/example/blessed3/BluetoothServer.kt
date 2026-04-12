package com.example.blessed3

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.welie.blessed.AdvertiseError
import com.welie.blessed.BluetoothCentral
import com.welie.blessed.BluetoothPeripheralManager
import com.welie.blessed.BluetoothPeripheralManagerCallback
import com.welie.blessed.GattStatus
import com.welie.blessed.ReadResponse
import timber.log.Timber
import java.util.UUID

@SuppressLint("MissingPermission")
class BluetoothServer(private val context: Context) {
    var isInitialized = false
    lateinit var peripheralManager: BluetoothPeripheralManager
    @Volatile private var disconnectAfterNotification = false
    private val bluetoothManager: BluetoothManager
    private lateinit var bleService: BleService

    private val peripheralManagerCallback: BluetoothPeripheralManagerCallback = object : BluetoothPeripheralManagerCallback() {
        override fun onServiceAdded(status: GattStatus, service: BluetoothGattService) {}

        override fun onCharacteristicRead(bluetoothCentral: BluetoothCentral, characteristic: BluetoothGattCharacteristic): ReadResponse {
            return bleService.onCharacteristicRead(bluetoothCentral, characteristic)
        }

        override fun onCharacteristicWrite(bluetoothCentral: BluetoothCentral, characteristic: BluetoothGattCharacteristic, value: ByteArray): GattStatus {
            return bleService.onCharacteristicWrite(bluetoothCentral, characteristic, value)
        }

        override fun onCharacteristicWriteCompleted(bluetoothCentral: BluetoothCentral, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            bleService.onCharacteristicWriteCompleted(bluetoothCentral, characteristic, value)
        }

        override fun onDescriptorRead(bluetoothCentral: BluetoothCentral, descriptor: BluetoothGattDescriptor): ReadResponse {
            return bleService.onDescriptorRead(bluetoothCentral, descriptor)
        }

        override fun onDescriptorWrite(bluetoothCentral: BluetoothCentral, descriptor: BluetoothGattDescriptor, value: ByteArray): GattStatus {
            return bleService.onDescriptorWrite(bluetoothCentral, descriptor, value)
        }

        override fun onNotifyingEnabled(bluetoothCentral: BluetoothCentral, characteristic: BluetoothGattCharacteristic) {
            bleService.onNotifyingEnabled(bluetoothCentral, characteristic)
        }

        override fun onNotifyingDisabled(bluetoothCentral: BluetoothCentral, characteristic: BluetoothGattCharacteristic) {
            bleService.onNotifyingDisabled(bluetoothCentral, characteristic)
        }

        override fun onNotificationSent(bluetoothCentral: BluetoothCentral, value: ByteArray, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
            if (disconnectAfterNotification) {
                disconnectAfterNotification = false
                peripheralManager.cancelConnection(bluetoothCentral)
            }
            bleService.onNotificationSent(bluetoothCentral, value, characteristic, status)
        }

        override fun onCentralConnected(bluetoothCentral: BluetoothCentral) {
            // Relay-only connections never send handshake — avoid opening chat until TYPE_HANDSHAKE.
            bleService.onCentralConnected(bluetoothCentral)
        }

        override fun onCentralDisconnected(bluetoothCentral: BluetoothCentral) {
            val peer = MessagingConnectionState.currentPeer
            if (peer != null &&
                peer.role == MessagingConnectionState.Role.WE_ARE_PERIPHERAL &&
                MessagingConnectionState.isPeer(bluetoothCentral.address)
            ) {
                MessagingConnectionState.clear()
                MessageBus.clear()
            }
            bleService.onCentralDisconnected(bluetoothCentral)
        }

        override fun onAdvertisingStarted(settingsInEffect: AdvertiseSettings) {}
        override fun onAdvertiseFailure(advertiseError: AdvertiseError) {}
        override fun onAdvertisingStopped() {}
        override fun onBluetoothAdapterStateChanged(state: Int) {
            if (state == BluetoothAdapter.STATE_ON) {
                startAdvertising()
            }
        }
    }

    fun startAdvertising() {
        startAdvertising(BleService.SERVICE_UUID)
    }

    fun startAdvertising(serviceUUID: UUID?) {
        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(serviceUUID))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(true)
            .addServiceData(ParcelUuid(serviceUUID), DeviceIdentity.appIdBytes)
            .build()
        peripheralManager.startAdvertising(advertiseSettings, advertiseData, scanResponse)
    }

    fun initialize() {
        val bluetoothAdapter = bluetoothManager.adapter
        bluetoothAdapter.name = Build.MODEL

        peripheralManager.openGattServer()
        peripheralManager.removeAllServices()

        bleService = BleService(peripheralManager, context)
        peripheralManager.add(bleService.service)

        isInitialized = true
    }

    fun sendBytesAndDisconnect(bytes: ByteArray) {
        disconnectAfterNotification = true
        sendBytes(bytes)
        Handler(Looper.getMainLooper()).postDelayed({
            if (disconnectAfterNotification) {
                disconnectAfterNotification = false
                MessagingConnectionState.clear()
                MessageBus.clear()
                getConnectedCentral()?.let { peripheralManager.cancelConnection(it) }
            }
        }, 5_000)
    }

    fun sendBytes(bytes: ByteArray) {
        val central = getConnectedCentral() ?: return
        bleService.sendToCentral(central, bytes)
    }

    fun disconnectAsPeripheral() {
        getConnectedCentral()?.let { central ->
            peripheralManager.cancelConnection(central)
        }
    }

    fun isCentralConnected(address: String): Boolean =
        peripheralManager.connectedCentrals.any { it.address.equals(address, ignoreCase = true) }

    fun getConnectedCentral(): BluetoothCentral? =
        peripheralManager.connectedCentrals.firstOrNull()

    init {
        Timber.plant(Timber.DebugTree())
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        peripheralManager = BluetoothPeripheralManager(context, bluetoothManager, peripheralManagerCallback)
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var instance: BluetoothServer? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): BluetoothServer {
            if (instance == null) {
                instance = BluetoothServer(context.applicationContext)
            }
            return instance!!
        }
    }
}
