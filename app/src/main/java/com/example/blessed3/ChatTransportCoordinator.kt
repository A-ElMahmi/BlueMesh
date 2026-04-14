package com.example.blessed3

import android.app.Application
import android.content.Context
import com.welie.blessed.BluetoothPeripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * Background BLE/internet connect for the active chat, outbound queue, and BLE idle teardown.
 */
object ChatTransportCoordinator {

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val outboundQueue = Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    private var activeSessionPeerId: String? = null

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var stateCollectJob: Job? = null
    private var idleJob: Job? = null

    fun initialize(app: Application) {
        appContext = app.applicationContext
        stateCollectJob?.cancel()
        stateCollectJob = scope.launch {
            MessagingConnectionState.state.collect {
                flushOutboundQueue(appContext)
            }
        }
    }

    fun transportMatches(peerAppId: String, s: MessagingConnectionState.Connected?): Boolean {
        if (s == null) return false
        val id = peerAppId.lowercase()
        return when (s.role) {
            MessagingConnectionState.Role.WE_ARE_INTERNET ->
                s.peerAppId?.lowercase() == id
            MessagingConnectionState.Role.WE_ARE_CENTRAL,
            MessagingConnectionState.Role.WE_ARE_PERIPHERAL ->
                s.peerAppId?.lowercase() == id
        }
    }

    /** Status line for the chat header (Compose should also observe [MessagingConnectionState.state]). */
    fun statusLine(peerAppId: String): String {
        val id = peerAppId.lowercase()
        if (activeSessionPeerId != id) return "Not connected"
        if (_scanning.value) return "Connecting…"
        val s = MessagingConnectionState.currentPeer
        if (!transportMatches(id, s)) return "Not connected"
        return when (s!!.role) {
            MessagingConnectionState.Role.WE_ARE_INTERNET ->
                if (NetworkUtils.hasInternet(appContext)) "Connected via Wi‑Fi"
                else "Connected via relay (no Wi‑Fi)"
            MessagingConnectionState.Role.WE_ARE_CENTRAL,
            MessagingConnectionState.Role.WE_ARE_PERIPHERAL ->
                if (s.peerAppId == null) "Connecting…" else "Connected via BLE"
        }
    }

    fun startSession(peerAppId: String, displayName: String) {
        val id = peerAppId.lowercase()
        activeSessionPeerId = id
        BluetoothHandler.cancelScanForPeer()
        // Step 1: online → server route immediately (no BLE scan delay).
        if (NetworkUtils.hasInternet(appContext)) {
            _scanning.value = false
            MessagingConnectionState.setConnectedAsInternet(id, displayName)
            return
        }
        // Steps 2–3 offline: try direct BLE to destination; on timeout arm relay session (send → [RelayManager.flood]).
        _scanning.value = true
        BluetoothHandler.scanForPeer(
            appId = id,
            onFound = { peripheral ->
                _scanning.value = false
                connectToPeripheral(peripheral)
            },
            onNotFound = {
                _scanning.value = false
                MessagingConnectionState.setConnectedAsInternet(id, displayName)
            }
        )
    }

    private fun connectToPeripheral(peripheral: BluetoothPeripheral) {
        val cm = BluetoothHandler.centralManager
        cm.stopScan()
        if (cm.getConnectedPeripherals().any { it.address == peripheral.address } ||
            cm.unconnectedPeripherals.containsKey(peripheral.address)
        ) {
            cm.cancelConnection(peripheral)
        }
        cm.connect(peripheral, BluetoothHandler.bluetoothPeripheralCallback)
    }

    fun onChatClosed(peerAppId: String) {
        idleJob?.cancel()
        idleJob = null
        val id = peerAppId.lowercase()
        if (activeSessionPeerId == id) {
            synchronized(outboundQueue) { outboundQueue.clear() }
            BluetoothHandler.cancelScanForPeer()
            teardownTransportForPeer(id)
            activeSessionPeerId = null
        }
        _scanning.value = false
    }

    private fun teardownTransportForPeer(peerIdLower: String) {
        val s = MessagingConnectionState.currentPeer ?: return
        if (!transportMatches(peerIdLower, s)) return
        teardownAllTransport()
    }

    fun teardownAllTransport() {
        idleJob?.cancel()
        idleJob = null
        val s = MessagingConnectionState.currentPeer ?: return
        when (s.role) {
            MessagingConnectionState.Role.WE_ARE_INTERNET ->
                MessagingConnectionState.clear()
            MessagingConnectionState.Role.WE_ARE_CENTRAL -> {
                BluetoothHandler.forceDisconnectCentral()
                MessagingConnectionState.clear()
            }
            MessagingConnectionState.Role.WE_ARE_PERIPHERAL -> {
                BluetoothServer.getInstance(appContext).disconnectAsPeripheral()
                MessagingConnectionState.clear()
            }
        }
    }

    fun onUserSend(context: Context, peerAppId: String, text: String) {
        val id = peerAppId.lowercase()
        ChatHistoryRepository.appendOutbound(id, text)
        synchronized(outboundQueue) { outboundQueue.add(text) }
        if (transportMatches(id, MessagingConnectionState.currentPeer)) {
            flushOutboundQueue(context.applicationContext)
            return
        }
        if (MessagingConnectionState.currentPeer != null) {
            teardownAllTransport()
        }
        _scanning.value = true
        BluetoothHandler.cancelScanForPeer()
        BluetoothHandler.scanForPeer(
            appId = id,
            onFound = { peripheral ->
                _scanning.value = false
                connectToPeripheral(peripheral)
            },
            onNotFound = {
                _scanning.value = false
                val name = KnownPeers.getAll().find { it.appId == id }?.displayName ?: id
                MessagingConnectionState.setConnectedAsInternet(id, name)
                flushOutboundQueue(context.applicationContext)
            }
        )
    }

    private fun flushOutboundQueue(context: Context) {
        val peerId = activeSessionPeerId ?: return
        if (!transportMatches(peerId, MessagingConnectionState.currentPeer)) return
        while (true) {
            val text = synchronized(outboundQueue) {
                if (outboundQueue.isEmpty()) null else outboundQueue.removeAt(0)
            } ?: break
            val ok = BleMessaging.sendTransport(context, text)
            if (!ok) {
                synchronized(outboundQueue) { outboundQueue.add(0, text) }
                break
            }
            onAppPayloadActivity()
        }
    }

    fun onAppPayloadActivity() {
        val peerId = activeSessionPeerId ?: return
        val s = MessagingConnectionState.currentPeer ?: return
        if (s.role != MessagingConnectionState.Role.WE_ARE_CENTRAL &&
            s.role != MessagingConnectionState.Role.WE_ARE_PERIPHERAL
        ) {
            return
        }
        if (!transportMatches(peerId, s)) return
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(10_000)
            if (!ActiveChatPeer.isShowing(peerId)) return@launch
            if (transportMatches(peerId, MessagingConnectionState.currentPeer)) {
                val s2 = MessagingConnectionState.currentPeer ?: return@launch
                if (s2.role == MessagingConnectionState.Role.WE_ARE_CENTRAL ||
                    s2.role == MessagingConnectionState.Role.WE_ARE_PERIPHERAL
                ) {
                    teardownTransportForPeer(peerId)
                }
            }
        }
    }
}
