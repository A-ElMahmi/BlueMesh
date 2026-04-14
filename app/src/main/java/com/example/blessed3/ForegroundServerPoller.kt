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
    private const val INTERVAL_MS = 15_000L

    fun start(app: Application) {
        val owner = ProcessLifecycleOwner.get()
        owner.lifecycleScope.launch {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    if (NetworkUtils.hasInternet(app)) {
                        runCatching { pollOnce() }
                            .onFailure { e -> Log.w(TAG, "poll failed", e) }
                    }
                    delay(INTERVAL_MS)
                }
            }
        }
    }

    private suspend fun pollOnce() {
        val messages = ServerClient.pollMessages(DeviceIdentity.appId)
        messages.forEach { msg ->
            ChatInboundDispatch.dispatch(
                senderAppId = msg.from,
                body = msg.content,
                dedupeKey = msg.messageId
            )
        }
        RelayManager.deliverPendingFromServer()
    }
}
