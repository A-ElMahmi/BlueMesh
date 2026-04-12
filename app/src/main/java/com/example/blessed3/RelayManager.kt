package com.example.blessed3

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.welie.blessed.BluetoothPeripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Single-hop relay + internet bridge.
 *
 * Sender path (no internet):
 *   BleMessaging → RelayManager.flood()
 *     → BLE write to every visible neighbor (sequentially)
 *
 * Bridge path (neighbor that receives a relay packet and has internet):
 *   HeartRateService/BluetoothHandler → RelayManager.onReceived()
 *     → POST to server
 *     → if no internet, drop (single hop only)
 */
@SuppressLint("StaticFieldLeak")
object RelayManager {

    private const val TAG = "RelayMgr"
    private lateinit var context: Context
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * Originate a relay flood from this device (which has no internet).
     * Builds a [RelayPacket] and writes it to all visible BLE neighbors sequentially.
     */
    fun flood(destinationAppId: String, content: String, messageId: String = UUID.randomUUID().toString()) {
        Log.d(TAG, "flood msgId=$messageId dest=$destinationAppId")
        val packet = RelayPacket(
            messageId = messageId,
            originAppId = DeviceIdentity.appId,
            destinationAppId = destinationAppId,
            content = content,
            seenBy = emptyList()
        )
        val bleBytes = BlePacket(BlePacket.TYPE_RELAY, packet.toJson()).toBytes()

        BluetoothHandler.scanForRelayNeighbors { neighbors ->
            val targets = neighbors.map { it.first }
            Log.d(TAG, "flood found ${targets.size} neighbor(s)")
            connectSequentially(targets, bleBytes)
        }
    }

    /**
     * Called when this device receives a relay packet over BLE.
     * If we have internet → forward to server. Otherwise drop.
     */
    fun onReceived(packet: RelayPacket) {
        if (NetworkUtils.hasInternet(context)) {
            Log.d(TAG, "onReceived → forwarding to server (msgId=${packet.messageId})")
            scope.launch {
                ServerClient.postMessage(
                    messageId = packet.messageId,
                    from = packet.originAppId,
                    to = packet.destinationAppId,
                    content = packet.content
                )
            }
        } else {
            Log.d(TAG, "onReceived → dropped (no internet, msgId=${packet.messageId})")
        }
    }

    private fun connectSequentially(peripherals: List<BluetoothPeripheral>, bytes: ByteArray) {
        if (peripherals.isEmpty()) return
        val queue = ArrayDeque(peripherals)

        fun next() {
            val peripheral = queue.removeFirstOrNull() ?: return
            BluetoothHandler.connectForRelay(peripheral, bytes) { next() }
        }
        next()
    }
}
