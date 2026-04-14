package com.example.blessed3

import android.util.Base64
import com.google.crypto.tink.subtle.Hkdf
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Symmetric chat payload: ECDH([E2eeIdentity], peer SPKI) → Tink HKDF → AES-256-GCM.
 * Same key for both directions so each side can decrypt DB rows it sent or received.
 */
object ChatPayloadCrypto {

    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128
    private val secureRandom = SecureRandom()

    private fun sessionKeyForPeer(peerAppId: String): ByteArray? {
        val peerKey = PeerPublicKeyStore.get(peerAppId) ?: return null
        val ikm = runCatching { E2eeIdentity.ecdhSharedSecret(peerKey) }.getOrNull() ?: return null
        val me = DeviceIdentity.appId.lowercase()
        val peer = peerAppId.lowercase()
        val pair = if (me < peer) "$me|$peer" else "$peer|$me"
        val info = "$pair|blessed-e2ee-v1".toByteArray(Charsets.UTF_8)
        return Hkdf.computeHkdf("HmacSha256", ikm, ByteArray(0), info, 32)
    }

    fun hasPeerPublicKey(peerAppId: String): Boolean = PeerPublicKeyStore.has(peerAppId)

    fun encryptForPeer(peerAppId: String, plaintext: String): String? {
        val key = sessionKeyForPeer(peerAppId) ?: return null
        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = iv + ct
        return E2eeWireFormat.MSG_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decryptFromPeer(peerAppId: String, wire: String): String? {
        if (!E2eeWireFormat.isEncryptedMessage(wire)) return null
        val key = sessionKeyForPeer(peerAppId) ?: return null
        val raw = Base64.decode(wire.removePrefix(E2eeWireFormat.MSG_PREFIX), Base64.NO_WRAP)
        if (raw.size < GCM_IV_LENGTH + 2) return null
        val iv = raw.copyOfRange(0, GCM_IV_LENGTH)
        val ct = raw.copyOfRange(GCM_IV_LENGTH, raw.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun buildKeyAnnounceWire(): String {
        val json = JSONObject().apply {
            put("appId", DeviceIdentity.appId.lowercase())
            put("publicKey", E2eeIdentity.publicKeySpkiBase64())
        }
        return E2eeWireFormat.KEY_ANNOUNCE_PREFIX + json.toString()
    }

    /**
     * If [content] is a key announce from [senderAppId], stores the key and returns true (no chat row).
     */
    fun tryConsumeKeyAnnounce(senderAppId: String, content: String): Boolean {
        if (!E2eeWireFormat.isKeyAnnounce(content)) return false
        val jsonStr = content.removePrefix(E2eeWireFormat.KEY_ANNOUNCE_PREFIX)
        return try {
            val o = JSONObject(jsonStr)
            val fromId = o.getString("appId").trim().lowercase()
            val pk = o.getString("publicKey").trim()
            if (fromId != senderAppId.lowercase()) return false
            PeerPublicKeyStore.putIfAbsent(fromId, pk)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** @return true if a new key was stored (caller may flush outbound queue). */
    fun mergePeerPublicKey(peerAppId: String, publicKeySpkiBase64: String?): Boolean {
        if (publicKeySpkiBase64.isNullOrBlank()) return false
        return PeerPublicKeyStore.putIfAbsent(peerAppId.lowercase(), publicKeySpkiBase64)
    }
}
