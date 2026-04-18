package com.example.blessed3

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.blessed3.ui.theme.AppDarkGrey
import com.example.blessed3.ui.theme.BlueMesh3Theme
import com.welie.blessed.BluetoothPeripheral
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Discover screen: scan for brand-new devices never chatted with before.
 */
class DiscoverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var refreshCooldown by remember { mutableStateOf(false) }
            BlueMesh3Theme {
                DeviceListScreen(
                    refreshCooldown = refreshCooldown,
                    onRefreshScan = {
                        BluetoothHandler.stopScanning()
                        BluetoothHandler.clearScannedDevices()
                        refreshCooldown = true
                        lifecycleScope.launch {
                            delay(1_000)
                            if (BluetoothHandler.centralManager.isBluetoothEnabled &&
                                BluetoothHandler.centralManager.permissionsGranted()
                            ) {
                                BluetoothHandler.startScanning()
                            }
                            refreshCooldown = false
                        }
                    },
                    onContinueManual = { id -> connectToManualId(id) },
                    onConnectConfirmed = { peripheral -> openChatForPeripheral(peripheral) }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        attemptAutoScan()
    }

    override fun onDestroy() {
        BluetoothHandler.stopScanning()
        super.onDestroy()
    }

    private fun attemptAutoScan() {
        if (!BluetoothHandler.centralManager.isBluetoothEnabled) {
            enableBleRequest.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        if (!BluetoothHandler.centralManager.permissionsGranted()) {
            requestScanPermissions()
            return
        }
        BluetoothHandler.clearScannedDevices()
        BluetoothHandler.startScanning()
    }

    @Composable
    private fun DeviceListScreen(
        refreshCooldown: Boolean,
        onRefreshScan: () -> Unit,
        onContinueManual: (String) -> Unit,
        onConnectConfirmed: (BluetoothPeripheral) -> Unit
    ) {
        val devices by BluetoothHandler.scannedDevices.collectAsState()
        var pendingDevice by remember { mutableStateOf<BluetoothPeripheral?>(null) }
        var manualId by remember { mutableStateOf("") }
        val canContinue = manualId.length == 8

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Discover Devices", fontSize = 22.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "My ID: ${DeviceIdentity.appId}",
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color.White,
                shadowElevation = 0.dp,
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = manualId,
                        onValueChange = { manualId = it.lowercase().filter { c -> c in '0'..'9' || c in 'a'..'f' }.take(8) },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text("Peer ID", color = AppDarkGrey, fontSize = 16.sp)
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = AppDarkGrey,
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp)
                    )
                    Text(
                        text = "Continue",
                        fontSize = 16.sp,
                        color = if (canContinue) MaterialTheme.colorScheme.primary else AppDarkGrey,
                        modifier = Modifier
                            .clickable(enabled = canContinue) {
                                if (canContinue) onContinueManual(manualId)
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .widthIn(min = 72.dp),
                        textDecoration = TextDecoration.None
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Found devices (${devices.size}):",
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onRefreshScan,
                    enabled = !refreshCooldown
                ) {
                    Icon(
                        painter = painterResource(R.drawable.refresh_24dp_303030_fill0_wght400_grad0_opsz24),
                        contentDescription = "Refresh scan",
                        tint = if (refreshCooldown) AppDarkGrey.copy(alpha = 0.4f) else Color.Black
                    )
                }
            }
            if (refreshCooldown) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = AppDarkGrey.copy(alpha = 0.12f)
                )
                Text(
                    text = "Scanning…",
                    fontSize = 12.sp,
                    color = AppDarkGrey,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
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
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_PEER_APP_ID, appId)
                .putExtra(ChatActivity.EXTRA_DISPLAY_NAME, appId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun openChatForPeripheral(peripheral: BluetoothPeripheral) {
        val appId = BluetoothHandler.getAppIdForAddress(peripheral.address)
        if (appId == null) {
            Toast.makeText(this, "Could not read peer ID from scan data. Scan again.", Toast.LENGTH_LONG).show()
            Timber.w("openChatForPeripheral: no appId for ${peripheral.address}")
            return
        }
        KnownPeers.save(
            KnownPeer(
                appId = appId,
                displayName = peripheral.name.ifBlank { appId },
                lastSeenMs = System.currentTimeMillis()
            )
        )
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_PEER_APP_ID, appId)
                .putExtra(ChatActivity.EXTRA_DISPLAY_NAME, peripheral.name.ifBlank { appId })
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
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
            if (permissions.values.all { it }) {
                attemptAutoScan()
            }
        }

    private val enableBleRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) attemptAutoScan()
        }
}
