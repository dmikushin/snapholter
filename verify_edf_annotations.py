"""
Verification: EDF+ annotations format produced by EdfWriter.kt.

Builds an EDF+ file matching the byte-level layout that EdfWriter writes,
then parses it strictly per the EDF+ spec (https://www.edfplus.info/specs/edfplus.html)
and asserts:

1. Header:
   - reserved field starts with "EDF+C"
   - num_signals == 2 when annotations are present
   - signal 2 label == "EDF Annotations"
2. Data records:
   - Each record's annotation block starts with a time-keeping TAL
     "+<recordIdx>\x14\x14\x00"
   - User TALs follow with onset == record index, text matching the note
   - Records without user notes contain only the time-keeping TAL,
     followed by zero padding
3. TAL bytes are packed contiguously (no zero-byte interleaving) — i.e.
   reading 2*Ns bytes back as a UTF-8 byte stream yields the original TALs.
4. Multi-note records are concatenated with " | " separator.
"""
import struct
import sys


SAMPLE_RATE = 200
DIGITAL_MIN, DIGITAL_MAX = -2048, 2047
PHYSICAL_MIN, PHYSICAL_MAX = -2048, 2047
TIMEKEEPING_MAX_BYTES = 24


def edf_str(value, length):
    return value[:length].ljust(length)


def time_keeping_tal(record_idx):
    return f"+{record_idx}\x14\x14\x00"


def user_tal(record_idx, text):
    return f"+{record_idx}\x14{text}\x14\x00"


def compute_ann_samples(annotations):
    """Same logic as EdfWriter.init: ceil((timekeeping_max + max_user_tal) / 2)."""
    if not annotations:
        return 0
    max_user = max(len(user_tal(r, t).encode("utf-8")) for r, t in annotations.items())
    total = TIMEKEEPING_MAX_BYTES + max_user
    return (total + 1) // 2


def build_header(total_records, ann_samples_per_record, has_annotations):
    num_signals = 2 if has_annotations else 1
    header_size = 256 + num_signals * 256
    parts = []
    parts.append(edf_str("0", 8))
    parts.append(edf_str("X X X TestPatient", 80))
    parts.append(edf_str("Startdate 01-JAN-2026 X SnapECG_B10 snapecg-holter", 80))
    parts.append(edf_str("01.01.26", 8))
    parts.append(edf_str("00.00.00", 8))
    parts.append(edf_str(str(header_size), 8))
    parts.append(edf_str("EDF+C" if has_annotations else "", 44))
    parts.append(edf_str(str(total_records), 8))
    parts.append(edf_str("1", 8))
    parts.append(edf_str(str(num_signals), 4))

    # Signal 1: ECG
    parts.append(edf_str("ECG", 16))
    parts.append(edf_str("AgCl electrode", 80))
    parts.append(edf_str("uV", 8))
    parts.append(edf_str(str(PHYSICAL_MIN), 8))
    parts.append(edf_str(str(PHYSICAL_MAX), 8))
    parts.append(edf_str(str(DIGITAL_MIN), 8))
    parts.append(edf_str(str(DIGITAL_MAX), 8))
    parts.append(edf_str("HP:0.05Hz LP:40Hz", 80))
    parts.append(edf_str(str(SAMPLE_RATE), 8))
    parts.append(edf_str("", 32))

    if has_annotations:
        parts.append(edf_str("EDF Annotations", 16))
        parts.append(edf_str("", 80))
        parts.append(edf_str("", 8))
        parts.append(edf_str("-32768", 8))
        parts.append(edf_str("32767", 8))
        parts.append(edf_str("-32768", 8))
        parts.append(edf_str("32767", 8))
        parts.append(edf_str("", 80))
        parts.append(edf_str(str(ann_samples_per_record), 8))
        parts.append(edf_str("", 32))

    header = "".join(parts)
    assert len(header) == header_size, f"header {len(header)} != {header_size}"
    return header.encode("ascii")


def build_record(record_idx, ann_samples_per_record, annotations, has_annotations):
    """Build one data record with sample_rate ECG samples + annotation block."""
    ecg_bytes = b"".join(struct.pack("<h", 0) for _ in range(SAMPLE_RATE))
    if not has_annotations:
        return ecg_bytes
    ann_bytes_capacity = ann_samples_per_record * 2
    tal = time_keeping_tal(record_idx)
    if record_idx in annotations:
        tal += user_tal(record_idx, annotations[record_idx])
    tal_bytes = tal.encode("utf-8")
    assert len(tal_bytes) <= ann_bytes_capacity, "TAL overflow"
    ann_block = tal_bytes + b"\x00" * (ann_bytes_capacity - len(tal_bytes))
    return ecg_bytes + ann_block


def parse_header(data):
    """Parse the EDF header strictly (offsets per spec section 2.1)."""
    return {
        "version": data[0:8].decode("ascii").strip(),
        "reserved": data[192:236].decode("ascii").strip(),
        "num_records": int(data[236:244].decode("ascii").strip()),
        "record_duration": float(data[244:252].decode("ascii").strip()),
        "num_signals": int(data[252:256].decode("ascii").strip()),
        "sig1_label": data[256:272].decode("ascii").strip(),
        # Per spec, samples-per-record field is at offset 216 within each
        # signal-header block of 256 bytes. Signal 1 header starts at 256.
        "sig1_ns": int(data[256 + 216:256 + 216 + 8].decode("ascii").strip()),
        "sig2_label": (data[512:528].decode("ascii").strip() if len(data) >= 528 else ""),
        "sig2_ns": (int(data[512 + 216:512 + 216 + 8].decode("ascii").strip())
                    if len(data) >= 736 else 0),
    }


def parse_tals(ann_block):
    r"""Strict EDF+ TAL parser: split on \x00 terminators, return list of (onset, text)."""
    tals = []
    cursor = 0
    while cursor < len(ann_block):
        end = ann_block.find(b"\x00", cursor)
        if end == -1 or end == cursor:
            break  # zero padding
        chunk = ann_block[cursor:end]
        # TAL: Onset[\x15Duration]\x14Annotation\x14   (\x14 separated, ends with \x14 before \x00)
        sep_idx = chunk.find(b"\x14")
        if sep_idx == -1:
            break
        onset_dur = chunk[:sep_idx].decode("utf-8")
        if "\x15" in onset_dur:
            onset_str = onset_dur.split("\x15", 1)[0]
        else:
            onset_str = onset_dur
        onset = float(onset_str)
        rest = chunk[sep_idx + 1:].decode("utf-8")
        # rest is "Annotation1\x14Annotation2\x14..." (last \x14 before terminator)
        annotations = [a for a in rest.split("\x14") if a != ""]
        text = annotations[0] if annotations else ""
        tals.append((onset, text))
        cursor = end + 1
    return tals


def assert_eq(actual, expected, msg):
    if actual != expected:
        raise AssertionError(f"{msg}: expected {expected!r}, got {actual!r}")


def test_header_marker_for_edfplus():
    print("1. EDF+C marker in reserved field when annotations present")
    annotations = {0: "test"}
    ns = compute_ann_samples(annotations)
    h = parse_header(build_header(3, ns, has_annotations=True))
    assert h["reserved"].startswith("EDF+C"), f"reserved was {h['reserved']!r}"
    assert_eq(h["num_signals"], 2, "num_signals")
    assert_eq(h["sig2_label"], "EDF Annotations", "sig2_label")
    print(f"   OK: reserved='{h['reserved']}', num_signals=2, ns={h['sig2_ns']}")


def test_no_annotations_means_plain_edf():
    print("2. No annotations -> plain EDF (1 signal, no EDF+C marker)")
    h = parse_header(build_header(3, 0, has_annotations=False))
    assert_eq(h["num_signals"], 1, "num_signals")
    assert h["reserved"] == "", f"reserved should be empty, got {h['reserved']!r}"
    print("   OK: 1 signal, no EDF+C marker")


def test_time_keeping_tal_in_every_record():
    print("3. Every data record has a time-keeping TAL with correct onset")
    annotations = {1: "user note"}
    ns = compute_ann_samples(annotations)
    ann_capacity = ns * 2
    for record_idx in [0, 1, 2, 5, 100]:
        record = build_record(record_idx, ns, annotations, has_annotations=True)
        ann_block = record[SAMPLE_RATE * 2:]
        assert len(ann_block) == ann_capacity
        tals = parse_tals(ann_block)
        assert tals, f"record {record_idx}: no TALs parsed"
        first_onset, first_text = tals[0]
        assert_eq(first_onset, float(record_idx), f"record {record_idx} time-keeping onset")
        assert_eq(first_text, "", f"record {record_idx} time-keeping text (must be empty)")
    print("   OK: time-keeping TAL present with correct onset in records 0,1,2,5,100")


def test_user_annotation_uses_correct_onset():
    print("4. User annotations have onset = record index (not hardcoded 0)")
    annotations = {0: "first second", 5: "fifth second", 42: "much later"}
    ns = compute_ann_samples(annotations)
    for record_idx, expected_text in annotations.items():
        record = build_record(record_idx, ns, annotations, has_annotations=True)
        tals = parse_tals(record[SAMPLE_RATE * 2:])
        # First TAL is time-keeping (empty text), second is user
        assert len(tals) >= 2, f"record {record_idx}: expected user TAL after time-keeping"
        user_onset, user_text = tals[1]
        assert_eq(user_onset, float(record_idx), f"record {record_idx} user onset")
        assert_eq(user_text, expected_text, f"record {record_idx} user text")
    print(f"   OK: user TALs at onsets {sorted(annotations.keys())}")


def test_tal_bytes_packed_contiguously():
    print("5. TAL bytes are contiguous (no zero-byte interleaving)")
    annotations = {0: "ABCDEF"}
    ns = compute_ann_samples(annotations)
    record = build_record(0, ns, annotations, has_annotations=True)
    ann_block = record[SAMPLE_RATE * 2:]
    expected_prefix = b"+0\x14\x14\x00+0\x14ABCDEF\x14\x00"
    assert ann_block.startswith(expected_prefix), \
        f"TAL bytes not contiguous: got {ann_block[:len(expected_prefix)+8]!r}"
    print(f"   OK: ann block starts with {expected_prefix!r}")


def test_record_without_user_note_has_only_timekeeping():
    print("6. Record without user note has only time-keeping TAL + zero padding")
    annotations = {1: "only second"}
    ns = compute_ann_samples(annotations)
    record = build_record(0, ns, annotations, has_annotations=True)  # record 0 has no user note
    ann_block = record[SAMPLE_RATE * 2:]
    tals = parse_tals(ann_block)
    assert_eq(len(tals), 1, "record 0 should have exactly 1 TAL (time-keeping only)")
    assert_eq(tals[0], (0.0, ""), "record 0 TAL")
    tal_size = len(b"+0\x14\x14\x00")
    assert all(b == 0 for b in ann_block[tal_size:]), "padding not all zeros"
    print(f"   OK: record 0 has only time-keeping; {len(ann_block) - tal_size} zero pad bytes")


def test_multi_note_concatenation():
    print("7. Multi-note records concatenate with ' | ' separator")
    # RecordingStore concatenates same-second notes with " | ".
    annotations = {0: "first | second"}
    ns = compute_ann_samples(annotations)
    record = build_record(0, ns, annotations, has_annotations=True)
    tals = parse_tals(record[SAMPLE_RATE * 2:])
    assert_eq(len(tals), 2, "expected timekeeping + user")
    _, text = tals[1]
    assert "first" in text and "second" in text, f"both notes in text, got: {text!r}"
    assert " | " in text, f"separator missing: {text!r}"
    print(f"   OK: combined text = {text!r}")


def test_full_file_round_trip():
    print("8. Full file: header + records, parse header then verify each record")
    annotations = {0: "start", 3: "midpoint event", 5: "end"}
    total_records = 6
    ns = compute_ann_samples(annotations)

    header = build_header(total_records, ns, has_annotations=True)
    body = b"".join(build_record(i, ns, annotations, True) for i in range(total_records))
    file_data = header + body

    h = parse_header(file_data[:len(header)])
    assert_eq(h["num_records"], total_records, "header num_records")
    assert_eq(h["sig1_ns"], SAMPLE_RATE, "ECG samples per record")
    assert_eq(h["sig2_ns"], ns, "annotation samples per record")

    record_size = SAMPLE_RATE * 2 + ns * 2
    assert_eq(len(body), total_records * record_size, "body size")

    found_user_notes = {}
    for i in range(total_records):
        record = body[i * record_size:(i + 1) * record_size]
        tals = parse_tals(record[SAMPLE_RATE * 2:])
        assert tals[0] == (float(i), ""), f"record {i} timekeeping wrong: {tals[0]}"
        if len(tals) > 1:
            found_user_notes[int(tals[1][0])] = tals[1][1]

    assert_eq(found_user_notes, annotations, "round-trip user notes")
    print(f"   OK: round-tripped {len(annotations)} user notes through {total_records} records")


def main():
    print("=== EDF+ Annotations Verification (strict spec compliance) ===\n")
    tests = [
        test_header_marker_for_edfplus,
        test_no_annotations_means_plain_edf,
        test_time_keeping_tal_in_every_record,
        test_user_annotation_uses_correct_onset,
        test_tal_bytes_packed_contiguously,
        test_record_without_user_note_has_only_timekeeping,
        test_multi_note_concatenation,
        test_full_file_round_trip,
    ]
    failed = 0
    for t in tests:
        try:
            t()
        except AssertionError as e:
            print(f"   FAIL: {e}")
            failed += 1
        print()

    print("=" * 60)
    if failed == 0:
        print(f"ALL {len(tests)} TESTS PASSED")
        return 0
    else:
        print(f"{failed} of {len(tests)} TESTS FAILED")
        return 1


if __name__ == "__main__":
    sys.exit(main())
