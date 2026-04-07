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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("NAVDBG ChatActivity.onCreate taskId=$taskId instance=${System.identityHashCode(this)}")
        setContent {
            Blessed3Theme {
                val connectionState by MessagingConnectionState.state.collectAsState()

                LaunchedEffect(connectionState) {
                    Timber.d(
                        "NAVDBG ChatActivity.LaunchedEffect connectionState role=${connectionState?.role} " +
                            "peerAppId=${connectionState?.peerAppId} peerAddress=${connectionState?.peerAddress}"
                    )
                    if (connectionState == null) finish()
                }

                // Save peer as soon as we have their stable appId.
                // For WE_ARE_CENTRAL: available immediately from the scan result.
                // For WE_ARE_PERIPHERAL: set after the handshake packet arrives.
                LaunchedEffect(connectionState?.peerAppId) {
                    val appId = connectionState?.peerAppId ?: return@LaunchedEffect
                    KnownPeers.save(
                        KnownPeer(
                            appId = appId,
                            displayName = connectionState!!.displayLabel(),
                            lastSeenMs = System.currentTimeMillis()
                        )
                    )
                }

                connectionState?.let { state ->
                    ChatScreen(
                        peerLabel = state.displayLabel(),
                        onDisconnect = {
                            Timber.d("NAVDBG ChatActivity.onDisconnect clicked")
                            BleMessaging.disconnect(this@ChatActivity)
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Timber.d("NAVDBG ChatActivity.onResume taskId=$taskId instance=${System.identityHashCode(this)}")
    }

    override fun onDestroy() {
        Timber.d("NAVDBG ChatActivity.onDestroy instance=${System.identityHashCode(this)} finishing=$isFinishing")
        super.onDestroy()
    }

    @Composable
    private fun ChatScreen(peerLabel: String, onDisconnect: () -> Unit) {
        val messages by MessageBus.messages.collectAsState()
        var messageText by remember { mutableStateOf("") }
        val listState = rememberLazyListState()

        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Chat with $peerLabel", fontSize = 16.sp)
                Button(onClick = onDisconnect) { Text("Disconnect") }
            }

            Spacer(modifier = Modifier.height(8.dp))

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
                        BleMessaging.send(this@ChatActivity, text)
                        messageText = ""
                    }
                }) {
                    Text("Send")
                }
            }
        }
    }
}
