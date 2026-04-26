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
import secrets
import socket
import struct
import time
from dataclasses import dataclass, field

from cryptography.hazmat.primitives.ciphers.aead import AESGCM


# --- Constants ---

CONNECTOR_PORT = 8365
DISCOVERY_PORT = 8365
PROTOCOL_VERSION = "1.0"
PAIRING_CODE_LENGTH = 6
DISCOVERY_MAGIC = b"SNAPECG_DISCOVER"
DISCOVERY_INTERVAL = 2.0  # seconds between broadcasts
GCM_NONCE_BYTES = 12       # 96-bit nonce per NIST SP 800-38D


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
    if length > 10 * 1024 * 1024:  # 10MB max
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
    """Active connection to the Android app."""
    address: str
    port: int
    reader: asyncio.StreamReader | None = None
    writer: asyncio.StreamWriter | None = None
    paired: bool = False
    session_key: bytes | None = None
    _request_id: int = 0

    async def connect(self):
        """Establish TCP connection to the app."""
        self.reader, self.writer = await asyncio.open_connection(self.address, self.port)

    async def disconnect(self):
        """Close connection."""
        if self.writer:
            self.writer.close()
            try:
                await self.writer.wait_closed()
            except Exception:
                pass
            self.writer = None
            self.reader = None

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
