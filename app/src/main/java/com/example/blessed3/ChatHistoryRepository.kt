package com.example.blessed3

import com.example.blessed3.db.AppDatabase
import com.example.blessed3.db.ChatHistoryDao
import com.example.blessed3.db.ConversationMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Persists conversation rows keyed by the remote peer's [peerAppId].
 */
object ChatHistoryRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dao: ChatHistoryDao

    fun initialize(db: AppDatabase) {
        dao = db.chatHistoryDao()
    }

    fun messagesForPeer(peerAppId: String): Flow<List<ChatMessage>> =
        dao.observeForPeer(peerAppId.lowercase())
            .map { rows ->
                val peer = peerAppId.lowercase()
                rows.map { e ->
                    ChatMessage(
                        id = e.messageId,
                        text = displayTextForPeer(peer, e.text, e.fromMe),
                        isFromMe = e.fromMe,
                        timestampMs = e.timestampMs
                    )
                }
            }
            .flowOn(Dispatchers.Default)

    /** Decrypt for list preview / bubbles. */
    fun displayTextForPeer(peerAppId: String, stored: String, fromMe: Boolean): String {
        val peer = peerAppId.lowercase()
        ChatPayloadCrypto.decryptFromPeer(peer, stored)?.let { return it }
        return if (E2eeWireFormat.isEncryptedMessage(stored)) "…" else stored
    }

    fun appendOutboundCipher(peerAppId: String, ciphertext: String, dedupeKey: String? = null) {
        scope.launch {
            insertRow(peerAppId.lowercase(), ciphertext, fromMe = true, dedupeKey = dedupeKey)
        }
    }

    /** Latest [ConversationMessageEntity] per peer (key = lowercase appId). */
    fun latestMessagesByPeerFlow(): Flow<Map<String, ConversationMessageEntity>> =
        dao.observeLatestMessagePerPeer().map { list ->
            list.associateBy { it.peerAppId.lowercase() }
        }

    /**
     * Inbound from any transport. Increments unread unless [ActiveChatPeer] matches [senderAppId].
     */
    fun appendInbound(senderAppId: String, text: String, dedupeKey: String?) {
        val peer = senderAppId.lowercase()
        scope.launch {
            if (dedupeKey != null && dao.existsWithDedupeKey(dedupeKey)) return@launch
            if (!insertRow(peer, text, fromMe = false, dedupeKey = dedupeKey)) return@launch
            withContext(Dispatchers.Main) {
                if (!ActiveChatPeer.isShowing(peer)) {
                    KnownPeers.incrementUnread(peer)
                }
                ChatTransportCoordinator.onAppPayloadActivity()
            }
        }
    }

    private suspend fun insertRow(
        peerAppId: String,
        text: String,
        fromMe: Boolean,
        dedupeKey: String?
    ): Boolean {
        val entity = ConversationMessageEntity(
            messageId = UUID.randomUUID().toString(),
            peerAppId = peerAppId,
            timestampMs = System.currentTimeMillis(),
            text = text,
            fromMe = fromMe,
            dedupeKey = dedupeKey
        )
        return try {
            dao.insert(entity)
            true
        } catch (_: Exception) {
            false
        }
    }
}
