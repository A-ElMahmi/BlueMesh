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
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.blessed3.ui.theme.Blessed3Theme
import com.welie.blessed.BluetoothPeripheral
import timber.log.Timber

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        enableEdgeToEdge()
        setContent {
            Blessed3Theme {
                val connectionState by MessagingConnectionState.state.collectAsState(initial = null)

                if (connectionState == null) {
                    DeviceListScreen(
                        onScanClick = { restartScanning() },
                        onConnectConfirmed = { peripheral -> connectToPeripheral(peripheral) }
                    )
                } else {
                    ChatScreen(
                        peerLabel = connectionState!!.displayLabel(),
                        onDisconnect = { BleMessaging.disconnect(this@MainActivity) }
                    )
                }
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

        // Confirm-connect dialog
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

    // ── Chat Screen ────────────────────────────────────────────────────────────

    @Composable
    fun ChatScreen(peerLabel: String, onDisconnect: () -> Unit) {
        val messages by MessageBus.messages.collectAsState()
        var messageText by remember { mutableStateOf("") }
        val listState = rememberLazyListState()

        // Scroll to bottom whenever a new message arrives
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Chat with $peerLabel", fontSize = 16.sp)
                Button(onClick = onDisconnect) { Text("Disconnect") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Message list
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth()
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

            // Input row
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
                        BleMessaging.send(this@MainActivity, text)
                        messageText = ""
                    }
                }) {
                    Text("Send")
                }
            }
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
        handler.postDelayed({ bluetoothServer.startAdvertising() }, 500)
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
