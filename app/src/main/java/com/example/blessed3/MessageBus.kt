package com.example.blessed3

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * In-memory message list for the current session.
 * Cleared on every disconnect so the next session starts fresh.
 *
 * add() and clear() are safe to call from any thread — they always
 * dispatch the StateFlow mutation to the main thread so Compose
 * collectAsState() picks up the change reliably.
 */
object MessageBus {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    fun add(msg: ChatMessage) {
        Log.d("BleMsg", "MessageBus.add isFromMe=${msg.isFromMe} \"${msg.text}\" (caller thread: ${Thread.currentThread().name})")
        scope.launch {
            _messages.value = _messages.value + msg
            Log.d("BleMsg", "MessageBus list size now ${_messages.value.size}")
        }
    }

    fun clear() {
        scope.launch {
            _messages.value = emptyList()
        }
    }
}
