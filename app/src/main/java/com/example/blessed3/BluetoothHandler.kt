package com.example.blessed3

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.BluetoothPeripheralCallback
import com.welie.blessed.ConnectionPriority
import com.welie.blessed.GattStatus
import com.welie.blessed.HciStatus
import com.welie.blessed.WriteType.WITH_RESPONSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID


@SuppressLint("StaticFieldLeak")
object BluetoothHandler {

    private lateinit var context: Context

    // Setup our own thread for BLE.
    // Use Handler(Looper.getMainLooper()) if you want to run on main thread
    private val handlerThread = HandlerThread("Blessed", Process.THREAD_PRIORITY_DEFAULT)
    private lateinit var handler : Handler

    lateinit var centralManager: BluetoothCentralManager

    private val measurementFlow_ = MutableStateFlow("Waiting for measurement")
    val measurementFlow = measurementFlow_.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val HRS_SERVICE_UUID: UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
    private val HRS_MEASUREMENT_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")
    private val NEW_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A39-0000-1000-8000-00805f9b34fb")

    private lateinit var peripheralGlobal: BluetoothPeripheral

    @Volatile private var disconnectAfterWrite = false

    val bluetoothPeripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
            peripheralGlobal = peripheral
            peripheral.requestConnectionPriority(ConnectionPriority.HIGH)
            peripheral.startNotify(HRS_SERVICE_UUID, HRS_MEASUREMENT_CHARACTERISTIC_UUID)
        }

        override fun onCharacteristicWrite(peripheral: BluetoothPeripheral, value: ByteArray, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
            if (disconnectAfterWrite) {
                disconnectAfterWrite = false
                disconnectAsCentral()
            }
        }

        override fun onNotificationStateUpdate(peripheral: BluetoothPeripheral, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
            if (status == GattStatus.SUCCESS) {
                val isNotifying = peripheral.isNotifying(characteristic)
                Timber.i("SUCCESS: Notify set to '%s' for %s", isNotifying, characteristic.uuid)
            } else {
                Timber.e("ERROR: Changing notification state failed for %s (%s)", characteristic.uuid, status)
            }
        }

        override fun onCharacteristicUpdate(peripheral: BluetoothPeripheral, value: ByteArray, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
            when (characteristic.uuid) {
                HRS_MEASUREMENT_CHARACTERISTIC_UUID -> {
                    val packet = BlePacket.fromBytes(value) ?: return
                    when (packet.type) {
                        BlePacket.TYPE_MSG ->
                            scope.launch(Dispatchers.Main) {
                                Toast.makeText(context, packet.body, Toast.LENGTH_SHORT).show()
                            }
                        BlePacket.TYPE_DISCONNECT -> {
                            MessagingConnectionState.clear()
                            scope.launch(Dispatchers.Main) {
                                Toast.makeText(context, "Peer disconnected gracefully", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                NEW_CHARACTERISTIC_UUID -> {
                    val text = value.toString(Charsets.UTF_8)
                    println("New Char: $text")
                }
            }
        }

    }

    fun sendBytesAndDisconnect(bytes: ByteArray) {
        disconnectAfterWrite = true
        sendBytes(bytes)
    }

    fun sendBytes(bytes: ByteArray) {
        val peer = MessagingConnectionState.currentPeer ?: return
        val peripheral = centralManager.getConnectedPeripherals().find {
            it.address.equals(peer.peerAddress, ignoreCase = true)
        } ?: return
        peripheral.writeCharacteristic(
            HRS_SERVICE_UUID,
            NEW_CHARACTERISTIC_UUID,
            bytes,
            WITH_RESPONSE
        )
    }

    fun disconnectAsCentral() {
        val peer = MessagingConnectionState.currentPeer ?: return
        val peripheral = centralManager.getConnectedPeripherals().find {
            it.address.equals(peer.peerAddress, ignoreCase = true)
        } ?: return
        centralManager.cancelConnection(peripheral)
    }

    private val connectRequestFlow_ = MutableSharedFlow<BluetoothPeripheral>()
    val connectRequestFlow = connectRequestFlow_.asSharedFlow()

    private val bluetoothCentralManagerCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscovered(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            Timber.i("Found peripheral '${peripheral.name}' with RSSI ${scanResult.rssi}")
            centralManager.stopScan()

            scope.launch {
                connectRequestFlow_.emit(peripheral)
            }
        }

        override fun onConnected(peripheral: BluetoothPeripheral) {
            Timber.i("connected to '${peripheral.name}'")
            MessagingConnectionState.setConnectedAsCentral(peripheral.address, peripheral.name.ifBlank { "" })
            Toast.makeText(context, "Connected to ${peripheral.name}", LENGTH_SHORT).show()
        }

        override fun onDisconnected(peripheral: BluetoothPeripheral, status: HciStatus) {
            Timber.i("disconnected '${peripheral.name}'")
            MessagingConnectionState.clearIfPeer(peripheral.address)
            Toast.makeText(context, "Disconnected ${peripheral.name}", LENGTH_SHORT).show()
            // Do not autoConnect here; let the user explicitly reconnect.
        }

        override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
            Timber.e("failed to connect to '${peripheral.name}'")
        }

        override fun onBluetoothAdapterStateChanged(state: Int) {
            Timber.i("bluetooth adapter changed state to %d", state)
            if (state == BluetoothAdapter.STATE_ON) {
                // Bluetooth is on now, start scanning again
                // Scan for peripherals with a certain service UUIDs
//                centralManager.startPairingPopupHack()
//                startScanning()
            }
        }
    }

    fun startScanning() {
        if(centralManager.isNotScanning) {
            centralManager.scanForPeripheralsWithServices(
                setOf(
                    HRS_SERVICE_UUID,
                )
            )
        }
    }

    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        Timber.plant(Timber.DebugTree())
        Timber.i("initializing BluetoothHandler")

        //  the thread and create our private Handler
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        this.context = context.applicationContext
        this.centralManager = BluetoothCentralManager(this.context, bluetoothCentralManagerCallback, handler)
    }
}
