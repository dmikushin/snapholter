package dev.snapecg.holter

import dev.snapecg.holter.connector.ConnectorCrypto
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * Unit tests for [ConnectorCrypto].
 *
 * Verifies primitives used by the pair handshake and post-pair AES-GCM
 * traffic, including known-vector outputs that pin down byte-for-byte
 * compatibility with snapecg-connector/protocol.py.
 */
class ConnectorCryptoTest {

    // ---- HMAC-SHA256 ----

    @Test
    fun `hmacSha256 matches RFC 4231 test case 1`() {
        // RFC 4231 §4.2: key = 0x0b * 20, data = "Hi There"
        val key = ByteArray(20) { 0x0b.toByte() }
        val data = "Hi There".toByteArray(Charsets.US_ASCII)
        val expected = "b0344c61d8db38535ca8afceaf0bf12b" +
                "881dc200c9833da726e9376c2e32cff7"
        val actual = ConnectorCrypto.hmacSha256(key, data).toHex()
        assertEquals(expected, actual)
    }

    @Test
    fun `hmacSha256 matches Python reference for known pair vector`() {
        // Same inputs we use in the Python interop test:
        //   code="XK4P9VR2J7TMQH3W" (16 chars from the high-entropy alphabet),
        //   salt=00112233445566778899aabbccddeeff,
        //   proof = HMAC-SHA256(code, salt)
        val code = "XK4P9VR2J7TMQH3W".toByteArray(Charsets.UTF_8)
        val salt = ConnectorCrypto.hexToBytes("00112233445566778899aabbccddeeff")!!
        val expected = "b6325a957950828127e023cce3492f32" +
                "fe8051d37ed969397dce3e3ca9fdc653"
        assertEquals(expected, ConnectorCrypto.hmacSha256(code, salt).toHex())
    }

    // ---- verifyProof ----

    @Test
    fun `verifyProof accepts matching proof`() {
        val code = "987654"
        val salt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val proof = ConnectorCrypto.hmacSha256(code.toByteArray(), salt)
        assertTrue(ConnectorCrypto.verifyProof(code, salt, proof))
    }

    @Test
    fun `verifyProof rejects wrong code`() {
        val salt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val proof = ConnectorCrypto.hmacSha256("987654".toByteArray(), salt)
        assertFalse(ConnectorCrypto.verifyProof("987655", salt, proof))
    }

    @Test
    fun `verifyProof rejects wrong salt`() {
        val code = "987654"
        val salt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val proof = ConnectorCrypto.hmacSha256(code.toByteArray(), salt)
        val tamperedSalt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 9)
        assertFalse(ConnectorCrypto.verifyProof(code, tamperedSalt, proof))
    }

    @Test
    fun `verifyProof rejects flipped proof bit`() {
        val code = "111111"
        val salt = byteArrayOf(9, 8, 7, 6)
        val proof = ConnectorCrypto.hmacSha256(code.toByteArray(), salt)
        proof[0] = (proof[0].toInt() xor 0x01).toByte()
        assertFalse(ConnectorCrypto.verifyProof(code, salt, proof))
    }

    // ---- PBKDF2 ----

    @Test
    fun `deriveSessionKey produces 32 bytes`() {
        val key = ConnectorCrypto.deriveSessionKey("123456", "saltsalt".toByteArray())
        assertEquals(32, key.size)
    }

    @Test
    fun `deriveSessionKey matches Python reference vector`() {
        // Python: hashlib.pbkdf2_hmac('sha256', b'XK4P9VR2J7TMQH3W',
        //   bytes.fromhex('00112233445566778899aabbccddeeff'), 100000, dklen=32)
        val salt = ConnectorCrypto.hexToBytes("00112233445566778899aabbccddeeff")!!
        val expected = "21a0bde826116e3dd56caf15501358a9" +
                "54fc337f77831b36ffdc8428f6fd6175"
        val actual = ConnectorCrypto.deriveSessionKey("XK4P9VR2J7TMQH3W", salt).toHex()
        assertEquals(expected, actual)
    }

    @Test
    fun `deriveSessionKey is deterministic for same inputs`() {
        val salt = "fixedsalt".toByteArray()
        val a = ConnectorCrypto.deriveSessionKey("987654", salt)
        val b = ConnectorCrypto.deriveSessionKey("987654", salt)
        assertArrayEquals(a, b)
    }

    @Test
    fun `deriveSessionKey diverges for different code`() {
        val salt = "fixedsalt".toByteArray()
        val a = ConnectorCrypto.deriveSessionKey("987654", salt)
        val b = ConnectorCrypto.deriveSessionKey("987655", salt)
        assertFalse(a.contentEquals(b))
    }

    // ---- AES-GCM ----

    @Test
    fun `encryptGcm output layout is nonce + ciphertext + tag`() {
        val key = ByteArray(32) { 0x42.toByte() }
        val plaintext = "hello".toByteArray()
        val blob = ConnectorCrypto.encryptGcm(key, plaintext)
        // 12-byte nonce + plaintext.size + 16-byte tag
        assertEquals(ConnectorCrypto.GCM_NONCE_BYTES + plaintext.size + 16, blob.size)
    }

    @Test
    fun `aes-gcm round-trip recovers plaintext`() {
        val key = ConnectorCrypto.deriveSessionKey("123456", "salt".toByteArray())
        val plaintext = """{"id":1,"method":"holter.get_status"}""".toByteArray()
        val blob = ConnectorCrypto.encryptGcm(key, plaintext)
        val recovered = ConnectorCrypto.decryptGcm(key, blob)
        assertArrayEquals(plaintext, recovered)
    }

    @Test
    fun `aes-gcm produces different ciphertexts for repeated plaintext`() {
        // Random nonce per call → ciphertexts must differ even with same key+plaintext.
        val key = ByteArray(32) { 1 }
        val pt = "same plaintext".toByteArray()
        val a = ConnectorCrypto.encryptGcm(key, pt)
        val b = ConnectorCrypto.encryptGcm(key, pt)
        assertFalse("nonces should not collide", a.contentEquals(b))
    }

    @Test(expected = AEADBadTagException::class)
    fun `aes-gcm detects tampered ciphertext`() {
        val key = ByteArray(32) { 7 }
        val plaintext = "secret".toByteArray()
        val blob = ConnectorCrypto.encryptGcm(key, plaintext)
        // Flip a byte inside the ciphertext (after nonce)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0xFF).toByte()
        ConnectorCrypto.decryptGcm(key, blob)  // throws AEADBadTagException
    }

    @Test(expected = AEADBadTagException::class)
    fun `aes-gcm detects tampered nonce`() {
        val key = ByteArray(32) { 7 }
        val blob = ConnectorCrypto.encryptGcm(key, "x".toByteArray())
        blob[0] = (blob[0].toInt() xor 0xFF).toByte()  // flip nonce byte
        ConnectorCrypto.decryptGcm(key, blob)
    }

    @Test(expected = AEADBadTagException::class)
    fun `aes-gcm rejects wrong key`() {
        val keyA = ByteArray(32) { 1 }
        val keyB = ByteArray(32) { 2 }
        val blob = ConnectorCrypto.encryptGcm(keyA, "secret".toByteArray())
        ConnectorCrypto.decryptGcm(keyB, blob)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `aes-gcm rejects truncated blob`() {
        ConnectorCrypto.decryptGcm(ByteArray(32), ByteArray(10))  // shorter than nonce+tag
    }

    // ---- hexToBytes ----

    @Test
    fun `hexToBytes parses lowercase`() {
        assertArrayEquals(byteArrayOf(0x00, 0xAB.toByte(), 0xFF.toByte()),
                          ConnectorCrypto.hexToBytes("00abff"))
    }

    @Test
    fun `hexToBytes parses uppercase`() {
        assertArrayEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte()),
                          ConnectorCrypto.hexToBytes("CAFE"))
    }

    @Test
    fun `hexToBytes rejects odd length`() {
        assertNull(ConnectorCrypto.hexToBytes("abc"))
    }

    @Test
    fun `hexToBytes rejects non-hex characters`() {
        assertNull(ConnectorCrypto.hexToBytes("xyz0"))
        assertNull(ConnectorCrypto.hexToBytes("00gg"))
    }

    @Test
    fun `hexToBytes parses empty string`() {
        assertArrayEquals(ByteArray(0), ConnectorCrypto.hexToBytes(""))
    }

    // ---- Helpers ----

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
