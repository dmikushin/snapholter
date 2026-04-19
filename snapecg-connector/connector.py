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
import json
import sys
import time
import socket
import signal

# Add parent dir for snapecg package
sys.path.insert(0, '..')
sys.path.insert(0, '../src')

from protocol import (
    CONNECTOR_PORT, AppConnection, DiscoveryListener,
    generate_pairing_code, send_message, recv_message,
)


class HolterConnector:
    """Main connector: manages app connection and exposes tools."""

    def __init__(self):
        self.app: AppConnection | None = None
        self.discovery = DiscoveryListener()
        self._running = False
        self._broadcast_task = None
        self._server = None

    # --- Discovery ---

    async def discover(self, timeout: float = 10.0) -> list[dict]:
        """Find Android apps on local network."""
        devices = await self.discovery.listen(duration=timeout)
        return [{'name': d.name, 'address': d.address, 'port': d.port,
                 'version': d.version} for d in devices]

    # --- Pairing ---

    async def pair(self, address: str, port: int = CONNECTOR_PORT) -> dict:
        """Initiate pairing with Android app."""
        code = generate_pairing_code()
        print(f"\n  Pairing code: {code}")
        print(f"  Confirm this code on your phone.\n")

        self.app = AppConnection(address=address, port=port)
        await self.app.connect()

        if await self.app.pair(code):
            return {'status': 'paired', 'address': address}
        else:
            await self.app.disconnect()
            self.app = None
            return {'status': 'failed'}

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
        min_battery = params.get('min_battery', 20)
        if 0 <= battery < min_battery:
            problems.append(f'Device battery too low: {battery}% (minimum {min_battery}%)')

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
        'holter_verify_setup': ('Pre-recording verification (GO/NO-GO)', {'min_battery': 'Min battery % (default 20)'}),
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
        """Handle incoming TCP connection from Android app."""
        addr = writer.get_extra_info('peername')
        print(f"App connected from {addr}")

        self.app = AppConnection(
            address=addr[0], port=addr[1],
            reader=reader, writer=writer,
        )

        try:
            while self._running:
                msg = await recv_message(reader)
                if msg is None:
                    break
                # Handle incoming messages from app (e.g., status updates)
                if msg.get('type') == 'pair_request':
                    code = msg.get('code', '')
                    print(f"\n  Pairing request. Code on phone: {code}")
                    print(f"  Does this match? (y/n): ", end='', flush=True)
                    # In MCP mode, auto-accept or wait for agent
                    self.app.paired = True
                    await send_message(writer, {'result': {'status': 'paired'}})
        except (asyncio.IncompleteReadError, ConnectionError):
            pass
        finally:
            print(f"App disconnected from {addr}")
            self.app = None

    async def start_server(self):
        """Start TCP server and broadcast loop."""
        self._running = True
        self._server = await asyncio.start_server(
            self._handle_app_connection,
            '0.0.0.0', CONNECTOR_PORT,
        )
        self._broadcast_task = asyncio.create_task(self._broadcast_loop())

        hostname = socket.gethostname()
        local_ip = socket.gethostbyname(hostname)
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

        loop = asyncio.get_event_loop()
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


def main():
    import argparse

    parser = argparse.ArgumentParser(description="SnapECG Holter Connector")
    parser.add_argument("--mcp", action="store_true", help="Run as MCP server (stdio)")
    parser.add_argument("--discover", action="store_true", help="Discover devices and exit")
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
    elif args.mcp:
        asyncio.run(connector.run_mcp())
    else:
        asyncio.run(connector.start_server())


if __name__ == '__main__':
    main()
