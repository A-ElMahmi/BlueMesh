package com.example.blessed3

import android.app.Application
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls the server while the **app** is in the foreground (any Activity resumed), not just
 * [MainActivity]. Uses [ProcessLifecycleOwner] so [ChatActivity] does not stop inbox polling.
 */
object ForegroundServerPoller {

    private const val TAG = "ServerPoll"
    private const val INTERVAL_MS = 3_000L

    fun start(app: Application) {
        val owner = ProcessLifecycleOwner.get()
        Log.d(TAG, "ForegroundServerPoller.start() scheduled (interval=${INTERVAL_MS}ms)")
        owner.lifecycleScope.launch {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Log.d(TAG, "process STARTED → poll loop active")
                while (isActive) {
                    val online = NetworkUtils.hasInternet(app)
                    val id = DeviceIdentity.appId
                    if (!online) {
                        Log.d(TAG, "skip tick: no internet (appId=$id)")
                    } else if (id.isBlank()) {
                        Log.w(TAG, "skip tick: appId not initialised")
                    } else {
                        runCatching { pollOnce() }
                            .onFailure { e -> Log.w(TAG, "poll failed", e) }
                    }
                    delay(INTERVAL_MS)
                }
                Log.d(TAG, "process STOPPED → poll loop paused")
            }
        }
    }

    private suspend fun pollOnce() {
        val messages = ServerClient.pollMessages(DeviceIdentity.appId)
        messages.forEach { msg ->
            ChatHistoryRepository.appendInbound(
                senderAppId = msg.from,
                text = msg.content,
                dedupeKey = msg.messageId
            )
        }
        RelayManager.deliverPendingFromServer()
    }
}
