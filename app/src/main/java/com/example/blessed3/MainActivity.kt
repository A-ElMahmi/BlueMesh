package com.example.blessed3

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.blessed3.ui.theme.Blessed3Theme
import com.welie.blessed.BluetoothPeripheral
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())

    // Scan-for-peer UI state kept as Activity-level flows so we can reset on pause.
    private val _scanningPeer = MutableStateFlow<KnownPeer?>(null)
    private val _pendingConnection = MutableStateFlow<Pair<KnownPeer, BluetoothPeripheral>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Blessed3Theme {
                val connectionState by MessagingConnectionState.state.collectAsState(initial = null)

                LaunchedEffect(connectionState != null) {
                    if (connectionState != null) {
                        startActivity(Intent(this@MainActivity, ChatActivity::class.java))
                    }
                }

                ChatsScreen()
            }
        }
    }

    // ── Chats Screen ───────────────────────────────────────────────────────────

    @Composable
    private fun ChatsScreen() {
        val peers by KnownPeers.peersFlow.collectAsState()
        val scanningPeer by _scanningPeer.asStateFlow().collectAsState()
        val pendingConnection by _pendingConnection.asStateFlow().collectAsState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Chats", fontSize = 26.sp)
                Button(onClick = {
                    startActivity(Intent(this@MainActivity, DiscoverActivity::class.java))
                }) {
                    Text("Discover")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (peers.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No contacts yet", fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Tap Discover to find nearby devices", fontSize = 13.sp)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(peers, key = { it.appId }) { peer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = scanningPeer == null) {
                                    startScanForPeer(peer)
                                }
                                .padding(vertical = 14.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(peer.displayName, fontSize = 17.sp)
                                Text(
                                    text = formatLastSeen(peer.lastSeenMs),
                                    fontSize = 12.sp
                                )
                            }
                            if (scanningPeer?.appId == peer.appId) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 4.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        // Confirm dialog once the targeted scan finds the peer
        pendingConnection?.let { (peer, peripheral) ->
            AlertDialog(
                onDismissRequest = { _pendingConnection.value = null },
                title = { Text("Connect?") },
                text = { Text("Connect to ${peer.displayName}?") },
                confirmButton = {
                    TextButton(onClick = {
                        _pendingConnection.value = null
                        connectToPeripheral(peripheral)
                    }) { Text("Yes") }
                },
                dismissButton = {
                    TextButton(onClick = { _pendingConnection.value = null }) { Text("No") }
                }
            )
        }
    }

    private fun startScanForPeer(peer: KnownPeer) {
        _scanningPeer.value = peer
        BluetoothHandler.scanForPeer(
            appId = peer.appId,
            onFound = { peripheral ->
                _scanningPeer.value = null
                _pendingConnection.value = Pair(peer, peripheral)
            },
            onNotFound = {
                _scanningPeer.value = null
                Toast.makeText(this, "${peer.displayName} is out of range", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun formatLastSeen(ms: Long): String {
        val diff = System.currentTimeMillis() - ms
        return when {
            diff < 60_000L -> "just now"
            diff < 3_600_000L -> "${diff / 60_000}m ago"
            diff < 86_400_000L -> "${diff / 3_600_000}h ago"
            else -> "${diff / 86_400_000}d ago"
        }
    }

    // ── BLE helpers ────────────────────────────────────────────────────────────

    private fun connectToPeripheral(peripheral: BluetoothPeripheral) {
        BluetoothHandler.clearScannedDevices()
        val cm = BluetoothHandler.centralManager
        cm.stopScan()
        if (cm.getConnectedPeripherals().any { it.address == peripheral.address } ||
            cm.unconnectedPeripherals.containsKey(peripheral.address)
        ) {
            cm.cancelConnection(peripheral)
        }
        cm.connect(peripheral, BluetoothHandler.bluetoothPeripheralCallback)
    }

    override fun onResume() {
        super.onResume()
        startAdvertising()
    }

    override fun onPause() {
        super.onPause()
        // Cancel any in-progress targeted scan so it doesn't surface stale results later.
        BluetoothHandler.cancelScanForPeer()
        _scanningPeer.value = null
        _pendingConnection.value = null
    }

    private fun startAdvertising() {
        val bluetoothServer = BluetoothServer.getInstance(applicationContext)
        val peripheralManager = bluetoothServer.peripheralManager

        if (!peripheralManager.permissionsGranted()) {
            requestPermissions2()
            return
        }
        if (!isBluetoothEnabled) {
            enableBleRequest.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        if (!bluetoothServer.isInitialized) {
            bluetoothServer.initialize()
        }
        if (!peripheralManager.isAdvertising) {
            handler.postDelayed({ bluetoothServer.startAdvertising() }, 500)
        }
    }

    private fun requestPermissions2() {
        val missing = BluetoothServer.getInstance(applicationContext).peripheralManager.getMissingPermissions()
        if (missing.isNotEmpty() && !permissionRequestInProgress) {
            permissionRequestInProgress = true
            blePermissionRequest.launch(missing)
        }
    }

    private fun requestPermissions() {
        val missing = BluetoothHandler.centralManager.getMissingPermissions()
        if (missing.isNotEmpty() && !permissionRequestInProgress) {
            permissionRequestInProgress = true
            blePermissionRequest.launch(missing)
        }
    }

    private var permissionRequestInProgress = false
    private val blePermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissionRequestInProgress = false
            permissions.entries.forEach { Timber.d("Permission ${it.key} = ${it.value}") }
        }

    private val enableBleRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // Bluetooth just got enabled; re-attempt advertising
                startAdvertising()
            }
        }

    private val isBluetoothEnabled: Boolean
        get() {
            val btManager = requireNotNull(applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            return requireNotNull(btManager.adapter).isEnabled
        }
}
