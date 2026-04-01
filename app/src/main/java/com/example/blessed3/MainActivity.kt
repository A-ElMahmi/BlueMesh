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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.blessed3.ui.theme.Blessed3Theme
import com.welie.blessed.BluetoothPeripheral
import timber.log.Timber

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())

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

                DeviceListScreen(
                    onScanClick = { restartScanning() },
                    onConnectConfirmed = { peripheral -> connectToPeripheral(peripheral) }
                )
            }
        }
    }

    // ── Device List Screen ─────────────────────────────────────────────────────

    @Composable
    fun DeviceListScreen(
        onScanClick: () -> Unit,
        onConnectConfirmed: (BluetoothPeripheral) -> Unit
    ) {
        val devices by BluetoothHandler.scannedDevices.collectAsState()
        var pendingDevice by remember { mutableStateOf<BluetoothPeripheral?>(null) }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("BLE Messenger", fontSize = 22.sp)
            Spacer(modifier = Modifier.height(8.dp))
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

    private fun restartScanning() {
        if (!BluetoothHandler.centralManager.isBluetoothEnabled) {
            enableBleRequest.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        if (BluetoothHandler.centralManager.permissionsGranted()) {
            BluetoothHandler.clearScannedDevices()
            BluetoothHandler.startScanning()
        } else {
            requestPermissions()
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
            if (result.resultCode == RESULT_OK) restartScanning()
        }

    private val isBluetoothEnabled: Boolean
        get() {
            val btManager = requireNotNull(applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            return requireNotNull(btManager.adapter).isEnabled
        }
}
