package com.example.blessed3

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class E2eeWireFormatTest {

    @Test
    fun isEncryptedMessage() {
        assertTrue(E2eeWireFormat.isEncryptedMessage("${E2eeWireFormat.MSG_PREFIX}abcd"))
        assertFalse(E2eeWireFormat.isEncryptedMessage("plain"))
        assertFalse(E2eeWireFormat.isEncryptedMessage("${E2eeWireFormat.KEY_ANNOUNCE_PREFIX}{}"))
    }

    @Test
    fun isKeyAnnounce() {
        assertTrue(E2eeWireFormat.isKeyAnnounce("${E2eeWireFormat.KEY_ANNOUNCE_PREFIX}{\"appId\":\"aa\"}"))
        assertFalse(E2eeWireFormat.isKeyAnnounce("hello"))
        assertFalse(E2eeWireFormat.isKeyAnnounce("${E2eeWireFormat.MSG_PREFIX}xyz"))
    }
}
