package com.example.blessed3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakePayloadTest {

    @Test
    fun parse_legacyEightHex() {
        val p = HandshakePayload.parse("aabbccdd")!!
        assertEquals("aabbccdd", p.appId)
        assertNull(p.publicKeySpkiBase64)
    }

    @Test
    fun parse_json_withPublicKey() {
        val json = HandshakePayload.toJson("AABBCCDD", "spki-base64-here")
        val p = HandshakePayload.parse(json)!!
        assertEquals("aabbccdd", p.appId)
        assertEquals("spki-base64-here", p.publicKeySpkiBase64)
    }

    @Test
    fun parse_json_withoutPublicKey() {
        val json = """{"appId":"FFEEDDCC"}"""
        val p = HandshakePayload.parse(json)!!
        assertEquals("ffeeddcc", p.appId)
        assertNull(p.publicKeySpkiBase64)
    }

    @Test
    fun isExtendedJson() {
        assertTrue(HandshakePayload.isExtendedJson("""{"appId":"aa"}"""))
        assertFalse(HandshakePayload.isExtendedJson("aabbccdd"))
    }

    @Test
    fun parse_invalid_returnsNull() {
        assertNull(HandshakePayload.parse("nothex"))
        assertNull(HandshakePayload.parse("aabbccd")) // wrong length
        assertNull(HandshakePayload.parse("{}"))
    }
}
