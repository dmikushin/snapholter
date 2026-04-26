"""
SnapECG Holter Connector — wire protocol between PC and Android app.

Message format (JSON over TCP with length prefix):
  [4 bytes: length (big-endian uint32)] [JSON payload]

Request:  {"id": 1, "method": "holter.get_status", "params": {}}
Response: {"id": 1, "result": {...}}
Error:    {"id": 1, "error": {"code": -1, "message": "..."}}

Discovery: UDP broadcast on port 8365
  → App sends: {"type": "snapecg_holter", "name": "Pixel 7", "version": "1.0"}
  ← Connector sends: {"type": "snapecg_connector", "name": "hostname", "port": 8365}

Pairing: 6-digit code displayed on both sides, user confirms on phone.
  After confirmation, TLS-PSK key derived from shared secret.
"""

import asyncio
import hashlib
import hmac
import json
import os
import secrets
import socket
import struct
import time
from dataclasses import dataclass, field
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM


# --- Constants ---

CONNECTOR_PORT = 8365
DISCOVERY_PORT = 8365
PROTOCOL_VERSION = "1.0"
# Cap inbound message size. A real ECG strip request returns ~80 KB
# (10 s × 200 Hz × 4 bytes JSON-encoded + framing); 1 MB leaves an
# order of magnitude of headroom while killing the trivial DoS where
# an attacker on the LAN sends a multi-GB length prefix and forces
# Python's readexactly to allocate the whole thing.
MAX_MESSAGE_BYTES = 1 * 1024 * 1024
# Pairing-code alphabet and length. We use a 30-char alphabet that drops
# the visually-confusable 0/O, 1/I/L, and U (commonly mistyped as V).
# 16 chars => 30**16 ≈ 2**78.6 entropy, infeasible to offline-brute-force
# from a captured {salt, HMAC(code, salt)} tuple. The previous 6-digit
# codes (10**6 ≈ 2**20) collapsed in < 1 s on a laptop.
PAIRING_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"  # 30 chars
PAIRING_CODE_LENGTH = 16
DISCOVERY_MAGIC = b"SNAPECG_DISCOVER"
DISCOVERY_INTERVAL = 2.0  # seconds between broadcasts
GCM_NONCE_BYTES = 12       # 96-bit nonce per NIST SP 800-38D
PAIRING_KEY_TTL_SECONDS = 7 * 24 * 60 * 60  # 7 days, matches Android PairingStore


def normalize_pairing_code(s: str) -> str:
    """Strip whitespace + hyphens, uppercase. Lets the user type the
    code with the visual grouping (XXXX-XXXX-XXXX-XXXX) we display."""
    return "".join(c for c in s.upper() if c.isalnum())


def is_valid_pairing_code(code: str) -> bool:
    if len(code) != PAIRING_CODE_LENGTH:
        return False
    return all(c in PAIRING_CODE_ALPHABET for c in code)


def format_pairing_code_for_display(code: str) -> str:
    """Insert hyphens every 4 chars: 'ABCDEFGH...' -> 'ABCD-EFGH-...'."""
    return "-".join(code[i:i + 4] for i in range(0, len(code), 4))


# --- Pairing persistence (PC side) ---

# Optional dependency: python-keyring. When available we store the AES-GCM
# session key in the OS keyring (Secret Service / Keychain / Credential
# Manager) so it lives encrypted at rest behind the user's login session.
# When unavailable we transparently fall back to the JSON file with mode
# 0600 — fine on a single-user laptop, vulnerable to live-USB / single-
# user-mode disk reads on a multi-user / clinical workstation.
try:
    import keyring as _keyring  # noqa: F401
    from keyring.errors import KeyringError as _KeyringError  # noqa: F401
    _HAS_KEYRING = True
except Exception:  # pragma: no cover — exercised only when the dep is absent
    _HAS_KEYRING = False
    _KeyringError = Exception  # type: ignore[assignment, misc]


_KEYRING_SERVICE = "snapecg-connector"


class PairingStore:
    """Per-peer session-key store, mirroring the Android-side PairingStore.

    Storage strategy:
      1. **OS keyring (preferred)**: under service name "snapecg-connector",
         account is the phone IP, value is the hex session key. The
         metadata file at ~/.config/snapecg/pairing.json still records
         {address: ts} so we can apply the 7-day TTL without unlocking
         the keyring on every request.
      2. **Plain JSON fallback**: when python-keyring is missing OR the
         backend rejects the write (e.g. headless server with no
         secret-service daemon), we write {address: {key_hex, ts}} to
         the same JSON file at mode 0600. A warning is printed at first
         save so the operator knows their session keys are at rest in
         plaintext.

    Either way, callers see the same .save() / .load_if_fresh() / .forget()
    API. A quick reconnect hits keyring (or fallback file) and skips the
    code re-entry.
    """

    def __init__(self, path: Path | None = None):
        self.path = path or (
            Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config")
            / "snapecg" / "pairing.json"
        )
        # Tracks whether we've already warned about plaintext fallback so we
        # don't spam the operator on every save.
        self._fallback_warned = False

    # ---- Internal helpers ----------------------------------------------

    def _load_metadata(self) -> dict:
        if not self.path.exists():
            return {}
        try:
            return json.loads(self.path.read_text())
        except (OSError, json.JSONDecodeError):
            return {}

    def _save_metadata(self, data: dict):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(data, indent=2))
        try:
            os.chmod(self.path, 0o600)
        except OSError:
            pass

    def _keyring_save(self, peer_address: str, key_hex: str) -> bool:
        """Store the key in the OS keyring. Returns True on success."""
        if not _HAS_KEYRING:
            return False
        try:
            _keyring.set_password(_KEYRING_SERVICE, peer_address, key_hex)
            return True
        except (_KeyringError, Exception):  # be conservative
            return False

    def _keyring_load(self, peer_address: str) -> bytes | None:
        if not _HAS_KEYRING:
            return None
        try:
            hexv = _keyring.get_password(_KEYRING_SERVICE, peer_address)
        except (_KeyringError, Exception):
            return None
        if not hexv:
            return None
        try:
            return bytes.fromhex(hexv)
        except ValueError:
            return None

    def _keyring_delete(self, peer_address: str):
        if not _HAS_KEYRING:
            return
        try:
            _keyring.delete_password(_KEYRING_SERVICE, peer_address)
        except (_KeyringError, Exception):
            pass

    # ---- Public API -----------------------------------------------------

    def save(self, peer_address: str, session_key: bytes):
        """Persist the (peer_address, session_key) tuple. Stores the key in
        the OS keyring when available; falls back to the metadata file."""
        meta = self._load_metadata()
        # Wipe stale fallback entry so we never have a plaintext copy AND a
        # keyring copy disagreeing about the value.
        meta.pop(peer_address, None)

        if self._keyring_save(peer_address, session_key.hex()):
            meta[peer_address] = {"ts": time.time(), "where": "keyring"}
        else:
            if not self._fallback_warned:
                import sys as _sys
                _sys.stderr.write(
                    "snapecg PairingStore: OS keyring unavailable, falling "
                    "back to plaintext file at " + str(self.path) +
                    " (mode 0600). Install python-keyring + a Secret Service "
                    "backend for at-rest encryption.\n"
                )
                self._fallback_warned = True
            meta[peer_address] = {
                "key_hex": session_key.hex(),
                "ts": time.time(),
                "where": "file",
            }

        self._save_metadata(meta)

    def load_if_fresh(self, peer_address: str) -> bytes | None:
        entry = self._load_metadata().get(peer_address)
        if not entry:
            return None
        if time.time() - entry.get("ts", 0) > PAIRING_KEY_TTL_SECONDS:
            return None
        if entry.get("where") == "keyring":
            return self._keyring_load(peer_address)
        # Legacy or fallback entry: read from the metadata file.
        try:
            return bytes.fromhex(entry["key_hex"])
        except (KeyError, ValueError):
            return None

    def forget(self, peer_address: str):
        """Remove both the keyring entry and the metadata so the next
        connect from this peer falls back to fresh pair."""
        self._keyring_delete(peer_address)
        meta = self._load_metadata()
        meta.pop(peer_address, None)
        self._save_metadata(meta)


# --- Message framing ---

async def send_message(writer: asyncio.StreamWriter, msg: dict,
                       session_key: bytes | None = None):
    """Send length-prefixed JSON message.

    If `session_key` is given, the JSON payload is encrypted with AES-GCM
    before framing: wire layout becomes
        [4 bytes length] [12 bytes nonce] [ciphertext + 16-byte GCM tag].
    Plaintext-mode wire layout:
        [4 bytes length] [JSON bytes].
    """
    payload = json.dumps(msg, ensure_ascii=False).encode('utf-8')
    if session_key is not None:
        nonce = secrets.token_bytes(GCM_NONCE_BYTES)
        ct = AESGCM(session_key).encrypt(nonce, payload, associated_data=None)
        payload = nonce + ct
    writer.write(struct.pack('>I', len(payload)))
    writer.write(payload)
    await writer.drain()


async def recv_message(reader: asyncio.StreamReader,
                       session_key: bytes | None = None) -> dict | None:
    """Receive length-prefixed JSON message (encrypted if session_key given)."""
    header = await reader.readexactly(4)
    length = struct.unpack('>I', header)[0]
    if length > MAX_MESSAGE_BYTES:
        return None
    payload = await reader.readexactly(length)
    if session_key is not None:
        if len(payload) < GCM_NONCE_BYTES + 16:
            raise ValueError("ciphertext shorter than nonce + GCM tag")
        nonce, ct = payload[:GCM_NONCE_BYTES], payload[GCM_NONCE_BYTES:]
        payload = AESGCM(session_key).decrypt(nonce, ct, associated_data=None)
    return json.loads(payload.decode('utf-8'))


# --- Pairing ---

def generate_pairing_code() -> str:
    """Generate a random 6-digit pairing code."""
    return ''.join(str(secrets.randbelow(10)) for _ in range(PAIRING_CODE_LENGTH))


def derive_session_key(code: str, salt: bytes) -> bytes:
    """Derive encryption key from pairing code + salt."""
    return hashlib.pbkdf2_hmac('sha256', code.encode(), salt, 100000, dklen=32)


# --- Discovery ---

@dataclass
class DiscoveredDevice:
    """A discovered Android app on the network."""
    name: str
    address: str
    port: int
    version: str
    discovered_at: float = field(default_factory=time.time)


class DiscoveryListener:
    """Listens for UDP discovery broadcasts from Android app."""

    def __init__(self, port: int = DISCOVERY_PORT):
        self.port = port
        self.devices: dict[str, DiscoveredDevice] = {}

    async def listen(self, duration: float = 5.0) -> list[DiscoveredDevice]:
        """Listen for discovery broadcasts for `duration` seconds."""
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.bind(('', self.port))
        sock.setblocking(False)

        loop = asyncio.get_running_loop()
        end_time = time.time() + duration

        while time.time() < end_time:
            try:
                data, addr = await asyncio.wait_for(
                    loop.sock_recvfrom(sock, 1024),
                    timeout=min(1.0, end_time - time.time())
                )
                try:
                    msg = json.loads(data.decode())
                    if msg.get('type') == 'snapecg_holter':
                        dev = DiscoveredDevice(
                            name=msg.get('name', 'Unknown'),
                            address=addr[0],
                            port=msg.get('port', CONNECTOR_PORT),
                            version=msg.get('version', '?'),
                        )
                        self.devices[addr[0]] = dev
                except (json.JSONDecodeError, KeyError):
                    pass
            except asyncio.TimeoutError:
                continue

        sock.close()
        return list(self.devices.values())

    def broadcast_presence(self):
        """Broadcast connector presence (called by connector)."""
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        msg = json.dumps({
            'type': 'snapecg_connector',
            'name': socket.gethostname(),
            'port': CONNECTOR_PORT,
            'version': PROTOCOL_VERSION,
        }).encode()
        try:
            sock.sendto(msg, ('<broadcast>', self.port))
        except OSError:
            pass
        finally:
            sock.close()


# --- App Connection ---

@dataclass
class AppConnection:
    """Active connection to the Android app.

    The phone is always the TCP client in this protocol — it dials us
    after seeing our UDP discovery broadcast — so this class is only
    ever populated by the server side accepting an incoming connection
    (see HolterConnector._handle_app_connection). It does NOT know how
    to dial the phone; the phone has no listening socket on 8365.
    """
    address: str
    port: int
    reader: asyncio.StreamReader | None = None
    writer: asyncio.StreamWriter | None = None
    paired: bool = False
    session_key: bytes | None = None
    _request_id: int = 0

    async def pair(self, code: str) -> bool:
        """
        Execute pairing handshake.
        Both sides display `code`, user confirms on phone.
        """
        salt = secrets.token_bytes(16)
        await send_message(self.writer, {
            'method': 'pair',
            'params': {
                'salt': salt.hex(),
                'proof': hmac.new(code.encode(), salt, 'sha256').hexdigest(),
            }
        })

        response = await recv_message(self.reader)
        if response and response.get('result', {}).get('status') == 'paired':
            self.session_key = derive_session_key(code, salt)
            self.paired = True
            return True
        return False

    async def call(self, method: str, params: dict | None = None) -> dict:
        """Send RPC request to the app and wait for response.

        Once paired, the session_key is used to AES-GCM encrypt every
        message in both directions. The `pair` method itself goes
        plaintext (handled by `pair()` below), since the key is only
        established by that exchange.
        """
        self._request_id += 1
        req = {
            'id': self._request_id,
            'method': method,
            'params': params or {},
        }
        key = self.session_key if self.paired else None
        await send_message(self.writer, req, session_key=key)
        response = await recv_message(self.reader, session_key=key)

        if response is None:
            raise ConnectionError("No response from app")
        if 'error' in response:
            raise RuntimeError(response['error'].get('message', 'Unknown error'))
        return response.get('result', {})
