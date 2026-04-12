package com.example.blessed3

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import com.example.blessed3.ui.theme.Blessed3Theme
import com.welie.blessed.BluetoothPeripheral
import timber.log.Timber

/**
 * Discover screen: scan for brand-new devices never chatted with before.
 * After a successful connection, ChatActivity is launched and this Activity
 * finishes so the back stack returns to MainActivity (Chats).
 */
class DiscoverActivity : ComponentActivity() {
    private var hasAutoLaunchedChat = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Blessed3Theme {
                val connectionState by MessagingConnectionState.state.collectAsState(initial = null)

                LaunchedEffect(connectionState) {
                    if (connectionState == null) {
                        hasAutoLaunchedChat = false
                        return@LaunchedEffect
                    }
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        Timber.d("NAVDBG DiscoverActivity skip auto-launch (not RESUMED)")
                        return@LaunchedEffect
                    }
                    if (hasAutoLaunchedChat) {
                        Timber.d("NAVDBG DiscoverActivity skip duplicate auto-launch")
                        return@LaunchedEffect
                    }

                    hasAutoLaunchedChat = true
                    Timber.d("NAVDBG DiscoverActivity launching ChatActivity from LaunchedEffect")
                    startActivity(
                        Intent(this@DiscoverActivity, ChatActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                    finish()
                }

                DeviceListScreen(
                    onScanClick = { restartScanning() },
                    onConnectConfirmed = { peripheral -> connectToPeripheral(peripheral) }
                )
            }
        }
    }

    @Composable
    private fun DeviceListScreen(
        onScanClick: () -> Unit,
        onConnectConfirmed: (BluetoothPeripheral) -> Unit
    ) {
        val devices by BluetoothHandler.scannedDevices.collectAsState()
        var pendingDevice by remember { mutableStateOf<BluetoothPeripheral?>(null) }
        var manualId by remember { mutableStateOf("") }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Discover Devices", fontSize = 22.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "My ID: ${DeviceIdentity.appId}",
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = manualId,
                    onValueChange = { manualId = it.lowercase().filter { c -> c in '0'..'9' || c in 'a'..'f' }.take(8) },
                    label = { Text("Peer ID") },
                    placeholder = { Text("8-char hex") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { connectToManualId(manualId) },
                    enabled = manualId.length == 8
                ) {
                    Text("Connect")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Button(onClick = onScanClick, modifier = Modifier.fillMaxWidth()) {
                Text("Scan")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Found devices (${devices.size}):", fontSize = 14.sp)
            Spacer(modifier = Modifier.height(4.dp))

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(devices, key = { it.address }) { peripheral ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { pendingDevice = peripheral }
                            .padding(vertical = 12.dp, horizontal = 4.dp)
                    ) {
                        Text(peripheral.name.ifBlank { "Unknown" }, fontSize = 16.sp)
                        Text(peripheral.address, fontSize = 12.sp)
                    }
                    HorizontalDivider()
                }
            }
        }

        pendingDevice?.let { peripheral ->
            AlertDialog(
                onDismissRequest = { pendingDevice = null },
                title = { Text("Connect?") },
                text = { Text("Connect to ${peripheral.name.ifBlank { peripheral.address }}?") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDevice = null
                        onConnectConfirmed(peripheral)
                    }) { Text("Yes") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDevice = null }) { Text("No") }
                }
            )
        }
    }

    private fun connectToManualId(appId: String) {
        KnownPeers.save(KnownPeer(appId = appId, displayName = appId, lastSeenMs = System.currentTimeMillis()))
        Toast.makeText(this, "Peer saved. Scanning…", Toast.LENGTH_SHORT).show()
        MessagingConnectionState.setConnectedAsInternet(appId, appId)
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

    private fun restartScanning() {
        if (!BluetoothHandler.centralManager.isBluetoothEnabled) {
            enableBleRequest.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        if (BluetoothHandler.centralManager.permissionsGranted()) {
            BluetoothHandler.clearScannedDevices()
            BluetoothHandler.startScanning()
        } else {
            requestScanPermissions()
        }
    }

    private fun requestScanPermissions() {
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
            if (result.resultCode == RESULT_OK) restartScanning()
        }

}
