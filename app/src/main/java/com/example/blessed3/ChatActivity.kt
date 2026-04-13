package com.example.blessed3

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import timber.log.Timber

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
            Blessed3Theme {
                ChatScreen(peerAppId = peerAppId, title = displayName)
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
    private fun ChatScreen(peerAppId: String, title: String) {
        var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
        var messageText by remember { mutableStateOf("") }
        val listState = rememberLazyListState()

        val conn by MessagingConnectionState.state.collectAsState(initial = null)
        val scanning by ChatTransportCoordinator.scanning.collectAsState()

        val statusText = remember(scanning, conn, peerAppId) {
            when {
                scanning -> "Connecting…"
                else -> {
                    val s = conn
                    if (!ChatTransportCoordinator.transportMatches(peerAppId, s)) {
                        "Not connected"
                    } else {
                        when (s!!.role) {
                            MessagingConnectionState.Role.WE_ARE_INTERNET -> "Connected via Wi‑Fi"
                            MessagingConnectionState.Role.WE_ARE_CENTRAL,
                            MessagingConnectionState.Role.WE_ARE_PERIPHERAL ->
                                if (s.peerAppId == null) "Connecting…" else "Connected via BLE"
                        }
                    }
                }
            }
        }

        LaunchedEffect(peerAppId) {
            ChatHistoryRepository.messagesForPeer(peerAppId).collect { messages = it }
        }

        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Chat with $title", fontSize = 18.sp)
                Text(statusText, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                items(messages, key = { it.id }) { msg ->
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    label = { Text("Message") },
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = {
                    val text = messageText.trim()
                    if (text.isNotEmpty()) {
                        ChatTransportCoordinator.onUserSend(this@ChatActivity, peerAppId, text)
                        messageText = ""
                    }
                }) {
                    Text("Send")
                }
            }
        }
    }
}
