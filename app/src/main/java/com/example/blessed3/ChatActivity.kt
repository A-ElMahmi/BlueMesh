package com.example.blessed3

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.blessed3.ui.theme.BlueMesh3Theme
import com.example.blessed3.ui.theme.TheirMessageBubble
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private sealed class ChatListEntry {
    data class Msg(val message: ChatMessage) : ChatListEntry()
    data class DateSep(val label: String) : ChatListEntry()
}

class ChatActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PEER_APP_ID = "peer_app_id"
        const val EXTRA_DISPLAY_NAME = "display_name"
    }

    private lateinit var peerAppId: String
    private lateinit var displayName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_PEER_APP_ID)?.lowercase()
        if (id.isNullOrBlank()) {
            finish()
            return
        }
        peerAppId = id
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)?.takeIf { it.isNotBlank() }
            ?: KnownPeers.getAll().find { it.appId == peerAppId }?.displayName
            ?: peerAppId

        Timber.d("NAVDBG ChatActivity peer=$peerAppId name=$displayName")
        ChatTransportCoordinator.startSession(peerAppId, displayName)

        setContent {
            BlueMesh3Theme {
                ChatScreen(peerAppId = peerAppId, onBack = { finish() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ActiveChatPeer.set(peerAppId)
        KnownPeers.clearUnread(peerAppId)
    }

    override fun onPause() {
        ActiveChatPeer.set(null)
        super.onPause()
    }

    override fun onDestroy() {
        ChatTransportCoordinator.onChatClosed(peerAppId)
        Timber.d("NAVDBG ChatActivity.onDestroy finishing=$isFinishing")
        super.onDestroy()
    }

    @Composable
    private fun ChatScreen(peerAppId: String, onBack: () -> Unit) {
        var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
        var messageText by remember { mutableStateOf("") }
        var showRenameDialog by remember { mutableStateOf(false) }
        var renameDraft by remember { mutableStateOf("") }
        val listState = rememberLazyListState()

        val peers by KnownPeers.peersFlow.collectAsState()
        val displayTitle = peers.find { it.appId == peerAppId }?.displayName ?: peerAppId

        val conn by MessagingConnectionState.state.collectAsState(initial = null)
        val scanning by ChatTransportCoordinator.scanning.collectAsState()
        val serverReachable by ServerClient.serverReachable.collectAsState()

        val statusText = remember(scanning, conn, peerAppId, serverReachable) {
            when {
                scanning -> "Connecting…"
                else -> {
                    val s = conn
                    if (!ChatTransportCoordinator.transportMatches(peerAppId, s)) {
                        "Not connected"
                    } else {
                        when (s!!.role) {
                            MessagingConnectionState.Role.WE_ARE_INTERNET ->
                                when {
                                    !NetworkUtils.hasInternet(this@ChatActivity) -> "Not connected"
                                    !serverReachable -> "Not connected"
                                    else -> "Connected via Wi‑Fi"
                                }
                            MessagingConnectionState.Role.WE_ARE_CENTRAL,
                            MessagingConnectionState.Role.WE_ARE_PERIPHERAL ->
                                if (s.peerAppId == null) "Connecting…" else "Connected via BLE"
                        }
                    }
                }
            }
        }

        val listEntries = remember(messages) { buildChatListEntries(messages) }

        LaunchedEffect(peerAppId) {
            ChatHistoryRepository.messagesForPeer(peerAppId).collect { messages = it }
        }

        LaunchedEffect(listEntries.size) {
            if (listEntries.isNotEmpty()) {
                listState.animateScrollToItem(listEntries.size - 1)
            }
        }

        if (showRenameDialog) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Display name") },
                text = {
                    OutlinedTextField(
                        value = renameDraft,
                        onValueChange = { renameDraft = it },
                        label = { Text("Name (local only)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val name = renameDraft.trim().ifBlank { peerAppId }
                            val existing = KnownPeers.getAll().find { it.appId == peerAppId }
                            KnownPeers.save(
                                KnownPeer(
                                    appId = peerAppId,
                                    displayName = name,
                                    lastSeenMs = existing?.lastSeenMs ?: System.currentTimeMillis()
                                )
                            )
                            showRenameDialog = false
                        }
                    ) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
                }
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back_24dp_303030_fill0_wght400_grad0_opsz24),
                        contentDescription = "Back",
                        tint = Color.Black
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = displayTitle,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Text(
                            text = " #${peerAppId}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal,
                            color = AppDarkGrey,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = statusText,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                IconButton(
                    onClick = {
                        renameDraft = displayTitle
                        showRenameDialog = true
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.edit_24dp_303030_fill0_wght400_grad0_opsz24),
                        contentDescription = "Edit name",
                        tint = Color.Black
                    )
                }
            }

            HorizontalDivider(thickness = 1.dp, color = AppDarkGrey.copy(alpha = 0.2f))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(
                    listEntries,
                    key = { index, entry ->
                        when (entry) {
                            is ChatListEntry.Msg -> entry.message.id
                            is ChatListEntry.DateSep -> "sep_${index}_${entry.label}"
                        }
                    }
                ) { _, entry ->
                    when (entry) {
                        is ChatListEntry.DateSep -> DateSeparatorRow(entry.label)
                        is ChatListEntry.Msg -> MessageBubble(entry.message)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
                        value = messageText,
                        onValueChange = { messageText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                "Send Message…",
                                color = AppDarkGrey,
                                fontSize = 16.sp
                            )
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = AppDarkGrey,
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 16.sp,
                            color = Color.Black
                        )
                    )
                    val canSend = messageText.trim().isNotEmpty()
                    IconButton(
                        onClick = {
                            val text = messageText.trim()
                            if (text.isNotEmpty()) {
                                ChatTransportCoordinator.onUserSend(this@ChatActivity, peerAppId, text)
                                messageText = ""
                            }
                        },
                        enabled = canSend
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.send_24dp_303030_fill0_wght400_grad0_opsz24),
                            contentDescription = "Send",
                            tint = if (canSend) MaterialTheme.colorScheme.primary else AppDarkGrey.copy(alpha = 0.45f)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun DateSeparatorRow(label: String) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = AppDarkGrey.copy(alpha = 0.4f)
            )
            Text(
                text = label,
                color = AppDarkGrey,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = AppDarkGrey.copy(alpha = 0.4f)
            )
        }
    }

    @Composable
    private fun MessageBubble(msg: ChatMessage) {
        val zone = ZoneId.systemDefault()
        val timeStr = remember(msg.timestampMs) {
            DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
                .format(Instant.ofEpochMilli(msg.timestampMs).atZone(zone).toLocalTime())
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = if (msg.isFromMe) Arrangement.End else Arrangement.Start
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .wrapContentWidth(align = if (msg.isFromMe) Alignment.End else Alignment.Start)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (msg.isFromMe) MaterialTheme.colorScheme.primary
                        else TheirMessageBubble
                    )
                    .padding(10.dp)
            ) {
                Column(
                    horizontalAlignment = if (msg.isFromMe) Alignment.End else Alignment.Start
                ) {
                    Text(
                        text = msg.text,
                        color = if (msg.isFromMe) Color.White else Color.Black,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = timeStr,
                        color = if (msg.isFromMe) Color.White.copy(alpha = 0.85f) else AppDarkGrey,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

private fun buildChatListEntries(messages: List<ChatMessage>): List<ChatListEntry> {
    if (messages.isEmpty()) return emptyList()
    val zone = ZoneId.systemDefault()
    val byDay = messages.groupBy { Instant.ofEpochMilli(it.timestampMs).atZone(zone).toLocalDate() }
    val daysAsc = byDay.keys.sorted()
    val out = mutableListOf<ChatListEntry>()
    for (day in daysAsc) {
        val label = relativeDateLabel(day, zone)
        out.add(ChatListEntry.DateSep(label))
        byDay[day]!!.sortedBy { it.timestampMs }.forEach { out.add(ChatListEntry.Msg(it)) }
    }
    return out
}

private fun relativeDateLabel(day: LocalDate, zone: ZoneId): String {
    val today = LocalDate.now(zone)
    return when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofPattern("dd/MM", Locale.getDefault()).format(day)
    }
}
