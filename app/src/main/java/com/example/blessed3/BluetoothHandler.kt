package com.example.blessed3

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.os.Process
import android.util.Log
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID


@SuppressLint("StaticFieldLeak")
object BluetoothHandler {

    private lateinit var context: Context

    private val handlerThread = HandlerThread("Blessed", Process.THREAD_PRIORITY_DEFAULT)
    private lateinit var handler: Handler

    lateinit var centralManager: BluetoothCentralManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _scannedDevices = MutableStateFlow<List<BluetoothPeripheral>>(emptyList())
    val scannedDevices = _scannedDevices.asStateFlow()

    fun clearScannedDevices() { _scannedDevices.value = emptyList() }

    private val HRS_SERVICE_UUID: UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
    private val HRS_SERVICE_PARCEL_UUID = ParcelUuid(HRS_SERVICE_UUID)
    private val HRS_MEASUREMENT_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")
    private val NEW_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A39-0000-1000-8000-00805f9b34fb")

    private lateinit var peripheralGlobal: BluetoothPeripheral

    @Volatile private var disconnectAfterWrite = false

    /** Maps BLE address → stable appId extracted from scan response service data. */
    private val addressToAppId = mutableMapOf<String, String>()

    // ── Scan-for-peer state ────────────────────────────────────────────────────

    @Volatile private var targetAppId: String? = null
    private var onPeerFound: ((BluetoothPeripheral) -> Unit)? = null
    private var onPeerNotFound: (() -> Unit)? = null
    private var scanForPeerJob: Job? = null

    /**
     * Start a targeted scan for a known peer by appId.
     * Calls [onFound] (on main thread) if found within [timeoutMs], else [onNotFound].
     */
    fun scanForPeer(
        appId: String,
        timeoutMs: Long = 10_000,
        onFound: (BluetoothPeripheral) -> Unit,
        onNotFound: () -> Unit
    ) {
        cancelScanForPeer()
        targetAppId = appId
        onPeerFound = { p -> mainHandler.post { onFound(p) } }
        onPeerNotFound = { mainHandler.post { onNotFound() } }

        addressToAppId.clear()
        centralManager.stopScan()
        startScanning()

        scanForPeerJob = scope.launch {
            delay(timeoutMs)
            val callback = onPeerNotFound
            clearScanForPeerState()
            centralManager.stopScan()
            callback?.invoke()
        }
    }

    fun cancelScanForPeer() {
        scanForPeerJob?.cancel()
        clearScanForPeerState()
    }

    private fun clearScanForPeerState() {
        scanForPeerJob = null
        targetAppId = null
        onPeerFound = null
        onPeerNotFound = null
    }

    // ── Peripheral callback ────────────────────────────────────────────────────

    val bluetoothPeripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
            peripheralGlobal = peripheral
            peripheral.requestConnectionPriority(ConnectionPriority.HIGH)
            peripheral.requestMtu(512)
            peripheral.startNotify(HRS_SERVICE_UUID, HRS_MEASUREMENT_CHARACTERISTIC_UUID)
            // Send handshake so peripheral side can learn our stable appId and save us as a peer
            val handshake = BlePacket(BlePacket.TYPE_HANDSHAKE, DeviceIdentity.appId).toBytes()
            peripheral.writeCharacteristic(HRS_SERVICE_UUID, NEW_CHARACTERISTIC_UUID, handshake, WITH_RESPONSE)
        }

        override fun onCharacteristicWrite(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            if (disconnectAfterWrite) {
                disconnectAfterWrite = false
                disconnectAsCentral()
            }
        }

        override fun onNotificationStateUpdate(
            peripheral: BluetoothPeripheral,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            if (status == GattStatus.SUCCESS) {
                Timber.i("SUCCESS: Notify set to '%s' for %s", peripheral.isNotifying(characteristic), characteristic.uuid)
            } else {
                Timber.e("ERROR: Changing notification state failed for %s (%s)", characteristic.uuid, status)
            }
        }

        override fun onCharacteristicUpdate(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            when (characteristic.uuid) {
                HRS_MEASUREMENT_CHARACTERISTIC_UUID -> {
                    Log.d("BleMsg", "CENTRAL recv ${value.size} bytes from ${peripheral.address}")
                    val packet = BlePacket.fromBytes(value) ?: run {
                        Log.d("BleMsg", "CENTRAL recv: failed to parse packet")
                        return
                    }
                    Log.d("BleMsg", "CENTRAL recv type=${packet.type} body=\"${packet.body}\"")
                    when (packet.type) {
                        BlePacket.TYPE_MSG ->
                            MessageBus.add(ChatMessage(packet.body, isFromMe = false))
                        BlePacket.TYPE_DISCONNECT -> {
                            MessagingConnectionState.clear()
                            MessageBus.clear()
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

    // ── Send / disconnect helpers ──────────────────────────────────────────────

    fun sendBytesAndDisconnect(bytes: ByteArray) {
        disconnectAfterWrite = true
        sendBytes(bytes)
        scope.launch {
            delay(5_000)
            if (disconnectAfterWrite) {
                disconnectAfterWrite = false
                MessagingConnectionState.clear()
                MessageBus.clear()
            }
        }
    }

    fun sendBytes(bytes: ByteArray) {
        val peer = MessagingConnectionState.currentPeer ?: return
        val peripheral = centralManager.getConnectedPeripherals().find {
            it.address.equals(peer.peerAddress, ignoreCase = true)
        } ?: return
        peripheral.writeCharacteristic(HRS_SERVICE_UUID, NEW_CHARACTERISTIC_UUID, bytes, WITH_RESPONSE)
    }

    fun disconnectAsCentral() {
        val peer = MessagingConnectionState.currentPeer ?: return
        val peripheral = centralManager.getConnectedPeripherals().find {
            it.address.equals(peer.peerAddress, ignoreCase = true)
        } ?: return
        centralManager.cancelConnection(peripheral)
    }

    // ── Central manager callback ───────────────────────────────────────────────

    private val bluetoothCentralManagerCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscovered(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            Timber.i("Found peripheral '${peripheral.name}' with RSSI ${scanResult.rssi}")

            val appId = extractAppId(scanResult)
            if (appId != null) {
                addressToAppId[peripheral.address] = appId
            }

            // Targeted scan: check if this is the peer we're looking for
            val target = targetAppId
            if (target != null && appId == target) {
                val callback = onPeerFound
                scanForPeerJob?.cancel()
                clearScanForPeerState()
                centralManager.stopScan()
                callback?.invoke(peripheral)
                return
            }

            // Normal scan: accumulate for the DiscoverActivity list
            val current = _scannedDevices.value
            if (current.none { it.address == peripheral.address }) {
                _scannedDevices.value = current + peripheral
            }
        }

        override fun onConnected(peripheral: BluetoothPeripheral) {
            Timber.i("connected to '${peripheral.name}'")
            val appId = addressToAppId[peripheral.address]
            MessagingConnectionState.setConnectedAsCentral(
                peerAddress = peripheral.address,
                peerName = peripheral.name.ifBlank { "" },
                peerAppId = appId
            )
        }

        override fun onDisconnected(peripheral: BluetoothPeripheral, status: HciStatus) {
            Timber.i("disconnected '${peripheral.name}'")
            MessagingConnectionState.clearIfPeer(peripheral.address)
            MessageBus.clear()
        }

        override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
            Timber.e("failed to connect to '${peripheral.name}'")
        }

        override fun onBluetoothAdapterStateChanged(state: Int) {
            Timber.i("bluetooth adapter changed state to %d", state)
        }
    }

    // ── Scanning ───────────────────────────────────────────────────────────────

    fun startScanning() {
        if (centralManager.isNotScanning) {
            centralManager.scanForPeripheralsWithServices(setOf(HRS_SERVICE_UUID))
        }
    }

    /** Extracts our stable 8-byte appId from the scan response service data. */
    private fun extractAppId(scanResult: ScanResult): String? {
        val bytes = scanResult.scanRecord?.getServiceData(HRS_SERVICE_PARCEL_UUID) ?: return null
        if (bytes.size < 8) return null
        return bytes.copyOfRange(0, 8).joinToString("") { "%02x".format(it) }
    }

    // ── Initialization ─────────────────────────────────────────────────────────

    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        Timber.plant(Timber.DebugTree())
        Timber.i("initializing BluetoothHandler")

        handlerThread.start()
        handler = Handler(handlerThread.looper)

        this.context = context.applicationContext
        this.centralManager = BluetoothCentralManager(this.context, bluetoothCentralManagerCallback, handler)
    }
}
