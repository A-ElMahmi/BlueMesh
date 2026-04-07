package com.example.blessed3

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.welie.blessed.BluetoothPeripheral
import java.util.Collections
import java.util.UUID

/**
 * Second-layer mesh relay system. Completely independent of the direct-chat BLE connection.
 *
 * Flow:
 *   A.send(destId, content)
 *     → floods to all visible neighbors (except those already in seenBy)
 *   B.onReceived(packet)
 *     → if B == dest  → Toast the message
 *     → else if idle  → flood further (seenBy prevents looping back)
 */
@SuppressLint("StaticFieldLeak")
object RelayManager {

    private lateinit var context: Context
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Message IDs we have already processed — prevents re-forwarding the same packet. */
    private val seenMessageIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Originate a relay message from this device toward [destinationAppId].
     * The caller is responsible for adding the message to the local UI first.
     */
    fun send(destinationAppId: String, content: String) {
        val packet = RelayPacket(
            messageId = UUID.randomUUID().toString(),
            originAppId = DeviceIdentity.appId,
            destinationAppId = destinationAppId,
            content = content,
            seenBy = listOf(DeviceIdentity.appId)
        )
        seenMessageIds.add(packet.messageId)
        floodToNeighbors(packet)
    }

    /**
     * Called by HeartRateService / BluetoothHandler whenever a TYPE_RELAY packet arrives.
     */
    fun onReceived(packet: RelayPacket) {
        if (packet.messageId in seenMessageIds) return
        seenMessageIds.add(packet.messageId)

        if (packet.destinationAppId == DeviceIdentity.appId) {
            val senderName = KnownPeers.getAll()
                .find { it.appId == packet.originAppId }
                ?.displayName
                ?: packet.originAppId
            mainHandler.post {
                Toast.makeText(
                    context,
                    "Relay msg from $senderName: ${packet.content}",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        // Only relay forward when not in an active direct-chat session
        if (!MessagingConnectionState.isConnected) {
            floodToNeighbors(packet)
        }
    }

    // ── Flooding ───────────────────────────────────────────────────────────────

    private fun floodToNeighbors(packet: RelayPacket) {
        // Add ourselves to seenBy so recipients know not to send it back to us
        val updated = packet.copy(seenBy = (packet.seenBy + DeviceIdentity.appId).distinct())
        val bleBytes = BlePacket(BlePacket.TYPE_RELAY, updated.toJson()).toBytes()

        BluetoothHandler.scanForRelayNeighbors { neighbors ->
            val targets = neighbors
                .filter { (_, appId) -> appId == null || appId !in updated.seenBy }
                .map { it.first }
            connectSequentially(targets, bleBytes)
        }
    }

    /** Connect to each peripheral one at a time, write the packet, then move to the next. */
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
