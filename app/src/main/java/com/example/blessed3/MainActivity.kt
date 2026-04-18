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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.blessed3.ui.theme.AppDarkGrey
import com.example.blessed3.ui.theme.AppLightGrey
import com.example.blessed3.ui.theme.BlueMesh3Theme
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("NAVDBG MainActivity.onCreate taskId=$taskId instance=${System.identityHashCode(this)}")
        setContent {
            BlueMesh3Theme {
                ChatsScreen()
            }
        }
    }

    @Composable
    private fun ChatsScreen() {
        val peers by KnownPeers.peersFlow.collectAsState()
        val latestByPeer by ChatHistoryRepository.latestMessagesByPeerFlow().collectAsState(initial = emptyMap())
        var searchText by remember { mutableStateOf("") }

        val sortedPeers = remember(peers, latestByPeer) {
            peers.sortedByDescending { p ->
                latestByPeer[p.appId.lowercase()]?.timestampMs ?: p.lastSeenMs
            }
        }

        val query = searchText.trim()
        val filteredPeers = remember(sortedPeers, query) {
            if (query.isEmpty()) sortedPeers
            else sortedPeers.filter { peer ->
                peer.displayName.contains(query, ignoreCase = true)
            }
        }

        val roboto = FontFamily.SansSerif

        Scaffold(
            containerColor = AppLightGrey,
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        startActivity(Intent(this@MainActivity, DiscoverActivity::class.java))
                    },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    modifier = Modifier.size(72.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.forum_24dp_303030_fill0_wght400_grad0_opsz24),
                        contentDescription = "Discover",
                        tint = Color.White
                    )
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(AppLightGrey)
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White,
                    shadowElevation = 0.dp,
                    tonalElevation = 0.dp
                ) {
                    TextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                "Search",
                                color = AppDarkGrey,
                                fontFamily = roboto,
                                fontWeight = FontWeight.Normal,
                                fontSize = 16.sp
                            )
                        },
                        leadingIcon = {
                            if (searchText.isNotEmpty()) {
                                IconButton(
                                    onClick = { searchText = "" },
                                    modifier = Modifier.padding(start = 4.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.close_24dp_303030_fill0_wght400_grad0_opsz24),
                                        contentDescription = "Clear search",
                                        tint = Color.Black
                                    )
                                }
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.search_24dp_303030_fill0_wght400_grad0_opsz24),
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            cursorColor = AppDarkGrey,
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = roboto,
                            fontWeight = FontWeight.Normal,
                            fontSize = 16.sp,
                            color = Color.Black
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                when {
                    sortedPeers.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No contacts yet",
                                fontFamily = roboto,
                                fontSize = 16.sp,
                                color = AppDarkGrey
                            )
                        }
                    }
                    filteredPeers.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No matching chats",
                                fontFamily = roboto,
                                fontSize = 16.sp,
                                color = AppDarkGrey
                            )
                        }
                    }
                    else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp)
                    ) {
                        items(filteredPeers, key = { it.appId }) { peer ->
                            val last = latestByPeer[peer.appId.lowercase()]
                            val previewRaw = last?.let { m ->
                                if (m.fromMe) "You: ${m.text}" else m.text
                            }
                            val preview = if (previewRaw.isNullOrBlank()) {
                                "No messages yet"
                            } else {
                                previewRaw
                            }
                            val hasUnread = peer.unreadCount > 0
                            val previewBold = hasUnread && last != null
                            val sortTs = last?.timestampMs ?: peer.lastSeenMs

                            ChatListRow(
                                peer = peer,
                                preview = preview,
                                timestampMs = sortTs,
                                hasUnread = hasUnread,
                                previewBold = previewBold,
                                roboto = roboto,
                                onClick = { openPeerMessages(peer) }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                thickness = 1.dp,
                                color = AppDarkGrey.copy(alpha = 0.15f)
                            )
                        }
                    }
                    }
                }
            }
        }
    }

    @Composable
    private fun ChatListRow(
        peer: KnownPeer,
        preview: String,
        timestampMs: Long,
        hasUnread: Boolean,
        previewBold: Boolean,
        roboto: FontFamily,
        onClick: () -> Unit
    ) {
        val initial = peer.displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val (letterColor, avatarBg) = profileAvatarColors(peer.appId)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(avatarBg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    color = letterColor,
                    fontFamily = roboto,
                    fontWeight = FontWeight.Normal,
                    fontSize = 18.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.displayName,
                    fontFamily = roboto,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = preview,
                    fontFamily = roboto,
                    fontWeight = if (previewBold) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 14.sp,
                    color = AppDarkGrey,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                if (hasUnread) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = formatChatListTimestamp(timestampMs),
                    fontFamily = roboto,
                    fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp,
                    color = AppDarkGrey
                )
            }
        }
    }

    private fun openPeerMessages(peer: KnownPeer) {
        Timber.d("NAVDBG MainActivity.openPeerMessages appId=${peer.appId}")
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_PEER_APP_ID, peer.appId)
                .putExtra(ChatActivity.EXTRA_DISPLAY_NAME, peer.displayName)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

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

private val profileAccentPalette = listOf(
    Color(0xFF102E81),
    Color(0xFF390D7C),
    Color(0xFFE74C3C),
)

/** Dark accent for the letter and a lighter tint of the same hue for the circle background. */
private fun profileAvatarColors(appId: String): Pair<Color, Color> {
    val textColor = profileAccentPalette[kotlin.math.abs(appId.hashCode()) % profileAccentPalette.size]
    val bg = Color(
        red = textColor.red + (1f - textColor.red) * 0.86f,
        green = textColor.green + (1f - textColor.green) * 0.86f,
        blue = textColor.blue + (1f - textColor.blue) * 0.86f,
    )
    return Pair(textColor, bg)
}

private fun formatChatListTimestamp(epochMs: Long): String {
    val zone = ZoneId.systemDefault()
    val zdt = Instant.ofEpochMilli(epochMs).atZone(zone)
    val date = zdt.toLocalDate()
    val today = LocalDate.now(zone)
    return if (date == today) {
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(zdt.toLocalTime())
    } else {
        DateTimeFormatter.ofPattern("dd/MM", Locale.getDefault()).format(date)
    }
}
