package com.example.blessed3

import java.util.concurrent.atomic.AtomicReference

/**
 * Which peer thread is currently visible in [ChatActivity] (foreground).
 */
object ActiveChatPeer {

    private val active = AtomicReference<String?>(null)

    fun set(peerAppId: String?) {
        active.set(peerAppId?.lowercase())
    }

    fun get(): String? = active.get()

    fun isShowing(peerAppId: String): Boolean =
        active.get() == peerAppId.lowercase()
}
