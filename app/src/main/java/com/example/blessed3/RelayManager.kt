package com.example.blessed3

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.welie.blessed.BluetoothPeripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.UUID

/**
 * Single-hop relay + internet bridge, both directions.
 *
 * Forward (A→server via B):
 *   BleMessaging → flood() → BLE write to neighbors → B.onReceived() → POST to server
 *
 * Reverse (server→A via B):
 *   B.deliverPendingFromServer() → poll /relay-pending → scan BLE → deliver to A → confirm
 *
 * Receive (A gets a relay packet):
 *   HeartRateService → onReceived() → toast if destination is us, else bridge to server
 */
@SuppressLint("StaticFieldLeak")
object RelayManager {

    private const val TAG = "RelayMgr"
    private lateinit var context: Context
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private val seenMessageIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    // ── Forward: A has no internet, flood to BLE neighbors ──────────────────

    fun flood(destinationAppId: String, content: String, messageId: String = UUID.randomUUID().toString()) {
        Log.d(TAG, "flood msgId=$messageId dest=$destinationAppId")
        val packet = RelayPacket(
            messageId = messageId,
            originAppId = DeviceIdentity.appId,
            destinationAppId = destinationAppId.lowercase(),
            content = content
        )
        val bleBytes = BlePacket(BlePacket.TYPE_RELAY, packet.toJson()).toBytes()

        BluetoothHandler.scanForRelayNeighbors { neighbors ->
            val targets = neighbors.map { it.first }
            Log.d(TAG, "flood found ${targets.size} neighbor(s)")
            connectSequentially(targets, bleBytes)
        }
    }

    // ── Receive: packet arrived over BLE ────────────────────────────────────

    fun onReceived(packet: RelayPacket) {
        if (packet.messageId in seenMessageIds) return
        seenMessageIds.add(packet.messageId)

        if (packet.destinationAppId.lowercase() == DeviceIdentity.appId.lowercase()) {
            Log.d(TAG, "onReceived → for us from ${packet.originAppId} (msgId=${packet.messageId})")
            ChatHistoryRepository.appendInbound(
                senderAppId = packet.originAppId,
                text = packet.content,
                dedupeKey = packet.messageId
            )
            return
        }

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

    // ── Reverse: B pulls pending messages from server, delivers via BLE ─────

    fun deliverPendingFromServer() {
        scope.launch {
            val pending = ServerClient.pollRelayPending()
            if (pending.isEmpty()) return@launch
            Log.d(TAG, "deliverPending: ${pending.size} message(s) from server")

            BluetoothHandler.scanForRelayNeighbors { neighbors ->
                val nearbyByAppId = neighbors
                    .filter { (_, appId) -> appId != null }
                    .associate { (peripheral, appId) -> appId!! to peripheral }
                Log.d(TAG, "deliverPending: ${nearbyByAppId.size} neighbor(s) with appId")

                val deliverable = pending.filter { it.to in nearbyByAppId }
                if (deliverable.isEmpty()) {
                    Log.d(TAG, "deliverPending: no matching targets nearby")
                    return@scanForRelayNeighbors
                }
                Log.d(TAG, "deliverPending: ${deliverable.size} deliverable message(s)")

                val jobs = deliverable.map { msg ->
                    val relay = RelayPacket(msg.messageId, msg.from, msg.to, msg.content)
                    val bytes = BlePacket(BlePacket.TYPE_RELAY, relay.toJson()).toBytes()
                    Triple(nearbyByAppId[msg.to]!!, bytes, msg.messageId)
                }
                deliverSequentially(jobs)
            }
        }
    }

    // ── Sequential BLE delivery helpers ─────────────────────────────────────

    private fun connectSequentially(peripherals: List<BluetoothPeripheral>, bytes: ByteArray) {
        if (peripherals.isEmpty()) return
        val queue = ArrayDeque(peripherals)

        fun next() {
            val peripheral = queue.removeFirstOrNull() ?: return
            BluetoothHandler.connectForRelay(peripheral, bytes) { next() }
        }
        next()
    }

    private fun deliverSequentially(jobs: List<Triple<BluetoothPeripheral, ByteArray, String>>) {
        if (jobs.isEmpty()) return
        val queue = ArrayDeque(jobs)

        fun next() {
            val (peripheral, bytes, messageId) = queue.removeFirstOrNull() ?: return
            BluetoothHandler.connectForRelay(peripheral, bytes) {
                scope.launch {
                    ServerClient.confirmRelayDelivery(messageId)
                    mainHandler.post { next() }
                }
            }
        }
        next()
    }
}
