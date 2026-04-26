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
import os
import struct
import sys
import tempfile
import time
import unittest
from pathlib import Path

from cryptography.exceptions import InvalidTag

from protocol import (
    AESGCM,
    GCM_NONCE_BYTES,
    PAIRING_CODE_ALPHABET,
    PAIRING_CODE_LENGTH,
    PAIRING_KEY_TTL_SECONDS,
    PairingStore,
    format_pairing_code_for_display,
    is_valid_pairing_code,
    normalize_pairing_code,
    send_message,
    recv_message,
)


# ---------------------------------------------------------------------------
# Shared interop test vectors (must match ConnectorCryptoTest.kt byte-for-byte)
# ---------------------------------------------------------------------------

# 16-char pairing code from the new high-entropy alphabet (replacing the
# previous 6-digit "123456"). Both Kotlin and Python tests pin these
# expected outputs so any drift breaks both suites simultaneously.
VECTOR_CODE = "XK4P9VR2J7TMQH3W"
VECTOR_SALT_HEX = "00112233445566778899aabbccddeeff"
VECTOR_SALT = bytes.fromhex(VECTOR_SALT_HEX)
VECTOR_PROOF_HEX = (
    "b6325a957950828127e023cce3492f32"
    "fe8051d37ed969397dce3e3ca9fdc653"
)
VECTOR_SESSION_KEY_HEX = (
    "21a0bde826116e3dd56caf15501358a9"
    "54fc337f77831b36ffdc8428f6fd6175"
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
        self.assertEqual(PAIRING_CODE_LENGTH, 16)


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


class PairingStorePersistenceTest(unittest.TestCase):
    """PairingStore round-trip, TTL expiry, peer isolation, mode-0600 perms."""

    def setUp(self):
        self.tmpdir = tempfile.TemporaryDirectory()
        self.path = Path(self.tmpdir.name) / "pairing.json"
        self.store = PairingStore(self.path)

    def tearDown(self):
        self.tmpdir.cleanup()

    def test_save_then_load_roundtrip(self):
        key = bytes.fromhex(VECTOR_SESSION_KEY_HEX)
        self.store.save("192.168.1.42", key)
        self.assertEqual(self.store.load_if_fresh("192.168.1.42"), key)

    def test_load_returns_none_when_missing(self):
        self.assertIsNone(self.store.load_if_fresh("nope"))

    def test_load_isolates_by_peer_address(self):
        self.store.save("10.0.0.1", b"\x01" * 32)
        self.store.save("10.0.0.2", b"\x02" * 32)
        self.assertEqual(self.store.load_if_fresh("10.0.0.1"), b"\x01" * 32)
        self.assertEqual(self.store.load_if_fresh("10.0.0.2"), b"\x02" * 32)
        self.assertIsNone(self.store.load_if_fresh("10.0.0.3"))

    def test_load_returns_none_when_expired(self):
        # Write directly with an old timestamp.
        old_ts = time.time() - PAIRING_KEY_TTL_SECONDS - 60
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps({
            "stale-host": {"key_hex": "00" * 32, "ts": old_ts},
        }))
        self.assertIsNone(self.store.load_if_fresh("stale-host"))

    def test_forget_clears_just_one_entry(self):
        self.store.save("a", b"\xaa" * 32)
        self.store.save("b", b"\xbb" * 32)
        self.store.forget("a")
        self.assertIsNone(self.store.load_if_fresh("a"))
        self.assertEqual(self.store.load_if_fresh("b"), b"\xbb" * 32)

    def test_save_uses_restrictive_file_permissions(self):
        if os.name != "posix":
            self.skipTest("POSIX permission semantics only")
        self.store.save("p", b"\x00" * 32)
        mode = os.stat(self.path).st_mode & 0o777
        self.assertEqual(mode, 0o600,
                         f"pairing.json should be owner-read/write only, got {oct(mode)}")

    def test_corrupt_file_yields_empty_result(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text("{not valid json")
        # Should not raise; just behaves as empty store.
        self.assertIsNone(self.store.load_if_fresh("anything"))


class PairingCodeFormatTest(unittest.TestCase):
    """High-entropy pairing-code normalization, formatting, and validation."""

    def test_alphabet_is_30_unambiguous_chars(self):
        # No 0/O, no 1/I/L, no U.
        self.assertEqual(len(PAIRING_CODE_ALPHABET), 30)
        for forbidden in "0O1ILU":
            self.assertNotIn(forbidden, PAIRING_CODE_ALPHABET,
                             f"alphabet should not contain '{forbidden}'")
        # All upper-case alphanumeric.
        self.assertTrue(PAIRING_CODE_ALPHABET.isalnum())
        self.assertEqual(PAIRING_CODE_ALPHABET, PAIRING_CODE_ALPHABET.upper())

    def test_normalize_strips_hyphens_and_uppercases(self):
        self.assertEqual(normalize_pairing_code("xk4p-9vr2-j7tm-qh3w"),
                         "XK4P9VR2J7TMQH3W")
        self.assertEqual(normalize_pairing_code("  xk4p 9vr2  "),
                         "XK4P9VR2")
        self.assertEqual(normalize_pairing_code("XK4P\t9VR2\nJ7TMQH3W"),
                         "XK4P9VR2J7TMQH3W")

    def test_format_inserts_hyphens_every_4_chars(self):
        self.assertEqual(format_pairing_code_for_display("XK4P9VR2J7TMQH3W"),
                         "XK4P-9VR2-J7TM-QH3W")

    def test_is_valid_accepts_known_vector(self):
        self.assertTrue(is_valid_pairing_code(VECTOR_CODE))

    def test_is_valid_rejects_wrong_length(self):
        self.assertFalse(is_valid_pairing_code("XK4P9VR2"))            # too short
        self.assertFalse(is_valid_pairing_code("XK4P9VR2J7TMQH3WX"))   # too long

    def test_is_valid_rejects_chars_outside_alphabet(self):
        self.assertFalse(is_valid_pairing_code("XK4P9VR2J7TMQH3O"))    # O
        self.assertFalse(is_valid_pairing_code("XK4P9VR2J7TMQH31"))    # 1
        self.assertFalse(is_valid_pairing_code("XK4P9VR2J7TMQH3L"))    # L

    def test_is_valid_rejects_lowercase(self):
        # Caller is expected to normalize() first; raw lowercase fails.
        self.assertFalse(is_valid_pairing_code("xk4p9vr2j7tmqh3w"))

    def test_entropy_envelope(self):
        # 30**16 must exceed 2**78 — sanity-check the entropy claim in the
        # commit message and the docstring.
        import math
        bits = 16 * math.log2(30)
        self.assertGreater(bits, 78)


if __name__ == "__main__":
    unittest.main(verbosity=2)
