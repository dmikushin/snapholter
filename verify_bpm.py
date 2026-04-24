"""
Verification script for QRSDetector BPM calculation.

Creates synthetic ECG data with known heart rates and verifies
that the QRSDetector reports BPM within 10% of expected value.
Also tests robustness: missing beats (electrode disconnect) should
not produce garbage BPM values (1-2 bpm).
"""
import sys
import os

# Add the snapecg-b10 package to path
sys.path.insert(0, os.path.expanduser("~/forge/snapecg-b10/src"))

from snapecg.qrs import QRSDetector

SAMPLE_RATE = 200  # Hz
BASELINE = 2048     # ADC baseline
QRS_AMPLITUDE = 600  # peak above baseline
QRS_WIDTH = 20       # samples (100ms at 200Hz)


def generate_ecg(bpm: float, duration_sec: float = 30.0) -> list[int]:
    """Generate synthetic ECG with QRS complexes at given BPM."""
    rr_samples = int(SAMPLE_RATE * 60.0 / bpm)
    total_samples = int(duration_sec * SAMPLE_RATE)
    samples = []

    for i in range(total_samples):
        pos_in_cycle = i % rr_samples
        if pos_in_cycle < QRS_WIDTH:
            # Triangle QRS complex: rise then fall
            if pos_in_cycle < QRS_WIDTH // 2:
                val = BASELINE + QRS_AMPLITUDE * pos_in_cycle // (QRS_WIDTH // 2)
            else:
                val = BASELINE + QRS_AMPLITUDE * (QRS_WIDTH - pos_in_cycle) // (QRS_WIDTH // 2)
        else:
            val = BASELINE
        samples.append(val)

    return samples


def generate_ecg_with_gap(bpm: float, gap_start_sec: float, gap_duration_sec: float,
                          total_duration_sec: float = 30.0) -> list[int]:
    """Generate ECG that goes silent (flat baseline) for gap_duration_sec."""
    rr_samples = int(SAMPLE_RATE * 60.0 / bpm)
    total_samples = int(total_duration_sec * SAMPLE_RATE)
    gap_start = int(gap_start_sec * SAMPLE_RATE)
    gap_end = gap_start + int(gap_duration_sec * SAMPLE_RATE)
    samples = []

    for i in range(total_samples):
        if gap_start <= i < gap_end:
            samples.append(BASELINE)  # flatline — electrode off
        else:
            # Adjust position accounting for gap
            if i >= gap_end:
                effective_i = i - (gap_end - gap_start)
            else:
                effective_i = i
            pos_in_cycle = effective_i % rr_samples
            if pos_in_cycle < QRS_WIDTH:
                if pos_in_cycle < QRS_WIDTH // 2:
                    val = BASELINE + QRS_AMPLITUDE * pos_in_cycle // (QRS_WIDTH // 2)
                else:
                    val = BASELINE + QRS_AMPLITUDE * (QRS_WIDTH - pos_in_cycle) // (QRS_WIDTH // 2)
            else:
                val = BASELINE
            samples.append(val)

    return samples


def test_bpm(name: str, samples: list[int], expected_bpm: float,
             allowed_error_pct: float = 10.0, warmup_sec: float = 5.0) -> bool:
    """Feed samples through QRSDetector and check BPM accuracy."""
    det = QRSDetector()
    warmup_samples = int(warmup_sec * SAMPLE_RATE)

    reported_bpms = []
    for i, s in enumerate(samples):
        hr, rr, amp = det.process(s)
        if i >= warmup_samples and hr > 0:
            reported_bpms.append(hr)

    if not reported_bpms:
        print(f"  FAIL [{name}]: No BPM reported at all!")
        return False

    # Compute median of reported BPMs (ignore first few for convergence)
    stable_bpms = reported_bpms[3:] if len(reported_bpms) > 3 else reported_bpms
    if not stable_bpms:
        stable_bpms = reported_bpms

    median_bpm = sorted(stable_bpms)[len(stable_bpms) // 2]
    error_pct = abs(median_bpm - expected_bpm) / expected_bpm * 100

    # Check for garbage values (BPM < 20 or > 250)
    min_bpm = min(reported_bpms)
    max_bpm = max(reported_bpms)
    garbage = [b for b in reported_bpms if b < 20 or b > 250]

    ok = True
    if error_pct > allowed_error_pct:
        print(f"  FAIL [{name}]: median BPM={median_bpm}, expected={expected_bpm:.0f}, "
              f"error={error_pct:.1f}% (allowed {allowed_error_pct}%)")
        ok = False
    elif garbage:
        print(f"  FAIL [{name}]: garbage BPM values found: {garbage}")
        ok = False
    else:
        print(f"  OK   [{name}]: median BPM={median_bpm}, expected={expected_bpm:.0f}, "
              f"error={error_pct:.1f}%, range=[{min_bpm}..{max_bpm}], "
              f"{len(reported_bpms)} reports")

    return ok


def test_gap_robustness(name: str, samples: list[int],
                        max_allowed_bpm: float = 250.0) -> bool:
    """Verify no garbage BPM during/after electrode disconnect gap."""
    det = QRSDetector()
    reported_bpms = []
    for i, s in enumerate(samples):
        hr, rr, amp = det.process(s)
        if hr > 0:
            reported_bpms.append((i, hr))

    garbage = [(i, hr) for i, hr in reported_bpms if hr < 20 or hr > 250]

    if garbage:
        print(f"  FAIL [{name}]: {len(garbage)} garbage BPM values: "
              f"{garbage[:5]}{'...' if len(garbage) > 5 else ''}")
        return False
    else:
        min_bpm = min(hr for _, hr in reported_bpms) if reported_bpms else 0
        max_bpm = max(hr for _, hr in reported_bpms) if reported_bpms else 0
        print(f"  OK   [{name}]: no garbage BPM, range=[{min_bpm}..{max_bpm}], "
              f"{len(reported_bpms)} reports")
        return True


def main():
    print("=== QRSDetector BPM Verification ===\n")

    # Test 1: Clean signals at various heart rates
    print("1. Clean signal BPM accuracy:")
    test_rates = [40, 60, 80, 100, 120, 150, 180]
    all_ok = True
    for bpm in test_rates:
        samples = generate_ecg(bpm, duration_sec=20.0)
        ok = test_bpm(f"clean_{bpm}bpm", samples, bpm)
        all_ok = all_ok and ok

    # Test 2: Gap robustness — electrode disconnect
    print("\n2. Gap robustness (electrode disconnect):")
    for bpm in [60, 80, 120]:
        samples = generate_ecg_with_gap(bpm, gap_start_sec=10.0,
                                         gap_duration_sec=3.0,
                                         total_duration_sec=25.0)
        ok = test_gap_robustness(f"gap3s_{bpm}bpm", samples)
        all_ok = all_ok and ok

    # Test 3: Long gap — should reset confidence and not report garbage
    print("\n3. Long gap (5 seconds — should reset confidence):")
    samples = generate_ecg_with_gap(80, gap_start_sec=10.0,
                                     gap_duration_sec=5.0,
                                     total_duration_sec=25.0)
    ok = test_gap_robustness("longgap5s_80bpm", samples)
    all_ok = all_ok and ok

    # Test 4: Very slow HR (40 BPM) — RR=300 samples, close to old INIT_RR
    print("\n4. Edge cases:")
    samples = generate_ecg(40, duration_sec=30.0)
    ok = test_bpm("slow_40bpm", samples, 40, warmup_sec=10.0)
    all_ok = all_ok and ok

    # Test 5: Very fast HR (200 BPM) — at the boundary of RR_MIN=55
    samples = generate_ecg(200, duration_sec=15.0)
    ok = test_bpm("fast_200bpm", samples, 200, warmup_sec=5.0, allowed_error_pct=15.0)
    all_ok = all_ok and ok

    # Test 6: Verify no BPM reported before MIN_VALID_BEATS (3 beats)
    print("\n6. Minimum beats gate:")
    det = QRSDetector()
    samples = generate_ecg(60, duration_sec=3.0)  # ~3 beats in 3 seconds
    early_bpms = []
    for i, s in enumerate(samples):
        hr, _, _ = det.process(s)
        if hr > 0:
            early_bpms.append((i, hr))
    # At 60 BPM, first warmup=100 samples, then we need 3 valid beats
    # First beat at ~120 samples after warmup, second at ~320, third at ~520
    # So we should get first BPM around sample 520 (~2.6 sec)
    if len(early_bpms) <= 2:
        print(f"  OK   [min_beats]: only {len(early_bpms)} BPM reports in first 3s (gate works)")
    else:
        print(f"  WARN [min_beats]: {len(early_bpms)} BPM reports in first 3s (gate may be loose)")

    # Summary
    print(f"\n{'='*50}")
    if all_ok:
        print("ALL TESTS PASSED")
    else:
        print("SOME TESTS FAILED — see above")
    print(f"{'='*50}")

    return 0 if all_ok else 1


if __name__ == '__main__':
    sys.exit(main())
