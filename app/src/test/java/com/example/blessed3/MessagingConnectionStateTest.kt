package com.example.blessed3

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MessagingConnectionStateTest {

    @Before
    @After
    fun resetState() {
        MessagingConnectionState.clear()
    }

    @Test
    fun normalizeAddress_uppercases() {
        assertEquals("AA:BB:CC", MessagingConnectionState.normalizeAddress("aa:bb:cc"))
        assertNull(MessagingConnectionState.normalizeAddress(null))
        assertNull(MessagingConnectionState.normalizeAddress("   "))
    }

    @Test
    fun displayLabel_usesNameWhenNonBlank() {
        MessagingConnectionState.setConnectedAsCentral("aa:bb", "Alice", "aabbccdd")
        val c = MessagingConnectionState.currentPeer!!
        assertEquals("Alice", c.displayLabel())
    }

    @Test
    fun displayLabel_fallsBackToAddress() {
        MessagingConnectionState.setConnectedAsCentral("BB:CC", "", "aabbccdd")
        val c = MessagingConnectionState.currentPeer!!
        assertEquals("BB:CC", c.displayLabel())
    }

    @Test
    fun isPeer_matchesNormalizedAddress() {
        MessagingConnectionState.setConnectedAsPeripheral("aa:bb:cc:dd", "Bob", null)
        assertTrue(MessagingConnectionState.isPeer("AA:BB:CC:DD"))
        assertFalse(MessagingConnectionState.isPeer("11:22:33:44"))
    }

    @Test
    fun setConnectedAsInternet_storesTargetAsAddressAndPeerAppId() {
        MessagingConnectionState.setConnectedAsInternet("deadbeef", "Peer")
        val c = MessagingConnectionState.currentPeer!!
        assertEquals(MessagingConnectionState.Role.WE_ARE_INTERNET, c.role)
        assertEquals("deadbeef", c.peerAddress)
        assertEquals("deadbeef", c.peerAppId)
    }

    @Test
    fun updatePeerAppId_updatesCurrent() {
        MessagingConnectionState.setConnectedAsCentral("AA", "N", null)
        MessagingConnectionState.updatePeerAppId("11223344")
        assertEquals("11223344", MessagingConnectionState.currentPeer!!.peerAppId)
    }

    @Test
    fun clearIfPeer_onlyClearsMatchingAddress() {
        MessagingConnectionState.setConnectedAsCentral("AA:01", "X", null)
        MessagingConnectionState.clearIfPeer("AA:02")
        assertTrue(MessagingConnectionState.isConnected)
        MessagingConnectionState.clearIfPeer("AA:01")
        assertFalse(MessagingConnectionState.isConnected)
    }
}
