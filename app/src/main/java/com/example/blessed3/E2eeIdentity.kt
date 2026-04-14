package com.example.blessed3

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * One long-term P-256 EC key in Android Keystore ([KeyProperties.PURPOSE_AGREE_KEY]) per install,
 * aligned with [DeviceIdentity.appId] identity. Used only for ECDH; public half is shared in handshake.
 */
object E2eeIdentity {

    private const val ALIAS = "e2ee_ec_p256_identity_v1"

    fun initialize(context: android.content.Context) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(ALIAS)) return
        val kg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        kg.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .build()
        )
        kg.generateKeyPair()
    }

    fun publicKeySpkiBase64(): String {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cert = ks.getCertificate(ALIAS)
        return Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
    }

    fun decodePeerPublicKey(spkiBase64: String): PublicKey {
        val bytes = Base64.decode(spkiBase64, Base64.NO_WRAP)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    fun ecdhSharedSecret(peerPublicKeySpkiBase64: String): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = ks.getKey(ALIAS, null) as PrivateKey
        val peerPublic = decodePeerPublicKey(peerPublicKeySpkiBase64)
        val ka = KeyAgreement.getInstance("ECDH", "AndroidKeyStore")
        ka.init(privateKey)
        ka.doPhase(peerPublic, true)
        return ka.generateSecret()
    }
}
