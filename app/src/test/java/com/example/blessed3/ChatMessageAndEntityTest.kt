package com.example.blessed3

import com.example.blessed3.db.ConversationMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageAndEntityTest {

    @Test
    fun chatMessage_fields() {
        val ts = 1_700_000_000_000L
        val msg = ChatMessage(
            id = "row-1",
            text = "hello",
            isFromMe = true,
            timestampMs = ts
        )
        assertEquals("row-1", msg.id)
        assertEquals("hello", msg.text)
        assertTrue(msg.isFromMe)
        assertEquals(ts, msg.timestampMs)
    }

    @Test
    fun conversationMessageEntity_storesCiphertextShape() {
        val entity = ConversationMessageEntity(
            messageId = "uuid-1",
            peerAppId = "aabbccdd",
            timestampMs = 99L,
            text = "${E2eeWireFormat.MSG_PREFIX}base64blob",
            fromMe = false,
            dedupeKey = "server-mid"
        )
        assertEquals("aabbccdd", entity.peerAppId)
        assertTrue(entity.text.startsWith(E2eeWireFormat.MSG_PREFIX))
        assertEquals(false, entity.fromMe)
        assertEquals("server-mid", entity.dedupeKey)
    }

    @Test
    fun serverMessage_fields() {
        val m = ServerMessage(
            messageId = "m-1",
            from = "aa",
            to = "bb",
            content = "${E2eeWireFormat.MSG_PREFIX}ct"
        )
        assertEquals("m-1", m.messageId)
        assertEquals("aa", m.from)
        assertEquals("bb", m.to)
        assertTrue(m.content.startsWith(E2eeWireFormat.MSG_PREFIX))
    }
}
