package com.example.blessed3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlePacketTest {

    @Test
    fun toBytes_fromBytes_roundTrip() {
        val inner = RelayPacket("id", "aa", "bb", "body").toJson()
        val original = BlePacket(BlePacket.TYPE_RELAY, inner)
        val restored = BlePacket.fromBytes(original.toBytes())!!
        assertEquals(original.type, restored.type)
        assertEquals(original.body, restored.body)
    }

    @Test
    fun fromBytes_invalid_returnsNull() {
        assertNull(BlePacket.fromBytes(byteArrayOf()))
        assertNull(BlePacket.fromBytes("{".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun relayEnvelope_carriesRelayJson() {
        val relay = RelayPacket("m1", "orig", "dest", "E2EE_MSG1:abc")
        val bytes = BlePacket(BlePacket.TYPE_RELAY, relay.toJson()).toBytes()
        val outer = BlePacket.fromBytes(bytes)!!
        assertEquals(BlePacket.TYPE_RELAY, outer.type)
        val parsed = RelayPacket.fromJson(outer.body)!!
        assertEquals("m1", parsed.messageId)
        assertEquals("orig", parsed.originAppId)
        assertEquals("dest", parsed.destinationAppId)
        assertEquals("E2EE_MSG1:abc", parsed.content)
    }
}
