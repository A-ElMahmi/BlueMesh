package com.example.blessed3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayPacketTest {

    @Test
    fun toJson_fromJson_roundTrip() {
        val original = RelayPacket(
            messageId = "mid-1",
            originAppId = "aabbccdd",
            destinationAppId = "11223344",
            content = "hello ciphertext"
        )
        val restored = RelayPacket.fromJson(original.toJson())!!
        assertEquals(original.messageId, restored.messageId)
        assertEquals(original.originAppId, restored.originAppId)
        assertEquals(original.destinationAppId, restored.destinationAppId)
        assertEquals(original.content, restored.content)
    }

    @Test
    fun fromJson_invalid_returnsNull() {
        assertNull(RelayPacket.fromJson("not json"))
        assertNull(RelayPacket.fromJson("{}"))
        assertNull(RelayPacket.fromJson("""{"messageId":"x"}"""))
    }
}
