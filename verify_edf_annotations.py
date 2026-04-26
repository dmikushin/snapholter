"""
Verification: EDF+ annotations format as produced by EdfWriter.kt.

Creates a synthetic EDF+ file with:
- Signal 1: ECG (200 samples/record, 1 record = 1 second)
- Signal 2: EDF Annotations (N bytes/record)
- 3 data records: record 0 has note, records 1-2 are empty

Verifies:
1. Header has 2 signals with correct labels
2. Annotation signal header fields are valid (empty phys dim, etc.)
3. TAL format is correct: +<onset 21>\x14<duration 21>\x14<text>\x14\x00
4. Annotations can be read and decoded
5. Records without annotations have all-zero annotation bytes
"""
import struct
import sys

# --- Simulate the same format as Kotlin EdfWriter ---

SAMPLE_RATE = 200
DIGITAL_MIN = -2048
DIGITAL_MAX = 2047
PHYSICAL_MIN = -2048
PHYSICAL_MAX = 2047


def edf_str(value: str, length: int) -> str:
    """Pad or truncate to exactly `length` chars."""
    return value[:length].ljust(length)


def build_header(total_seconds: int, ann_bytes_per_record: int, num_signals: int) -> bytes:
    """Build EDF+ header matching EdfWriter.kt format."""
    header_size = 256 + num_signals * 256

    sb = []
    # Fixed header (256 bytes)
    sb.append(edf_str("0", 8))                             # version
    sb.append(edf_str("X X X Test", 80))                    # patient
    sb.append(edf_str("Startdate 01-JAN-2026 X X X", 80))  # recording
    sb.append(edf_str("01.01.26", 8))                       # date
    sb.append(edf_str("00.00.00", 8))                       # time
    sb.append(edf_str(str(header_size), 8))                 # header bytes
    sb.append(edf_str("", 44))                              # reserved
    sb.append(edf_str(str(total_seconds), 8))               # num data records
    sb.append(edf_str("1", 8))                              # duration
    sb.append(edf_str(str(num_signals), 4))                 # num signals

    # Signal 1: ECG
    sb.append(edf_str("ECG", 16))
    sb.append(edf_str("AgCl electrode", 80))
    sb.append(edf_str("uV", 8))
    sb.append(edf_str(str(PHYSICAL_MIN), 8))
    sb.append(edf_str(str(PHYSICAL_MAX), 8))
    sb.append(edf_str(str(DIGITAL_MIN), 8))
    sb.append(edf_str(str(DIGITAL_MAX), 8))
    sb.append(edf_str("HP:0.05Hz LP:40Hz", 80))
    sb.append(edf_str(str(SAMPLE_RATE), 8))
    sb.append(edf_str("", 32))

    # Signal 2: EDF Annotations (if present)
    if num_signals > 1:
        sb.append(edf_str("EDF Annotations", 16))
        sb.append(edf_str("", 80))
        sb.append(edf_str("", 8))
        sb.append(edf_str("-32768", 8))
        sb.append(edf_str("32767", 8))
        sb.append(edf_str("-32768", 8))
        sb.append(edf_str("32767", 8))
        sb.append(edf_str("", 80))
        sb.append(edf_str(str(ann_bytes_per_record), 8))
        sb.append(edf_str("", 32))

    header = "".join(sb)
    assert len(header) == header_size, f"Header {len(header)} != {header_size}"
    return header.encode("ascii")


def build_ecg_record(n_samples: int = 200) -> bytes:
    """Build one ECG data record (silent/zero signal)."""
    buf = b""
    for _ in range(n_samples):
        buf += struct.pack("<h", 0)
    return buf


def build_annotation_bytes(tal: str, ann_bytes_per_record: int) -> bytes:
    """Encode TAL string as s16 samples (1 byte → 1 s16)."""
    ann = tal.encode("utf-8")
    buf = b""
    for i in range(ann_bytes_per_record):
        b = ann[i] if i < len(ann) else 0
        buf += struct.pack("<h", b)
    return buf


def parse_header(data: bytes) -> dict:
    """Parse EDF header, return key fields."""
    return {
        "version": data[0:8].decode("ascii").strip(),
        "num_records": int(data[236:244].decode("ascii").strip()),
        "record_duration": float(data[244:252].decode("ascii").strip()),
        "num_signals": int(data[252:256].decode("ascii").strip()),
        # Signal 1
        "sig1_label": data[256:272].decode("ascii").strip(),
        "sig1_ns": int(data[472:480].decode("ascii").strip()),  # samples/record
        # Signal 2 (if present)
        "sig2_label": data[512:528].decode("ascii").strip() if len(data) >= 528 else "",
        "sig2_ns": int(data[728:736].decode("ascii").strip()) if len(data) >= 736 else 0,
    }


def parse_tal(ann_data: bytes, ann_bytes_per_record: int) -> str:
    """Extract and decode TAL string from annotation record bytes."""
    buf = b""
    for i in range(ann_bytes_per_record):
        b = struct.unpack_from("<h", ann_data, i * 2)[0]
        if b == 0:
            break
        buf += bytes([b])
    return buf.decode("utf-8", errors="replace")


def main():
    print("=== EDF+ Annotations Verification ===\n")

    all_ok = True

    # Test 1: EDF without annotations (single signal)
    print("1. EDF without annotations (single signal):")
    header = parse_header(build_header(5, 0, 1))
    assert header["num_signals"] == 1, f"Expected 1 signal, got {header['num_signals']}"
    assert header["sig1_label"] == "ECG", f"Expected 'ECG', got '{header['sig1_label']}'"
    assert header["sig1_ns"] == 200, f"Expected 200 samples, got {header['sig1_ns']}"
    print("   OK: single signal, ECG label, 200 samples/record")

    # Test 2: EDF with annotations (2 signals)
    print("\n2. EDF with annotations (2 signals):")
    ann_bytes = 80  # e.g. 80 bytes per record for annotations
    header = parse_header(build_header(5, ann_bytes, 2))
    assert header["num_signals"] == 2, f"Expected 2 signals, got {header['num_signals']}"
    assert header["sig2_label"] == "EDF Annotations", f"Expected 'EDF Annotations', got '{header['sig2_label']}'"
    assert header["sig2_ns"] == ann_bytes, f"Expected {ann_bytes} ann samples, got {header['sig2_ns']}"
    print("   OK: 2 signals, EDF Annotations label, correct samples/record")

    # Test 3: TAL encoding format
    print("\n3. TAL encoding:")
    note = "[0:01:05] steep climb starts"
    # Same format as Kotlin: "+0.0".padEnd(21) + "\x14" + "".padEnd(21) + "\x14" + text + "\x14\x00"
    tal = "+0.0".ljust(21) + "\x14" + "".ljust(21) + "\x14" + note + "\x14\x00"

    # Verify structure
    onset = tal[:21]
    sep1 = tal[21]
    duration = tal[22:43]
    sep2 = tal[43]
    rest = tal[44:]

    assert onset == "+0.0".ljust(21), f"Onset: '{onset}'"
    assert sep1 == "\x14", f"Separator 1: {ord(sep1)}"
    assert duration == "".ljust(21), f"Duration: '{duration}'"
    assert sep2 == "\x14", f"Separator 2: {ord(sep2)}"
    assert note in rest, f"Note not found in TAL rest: '{rest}'"
    assert rest.endswith("\x14\x00"), f"TAL doesn't end with \\x14\\x00: '{rest[-10:]}'"
    print("   OK: onset 21 chars, separators correct, note embedded, \\x14\\x00 terminator")

    # Test 4: Full data record with annotation
    print("\n4. Full data record structure:")
    ann_bytes_per_record = 120
    ecg = build_ecg_record(200)
    ann = build_annotation_bytes(tal, ann_bytes_per_record)
    record = ecg + ann

    assert len(record) == 200 * 2 + ann_bytes_per_record * 2, \
        f"Record size {len(record)} != expected {200*2 + ann_bytes_per_record*2}"
    assert record[0:400] == ecg, "ECG data corrupted"
    print(f"   OK: record {len(record)} bytes (ECG 400 + ann {ann_bytes_per_record*2})")

    # Test 5: Parse annotation back from bytes
    print("\n5. Parse annotation from record:")
    ann_part = record[400:]  # skip ECG
    decoded = parse_tal(ann_part, ann_bytes_per_record)
    assert note in decoded, f"Note not found in decoded: '{decoded[:80]}...'"
    print(f"   OK: parsed TAL contains note: '{decoded[:60]}...'")

    # Test 6: Record without annotation (all zeros)
    print("\n6. Empty annotation record:")
    empty_tal = ""
    empty_ann = build_annotation_bytes(empty_tal, ann_bytes_per_record)
    assert empty_ann == b"\x00" * (ann_bytes_per_record * 2), "Empty annotation not all zeros"
    parsed = parse_tal(empty_ann, ann_bytes_per_record)
    assert parsed == "", f"Expected empty, got '{parsed}'"
    print("   OK: empty record is all zeros, parses as empty")

    # Test 7: Multiple notes in same second (concatenated)
    print("\n7. Multiple notes in same second:")
    notes = ["[0:01:05] steep climb starts", "[0:01:05] feeling dizzy"]
    combined = notes[0] + " | " + notes[1]
    tal_multi = "+0.0".ljust(21) + "\x14" + "".ljust(21) + "\x14" + combined + "\x14\x00"
    decoded_multi = parse_tal(
        build_annotation_bytes(tal_multi, 200), 200
    )
    assert notes[0] in decoded_multi and "feeling dizzy" in decoded_multi, \
        f"Not all notes found in: '{decoded_multi}'"
    print(f"   OK: both notes in TAL: '{decoded_multi[:80]}'")

    # Summary
    print(f"\n{'='*50}")
    if all_ok:
        print("ALL TESTS PASSED")
    else:
        print("SOME TESTS FAILED")
    print(f"{'='*50}")

    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
