package com.example.blessed3

/**
 * Single entry for inbound user-visible payloads from BLE, relay, or server poll.
 * Key announces are absorbed without creating a chat row.
 */
object ChatInboundDispatch {

    fun dispatch(senderAppId: String, body: String, dedupeKey: String?) {
        if (ChatPayloadCrypto.tryConsumeKeyAnnounce(senderAppId, body)) {
            ChatTransportCoordinator.onPeerPublicKeyLearned()
            return
        }
        ChatHistoryRepository.appendInbound(senderAppId, body, dedupeKey)
    }
}
