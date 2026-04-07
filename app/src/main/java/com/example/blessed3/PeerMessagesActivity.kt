package com.example.blessed3

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.blessed3.ui.theme.Blessed3Theme
import com.welie.blessed.BluetoothPeripheral
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Chat screen opened whenever the user taps a KnownPeer from the contacts list.
 *
 * Opens immediately — no waiting for a BLE connection. In the background it scans
 * for the peer and attempts a direct connection. While that resolves:
 *   - Messages typed before connection are queued locally.
 * Once resolved:
 *   - CONNECTED: queued messages are flushed via direct BLE; subsequent sends use BleMessaging.
 *   - FAILED:    queued messages are flushed via RelayManager; subsequent sends use RelayManager.
 */
class PeerMessagesActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PEER_APP_ID = "PEER_APP_ID"
        const val EXTRA_PEER_DISPLAY_NAME = "PEER_DISPLAY_NAME"
    }

    private val peerAppId: String by lazy {
        intent.getStringExtra(EXTRA_PEER_APP_ID) ?: ""
    }
    private val peerDisplayName: String by lazy {
        intent.getStringExtra(EXTRA_PEER_DISPLAY_NAME) ?: "Unknown"
    }

    private enum class AttemptState { PENDING, CONNECTED, FAILED }

    private val _attemptState = MutableStateFlow(AttemptState.PENDING)

    /** Messages typed before the connection attempt resolves. */
    private val pendingQueue = mutableListOf<String>()

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d(
            "NAVDBG PeerMessagesActivity.onCreate taskId=$taskId instance=${System.identityHashCode(this)} " +
                "peerAppId=$peerAppId peerName=$peerDisplayName"
        )
        MessageBus.clear()
        startScanAndConnect()

        setContent {
            Blessed3Theme {
                val connectionState by MessagingConnectionState.state.collectAsState()
                val attemptState by _attemptState.asStateFlow().collectAsState()
                val messages by MessageBus.messages.collectAsState()

                // Transition PENDING → CONNECTED once direct BLE is up for our peer
                LaunchedEffect(connectionState?.peerAppId) {
                    Timber.d(
                        "NAVDBG PeerMessagesActivity.LaunchedEffect state=${_attemptState.value} " +
                            "connRole=${connectionState?.role} connPeerAppId=${connectionState?.peerAppId} " +
                            "targetPeerAppId=$peerAppId"
                    )
                    val conn = connectionState ?: return@LaunchedEffect
                    if (_attemptState.value != AttemptState.PENDING) return@LaunchedEffect
                    if (conn.peerAppId == peerAppId) {
                        Timber.d("NAVDBG PeerMessagesActivity direct connection resolved for target peer")
                        flushPendingDirect(conn.role)
                        _attemptState.value = AttemptState.CONNECTED
                    }
                }

                PeerChatScreen(
                    peerName = peerDisplayName,
                    statusLabel = when (attemptState) {
                        AttemptState.PENDING -> "Connecting…"
                        AttemptState.CONNECTED -> "Connected"
                        AttemptState.FAILED -> "Relay mode"
                    },
                    messages = messages,
                    onSend = { text -> handleSend(text) },
                    onBack = {
                        Timber.d("NAVDBG PeerMessagesActivity.onBack clicked")
                        finish()
                    }
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Timber.d("NAVDBG PeerMessagesActivity.onPause instance=${System.identityHashCode(this)}")
        BluetoothHandler.cancelScanForPeer()
    }

    override fun onResume() {
        super.onResume()
        Timber.d("NAVDBG PeerMessagesActivity.onResume instance=${System.identityHashCode(this)}")
    }

    override fun onDestroy() {
        Timber.d("NAVDBG PeerMessagesActivity.onDestroy instance=${System.identityHashCode(this)} finishing=$isFinishing")
        super.onDestroy()
    }

    // ── Send logic ─────────────────────────────────────────────────────────────

    private fun handleSend(text: String) {
        Timber.d("NAVDBG PeerMessagesActivity.handleSend attemptState=${_attemptState.value} textLen=${text.length}")
        when (_attemptState.value) {
            AttemptState.PENDING -> {
                // Show in UI immediately; hold for flush once connection resolves
                MessageBus.add(ChatMessage(text, isFromMe = true))
                pendingQueue.add(text)
            }
            AttemptState.CONNECTED -> {
                BleMessaging.send(this, text)
            }
            AttemptState.FAILED -> {
                MessageBus.add(ChatMessage(text, isFromMe = true))
                RelayManager.send(peerAppId, text)
            }
        }
    }

    /**
     * Send all queued messages via the direct BLE connection.
     * The messages are already in the local UI so we bypass [BleMessaging.send] to
     * avoid adding them to [MessageBus] a second time.
     */
    private fun flushPendingDirect(role: MessagingConnectionState.Role) {
        val bytes = pendingQueue.map { BlePacket(BlePacket.TYPE_MSG, it).toBytes() }
        pendingQueue.clear()
        when (role) {
            MessagingConnectionState.Role.WE_ARE_CENTRAL ->
                bytes.forEach { BluetoothHandler.sendBytes(it) }
            MessagingConnectionState.Role.WE_ARE_PERIPHERAL -> {
                val server = BluetoothServer.getInstance(this)
                bytes.forEach { server.sendBytes(it) }
            }
        }
    }

    /** Send all queued messages via the relay mesh. */
    private fun flushPendingRelay() {
        val queued = pendingQueue.toList()
        pendingQueue.clear()
        queued.forEach { RelayManager.send(peerAppId, it) }
    }

    // ── Background scan / connect ──────────────────────────────────────────────

    private fun startScanAndConnect() {
        Timber.d("NAVDBG PeerMessagesActivity.startScanAndConnect targetPeerAppId=$peerAppId")
        BluetoothHandler.scanForPeer(
            appId = peerAppId,
            onFound = { peripheral -> connectToPeripheral(peripheral) },
            onNotFound = {
                Timber.d("NAVDBG PeerMessagesActivity.scanForPeer.onNotFound attemptState=${_attemptState.value}")
                if (_attemptState.value == AttemptState.PENDING) {
                    _attemptState.value = AttemptState.FAILED
                    flushPendingRelay()
                }
            }
        )
    }

    private fun connectToPeripheral(peripheral: BluetoothPeripheral) {
        Timber.d(
            "NAVDBG PeerMessagesActivity.connectToPeripheral address=${peripheral.address} " +
                "name=${peripheral.name}"
        )
        val cm = BluetoothHandler.centralManager
        cm.stopScan()
        if (cm.getConnectedPeripherals().any { it.address == peripheral.address } ||
            cm.unconnectedPeripherals.containsKey(peripheral.address)
        ) {
            cm.cancelConnection(peripheral)
        }
        cm.connect(peripheral, BluetoothHandler.bluetoothPeripheralCallback)
    }

    // ── Composable UI ──────────────────────────────────────────────────────────

    @Composable
    private fun PeerChatScreen(
        peerName: String,
        statusLabel: String,
        messages: List<ChatMessage>,
        onSend: (String) -> Unit,
        onBack: () -> Unit
    ) {
        var messageText by remember { mutableStateOf("") }
        val listState = rememberLazyListState()

        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(peerName, fontSize = 17.sp)
                    Text(statusLabel, fontSize = 12.sp, color = Color.Gray)
                }
                Button(onClick = onBack) { Text("Back") }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(messages) { msg ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        contentAlignment = if (msg.isFromMe) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        Text(
                            text = msg.text,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    label = { Text("Message") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    val text = messageText.trim()
                    if (text.isNotEmpty()) {
                        onSend(text)
                        messageText = ""
                    }
                }) {
                    Text("Send")
                }
            }
        }
    }
}
