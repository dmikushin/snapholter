#!/usr/bin/env python3
"""
SnapECG Holter Connector — PC bridge between AI agent and Android app.

Listens on port 8365 for Android app connections.
Exposes MCP tools for agent interaction.
Handles discovery, pairing, and request bridging.

Usage:
    python connector.py                  # start connector daemon
    python connector.py --pair           # pair with a new device
    python connector.py --mcp            # run as MCP server (stdio)
"""

import asyncio
import hmac
import json
import secrets
import sys
import time
import socket
import signal

# Add parent dir for snapecg package
sys.path.insert(0, '..')
sys.path.insert(0, '../src')

from protocol import (
    CONNECTOR_PORT, PAIRING_CODE_LENGTH, AppConnection, DiscoveryListener,
    PairingStore, derive_session_key, format_pairing_code_for_display,
    is_valid_pairing_code, normalize_pairing_code, send_message, recv_message,
)


class HolterConnector:
    """Main connector: manages app connection and exposes tools."""

    def __init__(self):
        self.app: AppConnection | None = None
        self.discovery = DiscoveryListener()
        self._running = False
        self._broadcast_task = None
        self._server = None
        # Set when an Android app's TCP connection has been accepted; cleared
        # on disconnect. Lets `pair()` wait for the app instead of trying to
        # dial it (the phone is the TCP client in this protocol).
        self._app_connected: asyncio.Event = asyncio.Event()
        # Persistent storage of session keys per phone IP, so a quick
        # reconnect (Wi-Fi drop, app restart) skips the code re-entry.
        self.pairing_store = PairingStore()

    # --- Discovery ---

    async def discover(self, timeout: float = 5.0) -> list[dict]:
        """Find Android apps on local network by scanning subnet for port 8365."""
        import ipaddress
        import asyncio

        # Get local IP and subnet
        import subprocess
        result = subprocess.run(['ip', '-4', 'route', 'show', 'default'],
                                capture_output=True, text=True)
        # Find local network interface IP
        iface_result = subprocess.run(['ip', '-4', 'addr', 'show'],
                                      capture_output=True, text=True)
        local_ips = []
        for line in iface_result.stdout.split('\n'):
            line = line.strip()
            if line.startswith('inet ') and '127.0.0.' not in line:
                parts = line.split()
                local_ips.append(parts[1])  # e.g. "192.168.1.123/24"

        devices = []
        for cidr in local_ips:
            try:
                network = ipaddress.ip_network(cidr, strict=False)
            except ValueError:
                continue

            # Skip large subnets
            if network.num_addresses > 1024:
                continue

            async def probe(ip):
                try:
                    _, writer = await asyncio.wait_for(
                        asyncio.open_connection(str(ip), CONNECTOR_PORT),
                        timeout=0.5
                    )
                    writer.close()
                    await writer.wait_closed()
                    return str(ip)
                except (asyncio.TimeoutError, OSError):
                    return None

            # Scan all hosts in parallel
            tasks = [probe(ip) for ip in network.hosts()]
            results = await asyncio.gather(*tasks)
            my_ip = cidr.split('/')[0]
            for ip in results:
                if ip and ip != my_ip:
                    devices.append({
                        'name': ip,
                        'address': ip,
                        'port': CONNECTOR_PORT,
                        'version': '?',
                    })

        return devices

    # --- Pairing ---

    async def _try_resume(self) -> dict | None:
        """If we have a stored key for this peer, try `resume` and skip
        code re-entry. Returns the result dict on success, None if no
        saved key or the phone rejected it (caller should fall back to
        fresh pair)."""
        peer = self.app.address if self.app else None
        if not peer:
            return None
        saved = self.pairing_store.load_if_fresh(peer)
        if not saved:
            return None
        salt = secrets.token_bytes(16)
        proof = hmac.new(saved, salt, 'sha256').hexdigest()
        try:
            result = await self.app.call('resume', {
                'salt': salt.hex(),
                'proof': proof,
            })
        except Exception as e:
            print(f"  resume RPC failed: {e} — falling back to fresh pair")
            return None
        if result.get('status') == 'resumed':
            self.app.session_key = saved
            self.app.paired = True
            print(f"  Resumed previous pairing with {peer}")
            return {'status': 'paired', 'address': peer, 'resumed': True}
        # Phone said needs_pair (key drift / TTL expired / no record).
        # Clear our stale entry to avoid retrying it on every reconnect.
        print(f"  Resume rejected: {result.get('error', 'unknown')}; will pair fresh")
        self.pairing_store.forget(peer)
        return None

    async def pair(self, address: str | None = None,
                   port: int = CONNECTOR_PORT,
                   code: str | None = None,
                   wait_seconds: float = 60.0) -> dict:
        """Pair with the Android app over its TCP connection to us.

        First attempts a silent `resume` using a saved session key (if
        one exists for this peer within the TTL window); falls back to
        the interactive code prompt + HMAC pair flow on resume failure.
        """
        # Wait for the app to dial us, if it hasn't already.
        if not self.app:
            print(f"Waiting up to {int(wait_seconds)}s for the app to connect...")
            try:
                await asyncio.wait_for(self._app_connected.wait(),
                                       timeout=wait_seconds)
            except asyncio.TimeoutError:
                return {'status': 'failed',
                        'reason': 'no app connection within timeout — '
                                  'is the connector daemon running?'}
        if not self.app:
            return {'status': 'failed', 'reason': 'app vanished before pair'}

        # Try resume first — silent, no user interaction.
        resume_result = await self._try_resume()
        if resume_result is not None:
            return resume_result

        # Fresh pair: prompt for code if one wasn't supplied.
        if code is None:
            raw = input(
                "Pairing code from phone (XXXX-XXXX-XXXX-XXXX, see notification): "
            )
            code = normalize_pairing_code(raw)
        else:
            code = normalize_pairing_code(code)
        if not is_valid_pairing_code(code):
            return {'status': 'failed',
                    'reason': f'expected {PAIRING_CODE_LENGTH} chars from the '
                              f'pairing alphabet (case-insensitive, hyphens ok)'}

        salt = secrets.token_bytes(16)
        proof = hmac.new(code.encode(), salt, 'sha256').hexdigest()
        try:
            result = await self.app.call('pair', {
                'salt': salt.hex(),
                'proof': proof,
            })
        except Exception as e:
            return {'status': 'failed', 'reason': f'pair RPC failed: {e}'}

        if result.get('status') == 'paired':
            session_key = derive_session_key(code, salt)
            self.app.session_key = session_key
            self.app.paired = True
            self.pairing_store.save(self.app.address, session_key)
            return {'status': 'paired', 'address': self.app.address,
                    'resumed': False}
        return {'status': 'failed',
                'reason': result.get('error', 'phone rejected proof')}

    # --- Bridge to app ---

    async def _call_app(self, method: str, params: dict = None) -> dict:
        """Forward a call to the connected Android app."""
        if not self.app or not self.app.writer:
            raise RuntimeError("Not connected to Android app")
        return await self.app.call(method, params)

    # --- Holter tools (for MCP) ---

    async def holter_discover(self, params: dict) -> dict:
        timeout = params.get('timeout', 10)
        devices = await self.discover(timeout)
        return {'devices': devices}

    async def holter_pair(self, params: dict) -> dict:
        address = params.get('address')
        if not address:
            return {'error': 'address required'}
        return await self.pair(address)

    async def holter_get_status(self, params: dict) -> dict:
        return await self._call_app('holter.get_status')

    async def holter_check_signal(self, params: dict) -> dict:
        """Get signal sample and analyze locally."""
        n_seconds = params.get('seconds', 10)
        n_samples = n_seconds * 200

        raw = await self._call_app('holter.get_signal', {'n': n_samples})
        samples = raw.get('samples', [])

        if not samples:
            return {'quality': 'no_data', 'message': 'No ECG samples received'}

        # Local analysis using snapecg package
        try:
            from snapecg.qrs import QRSDetector
            qrs = QRSDetector()
            beats = []
            for i, s in enumerate(samples):
                hr, rr, amp = qrs.process(s)
                if rr > 0:
                    beats.append({'index': i, 'hr': hr, 'rr': rr})

            hrs = [b['hr'] for b in beats if b['hr'] > 0]
            avg_hr = sum(hrs) / len(hrs) if hrs else 0

            if not beats:
                quality = 'poor'
                message = 'No heartbeats detected. Check electrode placement.'
            elif avg_hr < 30 or avg_hr > 200:
                quality = 'suspicious'
                message = f'Unusual heart rate: {avg_hr:.0f} bpm. Verify electrode contact.'
            else:
                quality = 'good'
                message = f'Signal quality good. HR: {avg_hr:.0f} bpm, {len(beats)} beats in {n_seconds}s.'

            return {
                'quality': quality,
                'message': message,
                'heart_rate': round(avg_hr, 1),
                'num_beats': len(beats),
                'duration_seconds': n_seconds,
                'beats': beats[:10],  # first 10 for context
            }
        except ImportError:
            return {'quality': 'unknown', 'message': 'snapecg package not available for local analysis',
                    'num_samples': len(samples)}

    async def holter_verify_setup(self, params: dict) -> dict:
        """Comprehensive pre-recording verification."""
        checks = {}

        # 1. Device status
        try:
            status = await self._call_app('holter.get_status')
            checks['device_connected'] = status.get('bt_connected', False)
            checks['device_battery'] = status.get('device_battery', -1)
            checks['phone_battery'] = status.get('phone_battery', -1)
            checks['lead_off'] = status.get('lead_off', True)
            checks['storage_mb'] = status.get('free_storage_mb', 0)
        except Exception as e:
            return {'go': False, 'reason': f'Cannot reach app: {e}', 'checks': checks}

        # 2. Signal quality
        try:
            signal = await self.holter_check_signal({'seconds': 10})
            checks['signal_quality'] = signal.get('quality', 'unknown')
            checks['heart_rate'] = signal.get('heart_rate', 0)
            checks['num_beats'] = signal.get('num_beats', 0)
        except Exception as e:
            checks['signal_quality'] = 'error'
            checks['signal_error'] = str(e)

        # 3. Evaluate GO/NO-GO
        problems = []

        if not checks.get('device_connected'):
            problems.append('Device not connected via Bluetooth')

        battery = checks.get('device_battery', -1)
        min_battery = params.get('min_battery', 1)  # level 0-3; default min=1
        if 0 <= battery < min_battery:
            problems.append(f'Device battery too low: {battery}/3 (minimum {min_battery}/3)')

        if checks.get('lead_off'):
            problems.append('Electrodes not in contact (lead off)')

        if checks.get('signal_quality') == 'poor':
            problems.append('No heartbeats detected — check electrode placement')

        if checks.get('storage_mb', 0) < 100:
            problems.append(f'Insufficient phone storage: {checks.get("storage_mb")}MB (need 100MB+)')

        go = len(problems) == 0

        return {
            'go': go,
            'problems': problems if problems else None,
            'message': 'All checks passed. Ready for Holter monitoring.' if go
                       else f'{len(problems)} issue(s) found.',
            'checks': checks,
        }

    async def holter_start(self, params: dict) -> dict:
        return await self._call_app('holter.start_recording', params)

    async def holter_stop(self, params: dict) -> dict:
        return await self._call_app('holter.stop_recording')

    async def holter_add_event(self, params: dict) -> dict:
        text = params.get('text', '')
        tag = params.get('tag', 'note')
        if not text:
            return {'error': 'text required'}
        return await self._call_app('holter.add_event', {'text': text, 'tag': tag})

    async def holter_get_events(self, params: dict) -> dict:
        return await self._call_app('holter.get_events')

    async def holter_download(self, params: dict) -> dict:
        return await self._call_app('holter.get_recording')

    async def holter_get_summary(self, params: dict) -> dict:
        return await self._call_app('holter.get_summary')

    # --- Tool registry ---

    TOOLS = {
        'holter_discover': ('Find Android app on local network', {'timeout': 'Scan duration (default 10s)'}),
        'holter_pair': ('Pair with Android app', {'address': 'IP address of the phone'}),
        'holter_get_status': ('Get device and recording status', {}),
        'holter_check_signal': ('Get ECG signal and analyze quality', {'seconds': 'Duration (default 10)'}),
        'holter_verify_setup': ('Pre-recording verification (GO/NO-GO)', {'min_battery': 'Min battery level 0-3 (default 1)'}),
        'holter_start': ('Start Holter recording', {}),
        'holter_stop': ('Stop Holter recording', {}),
        'holter_add_event': ('Add event to patient diary', {'text': 'Event description', 'tag': 'Category tag'}),
        'holter_get_events': ('Get event diary', {}),
        'holter_download': ('Download full recording', {}),
        'holter_get_summary': ('Get recording summary without full download', {}),
    }

    async def handle_request(self, request: dict) -> dict:
        """Handle a single tool request."""
        method = request.get('tool') or request.get('method', '')
        params = request.get('params', request.get('arguments', {}))

        if method == 'list_tools':
            return {'tools': [{'name': k, 'description': v[0], 'parameters': v[1]}
                              for k, v in self.TOOLS.items()]}

        handler = getattr(self, method, None)
        if handler and method.startswith('holter_'):
            try:
                return await handler(params or {})
            except Exception as e:
                return {'error': str(e)}

        return {'error': f'Unknown tool: {method}'}

    # --- Broadcast loop ---

    async def _broadcast_loop(self):
        """Periodically broadcast connector presence on the network."""
        while self._running:
            self.discovery.broadcast_presence()
            await asyncio.sleep(2.0)

    # --- Server for incoming app connections ---

    async def _handle_app_connection(self, reader, writer):
        """Hold the incoming TCP connection from the Android app.

        Important: we deliberately do NOT call recv_message here. The
        actual RPC traffic is consumed by `AppConnection.call()` against
        the same StreamReader; if this handler also read, the two would
        race over each frame and bytes would go to whichever happened
        to be scheduled first.

        Instead we just publish the connection on `self.app`, signal
        `_app_connected`, and park until the peer closes (detected via
        `reader.at_eof()`, which is set by asyncio's protocol layer when
        the transport hits FIN/RST — no read required).
        """
        addr = writer.get_extra_info('peername')
        print(f"App connected from {addr}")

        self.app = AppConnection(
            address=addr[0], port=addr[1],
            reader=reader, writer=writer,
        )
        self._app_connected.set()

        try:
            while self._running and not reader.at_eof():
                await asyncio.sleep(1.0)
        finally:
            print(f"App disconnected from {addr}")
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass
            self.app = None
            self._app_connected.clear()

    async def start_server(self):
        """Start TCP server and broadcast loop."""
        self._running = True
        self._server = await asyncio.start_server(
            self._handle_app_connection,
            '0.0.0.0', CONNECTOR_PORT,
        )
        self._broadcast_task = asyncio.create_task(self._broadcast_loop())

        # Discover the real outbound-capable IP. socket.gethostbyname(
        # gethostname()) returns 127.0.1.1 on most Linux distributions
        # because /etc/hosts maps the hostname to a loopback alias —
        # which would advertise the connector as unreachable.
        # Connecting a UDP socket (no packets sent) lets the kernel
        # pick the actual interface for routing to a public destination.
        local_ip = "127.0.0.1"
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
                probe.connect(("8.8.8.8", 80))
                local_ip = probe.getsockname()[0]
        except OSError:
            pass

        print(f"SnapECG Holter Connector")
        print(f"  Listening on {local_ip}:{CONNECTOR_PORT}")
        print(f"  Broadcasting presence on UDP {CONNECTOR_PORT}")
        print(f"  Waiting for Android app...\n")

        async with self._server:
            await self._server.serve_forever()

    def stop(self):
        self._running = False
        if self._broadcast_task:
            self._broadcast_task.cancel()
        if self._server:
            self._server.close()

    # --- MCP stdio mode ---

    async def run_mcp(self):
        """Run as MCP server on stdio."""
        self._running = True
        self._broadcast_task = asyncio.create_task(self._broadcast_loop())

        # Also start TCP server for incoming app connections
        self._server = await asyncio.start_server(
            self._handle_app_connection, '0.0.0.0', CONNECTOR_PORT,
        )

        loop = asyncio.get_running_loop()
        reader = asyncio.StreamReader()
        await loop.connect_read_pipe(
            lambda: asyncio.StreamReaderProtocol(reader), sys.stdin)

        while self._running:
            try:
                line = await reader.readline()
                if not line:
                    break
                request = json.loads(line.decode().strip())
                response = await self.handle_request(request)
                sys.stdout.write(json.dumps(response) + '\n')
                sys.stdout.flush()
            except json.JSONDecodeError:
                sys.stdout.write(json.dumps({'error': 'invalid JSON'}) + '\n')
                sys.stdout.flush()
            except Exception as e:
                sys.stdout.write(json.dumps({'error': str(e)}) + '\n')
                sys.stdout.flush()


    async def run_pair_workflow(self):
        """One-shot: start the broadcast/server stack, wait for the
        Android app to dial in, prompt for the code, complete pairing,
        then shut down. Used by `connector.py --pair`."""
        self._running = True
        self._server = await asyncio.start_server(
            self._handle_app_connection, '0.0.0.0', CONNECTOR_PORT,
        )
        self._broadcast_task = asyncio.create_task(self._broadcast_loop())
        print("Connector listening; waiting for app to dial in...")

        try:
            result = await self.pair(address=None)
            print(json.dumps(result, indent=2))
            return result
        finally:
            self.stop()


def main():
    import argparse

    parser = argparse.ArgumentParser(description="SnapECG Holter Connector")
    parser.add_argument("--mcp", action="store_true", help="Run as MCP server (stdio)")
    parser.add_argument("--discover", action="store_true", help="Discover devices and exit")
    parser.add_argument("--pair", action="store_true",
                        help="Run a one-shot pairing workflow and exit")
    args = parser.parse_args()

    connector = HolterConnector()

    def handle_signal(sig, frame):
        connector.stop()
        sys.exit(0)

    signal.signal(signal.SIGINT, handle_signal)

    if args.discover:
        devices = asyncio.run(connector.discover(timeout=10))
        if devices:
            for d in devices:
                print(f"  {d['name']} [{d['address']}:{d['port']}] v{d['version']}")
        else:
            print("  No devices found.")
    elif args.pair:
        result = asyncio.run(connector.run_pair_workflow())
        sys.exit(0 if result.get('status') == 'paired' else 1)
    elif args.mcp:
        asyncio.run(connector.run_mcp())
    else:
        asyncio.run(connector.start_server())


if __name__ == '__main__':
    main()
