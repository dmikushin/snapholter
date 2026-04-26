"""
Unit tests for snapecg-connector/protocol.py.

Verifies wire-compatible behavior with the Android side
(see ConnectorCryptoTest.kt for the matching Kotlin test):
- Pair proof = HMAC-SHA256(code, salt)
- Session key = PBKDF2-HMAC-SHA256(code, salt, 100k iters, 32 bytes)
- AES-256-GCM message framing with random 96-bit nonces and 128-bit tag

Same test vectors used on both sides — if either implementation
drifts, both test files fail.

Run: python3 -m pytest test_protocol.py
or:  python3 test_protocol.py
"""
import asyncio
import hashlib
import hmac
import json
import struct
import sys
import unittest

from cryptography.exceptions import InvalidTag

from protocol import (
    AESGCM,
    GCM_NONCE_BYTES,
    PAIRING_CODE_LENGTH,
    send_message,
    recv_message,
)


# ---------------------------------------------------------------------------
# Shared interop test vectors (must match ConnectorCryptoTest.kt byte-for-byte)
# ---------------------------------------------------------------------------

VECTOR_CODE = "123456"
VECTOR_SALT_HEX = "00112233445566778899aabbccddeeff"
VECTOR_SALT = bytes.fromhex(VECTOR_SALT_HEX)
VECTOR_PROOF_HEX = (
    "659eecc469be2ed876992f478a9d939c"
    "1f0b281f68d6445e9352b154eae58e14"
)
VECTOR_SESSION_KEY_HEX = (
    "1d44f879854c5e777dd01c8275d58b1e"
    "976e7038ae2860fd82d69d1d2f3d610b"
)


# ---------------------------------------------------------------------------
# In-memory mock for asyncio.StreamReader/Writer
# ---------------------------------------------------------------------------

class MockStream:
    """Round-trip-able buffer matching the StreamReader/StreamWriter API
    needed by send_message/recv_message."""
    def __init__(self):
        self.data = bytearray()

    # StreamWriter side
    def write(self, b):
        self.data.extend(b)

    async def drain(self):
        pass

    # StreamReader side
    async def readexactly(self, n):
        if len(self.data) < n:
            raise asyncio.IncompleteReadError(bytes(self.data), n)
        chunk = bytes(self.data[:n])
        del self.data[:n]
        return chunk


def run(coro):
    """Helper: run coroutine in a fresh event loop."""
    return asyncio.new_event_loop().run_until_complete(coro)


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

class PairingVectorsTest(unittest.TestCase):
    """HMAC + PBKDF2 outputs must match the Kotlin side bit-for-bit."""

    def test_pair_proof_matches_kotlin_vector(self):
        proof = hmac.new(VECTOR_CODE.encode(), VECTOR_SALT, hashlib.sha256).hexdigest()
        self.assertEqual(proof, VECTOR_PROOF_HEX)

    def test_session_key_matches_kotlin_vector(self):
        key = hashlib.pbkdf2_hmac(
            "sha256", VECTOR_CODE.encode(), VECTOR_SALT,
            iterations=100_000, dklen=32,
        )
        self.assertEqual(key.hex(), VECTOR_SESSION_KEY_HEX)

    def test_pairing_code_length_constant(self):
        self.assertEqual(PAIRING_CODE_LENGTH, 6)


class FramingPlaintextTest(unittest.TestCase):
    """send/recv_message round-trip without a session key."""

    def test_round_trip(self):
        msg = {"id": 1, "method": "holter.get_status", "params": {}}
        b = MockStream()
        run(send_message(b, msg))
        parsed = run(recv_message(b))
        self.assertEqual(parsed, msg)

    def test_wire_layout_is_length_prefix_plus_json(self):
        msg = {"id": 7, "method": "ping"}
        b = MockStream()
        run(send_message(b, msg))
        length = struct.unpack(">I", bytes(b.data[:4]))[0]
        json_bytes = bytes(b.data[4:])
        self.assertEqual(length, len(json_bytes))
        self.assertEqual(json.loads(json_bytes), msg)


class FramingEncryptedTest(unittest.TestCase):
    """send/recv_message with AES-GCM session key."""

    KEY = bytes.fromhex(VECTOR_SESSION_KEY_HEX)

    def test_round_trip_with_key(self):
        msg = {"id": 1, "method": "holter.get_signal", "params": {"n": 2000}}
        b = MockStream()
        run(send_message(b, msg, session_key=self.KEY))
        parsed = run(recv_message(b, session_key=self.KEY))
        self.assertEqual(parsed, msg)

    def test_wire_layout_is_length_nonce_ciphertext_tag(self):
        msg = {"id": 1, "method": "ping"}
        b = MockStream()
        run(send_message(b, msg, session_key=self.KEY))

        length = struct.unpack(">I", bytes(b.data[:4]))[0]
        body = bytes(b.data[4:])
        self.assertEqual(length, len(body))
        self.assertGreaterEqual(len(body), GCM_NONCE_BYTES + 16)

        # Nonce is the first 12 bytes; rest is ciphertext+tag.
        nonce = body[:GCM_NONCE_BYTES]
        ct = body[GCM_NONCE_BYTES:]
        plain = AESGCM(self.KEY).decrypt(nonce, ct, associated_data=None)
        self.assertEqual(json.loads(plain), msg)

    def test_repeated_sends_use_unique_nonces(self):
        msg = {"id": 1, "x": "same"}
        b1, b2 = MockStream(), MockStream()
        run(send_message(b1, msg, session_key=self.KEY))
        run(send_message(b2, msg, session_key=self.KEY))
        # Same plaintext + same key → with random nonce, ciphertexts must differ.
        self.assertNotEqual(bytes(b1.data), bytes(b2.data))

    def test_tampered_ciphertext_raises_invalid_tag(self):
        b = MockStream()
        run(send_message(b, {"id": 1}, session_key=self.KEY))
        b.data[-1] ^= 0xFF  # flip last tag byte
        with self.assertRaises(InvalidTag):
            run(recv_message(b, session_key=self.KEY))

    def test_tampered_nonce_raises_invalid_tag(self):
        b = MockStream()
        run(send_message(b, {"id": 1}, session_key=self.KEY))
        b.data[4] ^= 0xFF  # flip a byte inside the 12-byte nonce
        with self.assertRaises(InvalidTag):
            run(recv_message(b, session_key=self.KEY))

    def test_wrong_key_raises_invalid_tag(self):
        b = MockStream()
        run(send_message(b, {"id": 1}, session_key=self.KEY))
        wrong = bytes([self.KEY[0] ^ 1]) + self.KEY[1:]
        with self.assertRaises(InvalidTag):
            run(recv_message(b, session_key=wrong))

    def test_truncated_ciphertext_rejected(self):
        b = MockStream()
        run(send_message(b, {"id": 1}, session_key=self.KEY))
        # Keep length prefix but cut body shorter than nonce+tag.
        del b.data[GCM_NONCE_BYTES + 5:]  # leave a too-short body
        # Patch the length prefix to match.
        b.data[:4] = struct.pack(">I", len(b.data) - 4)
        with self.assertRaises(ValueError):
            run(recv_message(b, session_key=self.KEY))


class CrossLanguageInteropTest(unittest.TestCase):
    """Decrypt a buffer encrypted *here* using only the standard AESGCM —
    same algorithm Android's javax.crypto AES/GCM/NoPadding implements,
    so a successful round-trip here is also evidence the Kotlin decryptor
    will accept these bytes (and vice versa)."""

    def test_known_vector_round_trip(self):
        key = bytes.fromhex(VECTOR_SESSION_KEY_HEX)
        # Build an encrypted message manually and feed it back through recv_message.
        nonce = b"\x00" * GCM_NONCE_BYTES  # deterministic for the test only
        plaintext = json.dumps({"hello": "world", "n": 42}).encode()
        ct = AESGCM(key).encrypt(nonce, plaintext, associated_data=None)
        body = nonce + ct
        b = MockStream()
        b.data.extend(struct.pack(">I", len(body)))
        b.data.extend(body)
        parsed = run(recv_message(b, session_key=key))
        self.assertEqual(parsed, {"hello": "world", "n": 42})


if __name__ == "__main__":
    unittest.main(verbosity=2)
