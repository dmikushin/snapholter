package dev.snapecg.holter.connector

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure crypto primitives used by the connector pairing/session protocol.
 *
 * Wire-compatible with snapecg-connector/protocol.py:
 *   - HMAC-SHA256 for pair proofs
 *   - PBKDF2-HMAC-SHA256 (100k iters, 32 bytes) for session-key derivation
 *   - AES-256-GCM with 12-byte nonce + 16-byte tag for message encryption
 *
 * Kept as a stateless object so unit tests can call it directly without
 * standing up the Android Service.
 */
object ConnectorCrypto {

    const val GCM_NONCE_BYTES = 12      // 96-bit nonce per NIST SP 800-38D
    const val GCM_TAG_BITS = 128        // 16-byte authentication tag
    const val PBKDF2_ITERATIONS = 100_000
    const val SESSION_KEY_BYTES = 32    // AES-256

    private val secureRandom = SecureRandom()

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun deriveSessionKey(code: String, salt: ByteArray): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(code.toCharArray(), salt, PBKDF2_ITERATIONS, SESSION_KEY_BYTES * 8)
        return factory.generateSecret(spec).encoded
    }

    /** Constant-time compare of pair proof bytes. */
    fun verifyProof(code: String, salt: ByteArray, proof: ByteArray): Boolean {
        val expected = hmacSha256(code.toByteArray(Charsets.UTF_8), salt)
        return MessageDigest.isEqual(expected, proof)
    }

    /**
     * Encrypt with AES-256-GCM. Output layout: [12-byte nonce] [ciphertext + 16-byte tag].
     */
    fun encryptGcm(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(GCM_NONCE_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE,
                 SecretKeySpec(key, "AES"),
                 GCMParameterSpec(GCM_TAG_BITS, nonce))
        }
        val ct = cipher.doFinal(plaintext)
        return nonce + ct
    }

    fun decryptGcm(key: ByteArray, blob: ByteArray): ByteArray {
        if (blob.size < GCM_NONCE_BYTES + GCM_TAG_BITS / 8) {
            throw IllegalArgumentException("ciphertext shorter than nonce + GCM tag")
        }
        val nonce = blob.copyOfRange(0, GCM_NONCE_BYTES)
        val ct = blob.copyOfRange(GCM_NONCE_BYTES, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE,
                 SecretKeySpec(key, "AES"),
                 GCMParameterSpec(GCM_TAG_BITS, nonce))
        }
        return cipher.doFinal(ct)
    }

    fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
