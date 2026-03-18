package com.example.blessed3

import android.app.AlertDialog
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.blessed3.ui.theme.Blessed3Theme
import com.welie.blessed.BluetoothPeripheral
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Blessed3Theme {
                val connectionState by MessagingConnectionState.state.collectAsState(initial = null)
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()) {

                    Text(text = "Hi", fontSize = 24.sp)
                    connectionState?.let { connected ->
                        Text(
                            text = "Connected to ${connected.displayLabel()} (${connected.role.name})",
                            fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Button(onClick = {
                        if (MessagingConnectionState.isConnected) {
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "Already connected to ${connectionState?.displayLabel()}. Use this connection for messaging.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            return@Button
                        }
                        restartScanning()
                    }) {
                        Text("Scan")
                    }
                }
            }
        }

        lifecycleScope.launch {
            BluetoothHandler.connectRequestFlow.collect { peripheral ->
                showConnectDialog(peripheral)
            }
        }
    }

    private fun showConnectDialog(peripheral: BluetoothPeripheral) {
        if (MessagingConnectionState.isPeer(peripheral.address)) {
            AlertDialog.Builder(this)
                .setTitle("Already connected")
                .setMessage("You are already connected to ${peripheral.name.ifBlank { peripheral.address }}. Use this connection to send and receive messages — no new connection needed.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Device found")
            .setMessage("Connect to ${peripheral.name}?")
            .setPositiveButton("Yes") { _, _ ->
                val cm = BluetoothHandler.centralManager

                // If blessed still thinks we are connected or connecting to this peripheral,
                // cancel that first so a fresh connect() can succeed.
                if (cm.getConnectedPeripherals().any { it.address == peripheral.address } ||
                    cm.unconnectedPeripherals.containsKey(peripheral.address)
                ) {
                    cm.cancelConnection(peripheral)
                }

                cm.connect(
                    peripheral,
                    BluetoothHandler.bluetoothPeripheralCallback
                )
            }
            .setNegativeButton("No", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        startAdvertising()
//        restartScanning()
    }

    private fun startAdvertising() {
        println("MainActivity.startAdvertising()...")
        val bluetoothServer = BluetoothServer.getInstance(applicationContext)
        val peripheralManager = bluetoothServer.peripheralManager

        if (!peripheralManager.permissionsGranted()) {
            println("Requesting permission?")
            requestPermissions2()
            return
        }

        if (!isBluetoothEnabled) {
            println("BT not enabled?")
            enableBleRequest.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        // Make sure we initialize the server only once
        if (!bluetoothServer.isInitialized) {
            println("BT Server not init?")
            bluetoothServer.initialize()
        }

        println("BTServer init. Now startAdvertising after 500ms")

        // All good now, we can start advertising
        handler.postDelayed({
            bluetoothServer.startAdvertising()
        }, 500)
    }

    private fun restartScanning() {
        if (!BluetoothHandler.centralManager.isBluetoothEnabled) {
            enableBleRequest.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        if (BluetoothHandler.centralManager.permissionsGranted()) {
            println("Calling startScanning()...")
            BluetoothHandler.startScanning()
        } else {
            requestPermissions()
        }
    }

    private fun requestPermissions2() {
        val missingPermissions = BluetoothServer.getInstance(applicationContext).peripheralManager.getMissingPermissions()
        if (missingPermissions.isNotEmpty() && !permissionRequestInProgress) {
            permissionRequestInProgress = true
            blePermissionRequest.launch(missingPermissions)
        }
    }

    private fun requestPermissions() {
        val missingPermissions = BluetoothHandler.centralManager.getMissingPermissions()
        println("Any missing permissions? ${missingPermissions.joinToString()}")
        if (missingPermissions.isNotEmpty() && !permissionRequestInProgress) {
            permissionRequestInProgress = true
            blePermissionRequest.launch(missingPermissions)
        }
    }

    private var permissionRequestInProgress = false
    private val blePermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            println("Permissions result")
            permissionRequestInProgress = false
            permissions.entries.forEach {
                Timber.d("Permission ${it.key} = ${it.value}")
            }
        }

    private val enableBleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        println("enableBleRequest callback")
        if (result.resultCode == RESULT_OK) {
            restartScanning()
        }
    }

    private val isBluetoothEnabled: Boolean
        get() {
            val bluetoothManager: BluetoothManager = requireNotNull(applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager) { "cannot get BluetoothManager" }
            val bluetoothAdapter: BluetoothAdapter = requireNotNull(bluetoothManager.adapter) { "no bluetooth adapter found" }
            return bluetoothAdapter.isEnabled
        }
}