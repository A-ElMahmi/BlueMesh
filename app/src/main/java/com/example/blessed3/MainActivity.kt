package com.example.blessed3

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import com.example.blessed3.ui.theme.Blessed3Theme
import timber.log.Timber

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastAutoLaunchedIncomingPeer: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("NAVDBG MainActivity.onCreate taskId=$taskId instance=${System.identityHashCode(this)}")
        setContent {
            Blessed3Theme {
                val connectionState by MessagingConnectionState.state.collectAsState(initial = null)

                // Only open ChatActivity for *incoming* connections (we are the peripheral).
                // Outgoing connections are handled inside PeerMessagesActivity itself.
                LaunchedEffect(connectionState) {
                    Timber.d(
                        "NAVDBG MainActivity.LaunchedEffect connectionState role=${connectionState?.role} " +
                            "peerAppId=${connectionState?.peerAppId} peerAddress=${connectionState?.peerAddress}"
                    )
                    if (connectionState?.role != MessagingConnectionState.Role.WE_ARE_PERIPHERAL) {
                        lastAutoLaunchedIncomingPeer = null
                        return@LaunchedEffect
                    }

                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        Timber.d("NAVDBG MainActivity skip auto-launch (not RESUMED)")
                        return@LaunchedEffect
                    }

                    val peerKey = connectionState?.peerAddress ?: "UNKNOWN"
                    if (lastAutoLaunchedIncomingPeer == peerKey) {
                        Timber.d("NAVDBG MainActivity skip duplicate auto-launch for peer=$peerKey")
                        return@LaunchedEffect
                    }

                    lastAutoLaunchedIncomingPeer = peerKey
                    Timber.d("NAVDBG MainActivity launching ChatActivity from LaunchedEffect")
                    startActivity(
                        Intent(this@MainActivity, ChatActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                }

                ChatsScreen()
            }
        }
    }

    // ── Chats Screen ───────────────────────────────────────────────────────────

    @Composable
    private fun ChatsScreen() {
        val peers by KnownPeers.peersFlow.collectAsState()

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
                                .clickable { openPeerMessages(peer) }
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
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    private fun openPeerMessages(peer: KnownPeer) {
        Timber.d("NAVDBG MainActivity.openPeerMessages appId=${peer.appId} name=${peer.displayName}")
        val intent = Intent(this, PeerMessagesActivity::class.java).apply {
            putExtra(PeerMessagesActivity.EXTRA_PEER_APP_ID, peer.appId)
            putExtra(PeerMessagesActivity.EXTRA_PEER_DISPLAY_NAME, peer.displayName)
        }
        startActivity(intent)
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

    // ── BLE advertising ────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        Timber.d("NAVDBG MainActivity.onResume taskId=$taskId instance=${System.identityHashCode(this)}")
        startAdvertising()
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

    private var permissionRequestInProgress = false
    private val blePermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissionRequestInProgress = false
            permissions.entries.forEach { Timber.d("Permission ${it.key} = ${it.value}") }
        }

    private val enableBleRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startAdvertising()
            }
        }

    private val isBluetoothEnabled: Boolean
        get() {
            val btManager = requireNotNull(applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            return requireNotNull(btManager.adapter).isEnabled
        }
}
