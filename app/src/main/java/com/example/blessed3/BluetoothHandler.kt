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
import java.util.Collections


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

    private val SERVICE_PARCEL_UUID = ParcelUuid(BleService.SERVICE_UUID)

    private lateinit var peripheralGlobal: BluetoothPeripheral

    @Volatile private var disconnectAfterWrite = false

    /** Maps BLE address → stable appId extracted from scan response service data. */
    private val addressToAppId = mutableMapOf<String, String>()

    fun getAppIdForAddress(address: String): String? = addressToAppId[address.uppercase()]

    // ── Scan-for-peer state ────────────────────────────────────────────────────

    @Volatile private var targetAppId: String? = null
    private var onPeerFound: ((BluetoothPeripheral) -> Unit)? = null
    private var onPeerNotFound: (() -> Unit)? = null
    private var scanForPeerJob: Job? = null

    fun scanForPeer(
        appId: String,
        timeoutMs: Long = 3_000,
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

    // ── Relay infrastructure ───────────────────────────────────────────────────

    private data class RelayJob(val bytes: ByteArray, val onDone: () -> Unit)

    /** Keyed by uppercase BLE address. Presence = this is a relay (not a chat) connection. */
    private val activeRelayJobs: MutableMap<String, RelayJob> =
        Collections.synchronizedMap(mutableMapOf())

    /** Separate scan accumulator so relay scans don't disturb the DiscoverActivity list. */
    @Volatile private var relayScanning = false
    private val relayScanResults: MutableList<Pair<BluetoothPeripheral, String?>> =
        Collections.synchronizedList(mutableListOf())

    /**
     * Scan for ~[durationMs] ms, then call [onComplete] with (peripheral, appId?) pairs.
     * Results are independent of [scannedDevices].
     */
    fun scanForRelayNeighbors(
        durationMs: Long = 5_000,
        onComplete: (List<Pair<BluetoothPeripheral, String?>>) -> Unit
    ) {
        relayScanResults.clear()
        relayScanning = true
        startScanning()

        scope.launch {
            delay(durationMs)
            centralManager.stopScan()
            relayScanning = false
            val results = relayScanResults.toList()
            mainHandler.post { onComplete(results) }
        }
    }

    /**
     * Connect to [peripheral] solely to write [bytes] to the relay characteristic, then disconnect.
     * Does NOT touch [MessagingConnectionState]. Calls [onDone] after the peripheral disconnects.
     */
    fun connectForRelay(
        peripheral: BluetoothPeripheral,
        bytes: ByteArray,
        onDone: () -> Unit
    ) {
        activeRelayJobs[peripheral.address.uppercase()] = RelayJob(bytes, onDone)
        centralManager.connect(peripheral, relayPeripheralCallback)
    }

    /** Peripheral callback used exclusively for relay hops — never touches MessagingConnectionState. */
    private val relayPeripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
            val job = activeRelayJobs[peripheral.address.uppercase()] ?: return
            peripheral.writeCharacteristic(
                BleService.SERVICE_UUID,
                BleService.MEASUREMENT_CHARACTERISTIC_UUID,
                job.bytes,
                WITH_RESPONSE
            )
        }

        override fun onCharacteristicWrite(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            // Disconnect regardless of status so the queue can advance
            centralManager.cancelConnection(peripheral)
        }
    }

    // ── Normal peripheral callback ─────────────────────────────────────────────

    val bluetoothPeripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
            if (peripheral.address.uppercase() in activeRelayJobs) return
            peripheralGlobal = peripheral
            peripheral.requestConnectionPriority(ConnectionPriority.HIGH)
            peripheral.requestMtu(512)
            peripheral.startNotify(BleService.SERVICE_UUID, BleService.MEASUREMENT_CHARACTERISTIC_UUID)
            val handshake = BlePacket(BlePacket.TYPE_HANDSHAKE, DeviceIdentity.appId).toBytes()
            peripheral.writeCharacteristic(BleService.SERVICE_UUID, BleService.MEASUREMENT_CHARACTERISTIC_UUID, handshake, WITH_RESPONSE)
        }

        override fun onCharacteristicWrite(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            if (peripheral.address.uppercase() in activeRelayJobs) return
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
            if (peripheral.address.uppercase() in activeRelayJobs) return
            when (characteristic.uuid) {
                BleService.MEASUREMENT_CHARACTERISTIC_UUID -> {
                    Log.d("BleMsg", "CENTRAL recv ${value.size} bytes from ${peripheral.address}")
                    val packet = BlePacket.fromBytes(value) ?: run {
                        Log.d("BleMsg", "CENTRAL recv: failed to parse packet")
                        return
                    }
                    Log.d("BleMsg", "CENTRAL recv type=${packet.type} body=\"${packet.body}\"")
                    when (packet.type) {
                        BlePacket.TYPE_MSG -> {
                            val from = MessagingConnectionState.currentPeer?.peerAppId
                            if (from != null) {
                                ChatHistoryRepository.appendInbound(from, packet.body, dedupeKey = null)
                            }
                        }
                        BlePacket.TYPE_RELAY -> {
                            val relay = RelayPacket.fromJson(packet.body)
                            if (relay != null) RelayManager.onReceived(relay)
                        }
                        BlePacket.TYPE_DISCONNECT -> {
                            MessagingConnectionState.clear()
                        }
                    }
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
            }
        }
    }

    fun sendBytes(bytes: ByteArray) {
        val peer = MessagingConnectionState.currentPeer ?: return
        val peripheral = centralManager.getConnectedPeripherals().find {
            it.address.equals(peer.peerAddress, ignoreCase = true)
        } ?: return
        peripheral.writeCharacteristic(BleService.SERVICE_UUID, BleService.MEASUREMENT_CHARACTERISTIC_UUID, bytes, WITH_RESPONSE)
    }

    fun disconnectAsCentral() {
        val peer = MessagingConnectionState.currentPeer ?: return
        val peripheral = centralManager.getConnectedPeripherals().find {
            it.address.equals(peer.peerAddress, ignoreCase = true)
        } ?: return
        centralManager.cancelConnection(peripheral)
    }

    fun forceDisconnectCentral() {
        val peer = MessagingConnectionState.currentPeer ?: return
        if (peer.role != MessagingConnectionState.Role.WE_ARE_CENTRAL) return
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
                addressToAppId[peripheral.address.uppercase()] = appId
            }

            // Feed relay scan accumulator when a relay scan is active
            if (relayScanning) {
                val addr = peripheral.address
                if (relayScanResults.none { it.first.address == addr }) {
                    relayScanResults.add(Pair(peripheral, appId))
                }
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
            // Relay connections are handled entirely by relayPeripheralCallback — skip state
            if (peripheral.address.uppercase() in activeRelayJobs) return

            val appId = addressToAppId[peripheral.address.uppercase()]
            MessagingConnectionState.setConnectedAsCentral(
                peerAddress = peripheral.address,
                peerName = peripheral.name.ifBlank { "" },
                peerAppId = appId
            )
        }

        override fun onDisconnected(peripheral: BluetoothPeripheral, status: HciStatus) {
            Timber.i("disconnected '${peripheral.name}'")
            val addr = peripheral.address.uppercase()
            val relayJob = activeRelayJobs.remove(addr)
            if (relayJob != null) {
                relayJob.onDone()
                return
            }
            MessagingConnectionState.clearIfPeer(peripheral.address)
        }

        override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
            Timber.e("failed to connect to '${peripheral.name}'")
            val addr = peripheral.address.uppercase()
            val relayJob = activeRelayJobs.remove(addr)
            if (relayJob != null) {
                // Still advance the queue even on failure
                relayJob.onDone()
            }
        }

        override fun onBluetoothAdapterStateChanged(state: Int) {
            Timber.i("bluetooth adapter changed state to %d", state)
        }
    }

    // ── Scanning ───────────────────────────────────────────────────────────────

    fun startScanning() {
        if (centralManager.isNotScanning) {
            centralManager.scanForPeripheralsWithServices(setOf(BleService.SERVICE_UUID))
        }
    }

    private fun extractAppId(scanResult: ScanResult): String? {
        val bytes = scanResult.scanRecord?.getServiceData(SERVICE_PARCEL_UUID) ?: return null
        if (bytes.size < 4) return null
        return bytes.copyOfRange(0, 4).joinToString("") { "%02x".format(it) }
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
