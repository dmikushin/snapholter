#!/usr/bin/env python3
"""
Mock SnapECG B10 device, speaking the same wire protocol as the real chest
strap so the Android app can drive a complete recording session against it.

Wire format (matches snapecg-holter/.../bluetooth/Protocol.kt):

    Packet := 0xFF | LEN | CMD/TYPE | DATA... | CHECKSUM
    LEN     = number of bytes that follow LEN itself, *including* the
              checksum byte (so payload length = LEN - 2).
    CHECKSUM= sum of [LEN, CMD, DATA...] mod 256, with 0xFF -> 0xFE escape.

This server:
- Listens on TCP for the app's debug-mode TCP transport shim (no real
  Bluetooth involved — the emulator's BT stack is unusable, so the app
  treats addresses of the form `tcp:host:port` as ordinary TCP under
  BuildConfig.DEBUG).
- Replies to GET_VERSION with a fixed version string.
- Replies to DEVICE_INFO with a 7-byte response.
- ACK-style commands (SET_TIME, READ_ADJUST_COEFF, FILTER_0_5HZ): no
  reply (the app fires them with no expected echo).
- On START_STOP=1 starts streaming PKT_ECG_1 packets at 200 Hz with a
  synthetic triangular-QRS waveform; on START_STOP=0 stops.
- Periodically (every ~3 s) emits a PKT_BAT_TEMP_ACC battery packet so
  the app's onBattery callback fires and the recording UI shows a
  battery level.

Usage:
    python3 mock_b10.py [--port 9999] [--bpm 72] [--lead-off-after 0]

The app side connects to "tcp:10.0.2.2:<port>" because 10.0.2.2 is the
emulator-loopback alias for the host. Run this script on the host before
launching the app.
"""

import argparse
import asyncio
import math
import struct
import time

# --- Constants from Protocol.kt -----------------------------------------------

HEADER = 0xFF
ECG_BASELINE = 2048

CMD_START_STOP        = 0x10
CMD_GET_VERSION       = 0x12
CMD_DEVICE_INFO       = 0x13
CMD_SET_TIME          = 0x16
CMD_READ_ADJUST_COEFF = 0x1A
CMD_FILTER_0_5HZ      = 0x1D
CMD_ANSWER            = 0x1F

PKT_ECG_1        = 0x00
PKT_BAT          = 0x03
PKT_BAT_TEMP     = 0x04
PKT_BAT_TEMP_ACC = 0x06

SAMPLE_RATE = 200       # Hz, fixed by the device
SAMPLE_INTERVAL = 1.0 / SAMPLE_RATE


# --- Packet framing -----------------------------------------------------------

def checksum(body: bytes) -> int:
    """Sum mod 256, escaping 0xFF (which is reserved as the start byte)."""
    s = 0
    for b in body:
        s = (s + b) & 0xFF
    if s == 0xFF:
        s = 0xFE
    return s


def make_packet(cmd_or_type: int, payload: bytes = b"") -> bytes:
    """Frame a payload as [HEADER, LEN, CMD, *payload, CHECKSUM]."""
    body = bytes([len(payload) + 2, cmd_or_type]) + payload
    return bytes([HEADER]) + body + bytes([checksum(body)])


# --- Synthetic ECG ------------------------------------------------------------

def encode_ecg_sample(value: int, lead_off: bool) -> bytes:
    """Encode a 12-bit ADC value (0..4095) as a 2-byte (high, low) pair
    matching Protocol.rebuildEcg's expectations:
        12-bit value = ((high & 0x1F) << 7) | (low & 0x7F)
    Bit 5 of the high byte carries the lead-off flag.
    """
    v = max(0, min(4095, value))
    high = (v >> 7) & 0x1F
    low = v & 0x7F
    if lead_off:
        high |= (1 << 5)
    return bytes([high, low])


def synth_ecg(t_samples: int, bpm: float,
              qrs_amp: int = 600, qrs_width: int = 20) -> int:
    """Triangular QRS waveform. Returns a 12-bit ADC value (centred on
    ECG_BASELINE = 2048, peaks ~+600 ADC units = ~600 µV)."""
    rr = max(1, int(SAMPLE_RATE * 60.0 / bpm))
    pos = t_samples % rr
    if pos < qrs_width:
        if pos < qrs_width // 2:
            v = ECG_BASELINE + qrs_amp * pos // (qrs_width // 2)
        else:
            v = ECG_BASELINE + qrs_amp * (qrs_width - pos) // (qrs_width // 2)
    else:
        # Add a tiny baseline wander so the signal isn't pure DC, otherwise
        # the QRS detector's noise estimate stays at zero.
        v = ECG_BASELINE + int(2 * math.sin(2 * math.pi * t_samples / 200))
    return v


# --- Protocol handlers --------------------------------------------------------

class MockDevice:
    def __init__(self, bpm: float, lead_off_after: float, verbose: bool):
        self.bpm = bpm
        self.lead_off_after = lead_off_after  # seconds; 0 = never
        self.verbose = verbose
        self.streaming = False
        self.stream_started_at = 0.0
        self.t_samples = 0

    # ---- Inbound parsing -------------------------------------------------

    async def read_packet(self, reader: asyncio.StreamReader) -> tuple[int, bytes] | None:
        """Read one framed packet. Returns (cmd, payload) or None on EOF."""
        # Hunt for HEADER (0xFF). Anything else is junk; drop it. This
        # matches the Kotlin StreamParser's resync behaviour.
        while True:
            b = await reader.read(1)
            if not b:
                return None
            if b[0] == HEADER:
                break
        ln_b = await reader.read(1)
        if not ln_b:
            return None
        ln = ln_b[0]
        if ln < 2:
            return None
        rest = await reader.readexactly(ln)
        cmd = rest[0]
        payload = rest[1:-1]
        # Verify checksum.
        body = bytes([ln, cmd]) + payload
        expected = checksum(body)
        if expected != rest[-1]:
            if self.verbose:
                print(f"  bad checksum on cmd=0x{cmd:02x}; dropping packet")
            return None
        return cmd, payload

    # ---- Outbound responses ----------------------------------------------

    @staticmethod
    def version_response() -> bytes:
        return make_packet(CMD_GET_VERSION, b"MOCK1.00")

    @staticmethod
    def device_info_response() -> bytes:
        # 7-byte payload; offset 6 carries the device type. Real Holter
        # devices set this to 1 (single-lead) or 2 (multi-lead). We pick 1.
        info = bytearray(7)
        info[6] = 1
        return make_packet(CMD_DEVICE_INFO, bytes(info))

    @staticmethod
    def battery_packet(level: int) -> bytes:
        # PKT_BAT_TEMP_ACC has length=11 with battery level at offset 2 of
        # the raw frame (1 byte after LEN+CMD). Encode as 8 bytes of payload.
        payload = bytearray(8)
        payload[0] = level & 0xFF
        return make_packet(PKT_BAT_TEMP_ACC, bytes(payload))

    # ---- Per-connection lifecycle ---------------------------------------

    async def handle_client(self, reader: asyncio.StreamReader,
                            writer: asyncio.StreamWriter):
        addr = writer.get_extra_info('peername')
        print(f"[{self._tstamp()}] App connected from {addr}")
        self.streaming = False
        self.t_samples = 0

        async def write(data: bytes):
            try:
                writer.write(data)
                await writer.drain()
            except (ConnectionError, OSError):
                raise

        async def command_loop():
            while True:
                pkt = await self.read_packet(reader)
                if pkt is None:
                    return
                cmd, payload = pkt
                await self.handle_command(cmd, payload, write)

        async def stream_loop():
            """Stream ECG samples at 200 Hz when self.streaming is True."""
            next_deadline = None
            while True:
                if not self.streaming:
                    await asyncio.sleep(0.05)
                    continue
                now = time.monotonic()
                if next_deadline is None:
                    next_deadline = now
                # Send one sample.
                v = synth_ecg(self.t_samples, self.bpm)
                lead_off = self._lead_off_now()
                payload = encode_ecg_sample(v, lead_off)
                await write(make_packet(PKT_ECG_1, payload))
                self.t_samples += 1
                next_deadline += SAMPLE_INTERVAL
                slack = next_deadline - time.monotonic()
                if slack > 0:
                    await asyncio.sleep(slack)
                else:
                    # Falling behind — reset the clock so we don't snowball.
                    next_deadline = time.monotonic()

        async def battery_loop():
            """Every ~3 seconds emit a battery packet."""
            level = 3
            while True:
                await asyncio.sleep(3.0)
                if self.streaming:
                    try:
                        await write(self.battery_packet(level))
                    except (ConnectionError, OSError):
                        return

        try:
            await asyncio.gather(
                command_loop(), stream_loop(), battery_loop(),
                return_exceptions=False,
            )
        except (asyncio.IncompleteReadError, ConnectionError, OSError) as e:
            if self.verbose:
                print(f"  client loop ended: {e}")
        finally:
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass
            print(f"[{self._tstamp()}] App disconnected from {addr} "
                  f"(streamed {self.t_samples} samples)")

    async def handle_command(self, cmd: int, payload: bytes, write):
        """Dispatch a single inbound command to its response (if any)."""
        if cmd == CMD_GET_VERSION:
            if self.verbose: print(f"  -> GET_VERSION → MOCK1.00")
            await write(self.version_response())
        elif cmd == CMD_DEVICE_INFO:
            if self.verbose: print(f"  -> DEVICE_INFO → type=1 (single-lead)")
            await write(self.device_info_response())
        elif cmd == CMD_SET_TIME:
            if self.verbose: print(f"  -> SET_TIME (no reply expected)")
        elif cmd == CMD_READ_ADJUST_COEFF:
            if self.verbose: print(f"  -> READ_ADJUST_COEFF (no reply expected)")
        elif cmd == CMD_FILTER_0_5HZ:
            if self.verbose: print(f"  -> FILTER_0_5HZ (no reply expected)")
        elif cmd == CMD_START_STOP:
            on = payload[:1] == b"\x01"
            if on:
                print(f"[{self._tstamp()}] START streaming at {self.bpm:.0f} bpm")
                self.stream_started_at = time.monotonic()
                self.t_samples = 0
                self.streaming = True
            else:
                dur = time.monotonic() - self.stream_started_at
                print(f"[{self._tstamp()}] STOP streaming "
                      f"(streamed {self.t_samples} samples in {dur:.1f}s)")
                self.streaming = False
        else:
            if self.verbose:
                print(f"  -> unknown cmd=0x{cmd:02x} payload={payload.hex()}")

    # ---- Helpers --------------------------------------------------------

    def _lead_off_now(self) -> bool:
        if self.lead_off_after <= 0 or not self.streaming:
            return False
        elapsed = time.monotonic() - self.stream_started_at
        return elapsed >= self.lead_off_after

    @staticmethod
    def _tstamp() -> str:
        return time.strftime("%H:%M:%S")


# --- Entry point --------------------------------------------------------------

async def main():
    parser = argparse.ArgumentParser(description="Mock SnapECG B10 device")
    parser.add_argument("--port", type=int, default=9999,
                        help="TCP listen port (default: 9999)")
    parser.add_argument("--bpm", type=float, default=72.0,
                        help="synthetic heart rate (default: 72)")
    parser.add_argument("--lead-off-after", type=float, default=0.0,
                        help="seconds after START to start asserting lead-off "
                             "(default: 0 = never)")
    parser.add_argument("-v", "--verbose", action="store_true",
                        help="print every received command")
    parser.add_argument("--bind", default="0.0.0.0",
                        help="address to bind (default: 0.0.0.0)")
    args = parser.parse_args()

    device = MockDevice(args.bpm, args.lead_off_after, args.verbose)
    server = await asyncio.start_server(device.handle_client, args.bind, args.port)

    print(f"Mock SnapECG B10 listening on {args.bind}:{args.port}")
    print(f"  bpm={args.bpm}, lead-off-after={args.lead_off_after or 'never'}")
    print(f"  app should connect to tcp:{args.bind}:{args.port}")
    print(f"  (from inside the Android emulator, use tcp:10.0.2.2:{args.port})")
    print()

    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
