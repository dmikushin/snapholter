# Holter Monitoring Agent Guide

Guide for an AI agent performing the role of a nurse/technician during
Holter ECG monitoring setup, supervision, and analysis with SnapECG B10.

## Prerequisites

- SnapECG B10 device (charged)
- Patient's Android phone with SnapECG Holter app installed
- PC with `snapecg-connector` running (systemd service or manual)
- Phone and PC on the same WiFi network
- MCP server `snapecg-connector` available to the agent

## Phase 1: Discovery and Pairing

### 1.1 Find the patient's phone

```
→ holter_discover(timeout=10)
```

Expected: list of devices with `name` (phone model), `address` (IP), `port`.
If empty: verify phone app is open and WiFi is connected.

### 1.2 Pair with the phone

```
→ holter_pair(address="<IP from discovery>")
```

The connector will display a 6-digit code. The same code appears on the phone.
Ask the patient: **"Please confirm the pairing code on your phone."**

## Phase 2: Pre-Recording Verification

### 2.1 Check device status

```
→ holter_get_status()
```

Verify:
- `bt_connected: true` — device is connected via Bluetooth
- `device_battery` — note the level
- `phone_battery` — should be sufficient for monitoring duration
- `lead_off: false` — electrodes are in contact
- `free_storage_mb` — at least 100 MB

If `bt_connected: false`: ask patient to bring the phone closer to the device.
If `lead_off: true`: ask patient to adjust electrode placement.

### 2.2 Check signal quality

```
→ holter_check_signal(seconds=10)
```

Evaluate:
- `quality: "good"` — proceed
- `quality: "poor"` — no heartbeats detected, electrodes need adjustment
- `quality: "suspicious"` — unusual HR, verify placement

If poor: guide the patient:
> "The electrodes are not making good contact. Please press them firmly
> against your chest, slightly below the collarbone. The metal contacts
> should be directly on skin, not on clothing."

Repeat `holter_check_signal` after adjustment.

### 2.3 Run comprehensive verification

```
→ holter_verify_setup(min_battery=20)
```

This checks everything at once:
- Device battery ≥ threshold
- Bluetooth connected
- Electrodes in contact
- Signal quality (QRS detected, HR reasonable)
- Phone storage sufficient

Response: `go: true` or `go: false` with list of `problems`.

**Do not proceed to recording until `go: true`.**

## Phase 3: Start Recording

### 3.1 Inform the patient

Before starting, explain:
> "The Holter monitor will now record your heart rhythm continuously.
> You can go about your normal activities. Keep the phone within
> Bluetooth range (about 5 meters from the device).
>
> If you feel any symptoms — dizziness, palpitations, chest pain —
> open the app and tap 'Add Event' to note what happened and when.
>
> The phone will vibrate and alert you if it loses connection to
> the device. If that happens, move closer to the phone."

### 3.2 Start the recording

```
→ holter_start()
```

Expected: `status: "starting"`. The phone will show a persistent notification
with recording progress.

### 3.3 Verify recording is active

Wait 10 seconds, then:

```
→ holter_get_status()
```

Confirm: `recording: true`, `sample_count` is increasing.

### 3.4 Add initial event

```
→ holter_add_event(text="Holter monitoring started. Patient at rest.", tag="start")
```

## Phase 4: During Recording (optional check-ins)

The recording runs autonomously on the phone. The agent may periodically check:

```
→ holter_get_summary()
```

Verify `sample_count` is growing. If recording gaps appear, alert the patient.

The agent can also add events remotely if the patient reports symptoms:

```
→ holter_add_event(text="Patient reports dizziness while climbing stairs", tag="dizziness")
```

## Phase 5: Stop Recording

### 5.1 Stop

```
→ holter_stop()
```

### 5.2 Add closing event

```
→ holter_add_event(text="Holter monitoring completed.", tag="end")
```

## Phase 6: Download and Analysis

### 6.1 Get summary

```
→ holter_get_summary()
```

Quick overview: duration, sample count, battery status.

### 6.2 Download full recording

```
→ holter_download()
```

Returns XML with:
- Session metadata (start/end time, device info, duration)
- All ECG samples
- Event diary with timestamps and sample indices
- Status log (BT disconnections, lead-off episodes)

### 6.3 Analyze with snapecg

The connector runs local analysis using the `snapecg` package:

```
→ holter_check_signal(seconds=0)  # analyzes buffered data
```

Or use the `ecg_analyze` tool from the base MCP server for deeper analysis
of the downloaded data.

### 6.4 Generate report for cardiologist

Based on the analysis, report:

1. **Recording quality**
   - Total duration
   - Recording gaps (BT disconnections, lead-off episodes)
   - Percentage of analyzable signal

2. **Heart rate**
   - Average, minimum, maximum HR
   - HR trend over time (day vs night)
   - Tachycardia episodes (HR > 100 bpm)
   - Bradycardia episodes (HR < 50 bpm)

3. **Rhythm**
   - Normal sinus rhythm percentage
   - Arrhythmia episodes (if detected)
   - Correlation with patient events

4. **Event diary correlation**
   - Patient-reported symptoms mapped to ECG segments
   - Whether symptoms correlate with rhythm changes

## Troubleshooting

| Problem | Action |
|---------|--------|
| `holter_discover` returns empty | Check phone WiFi, restart app |
| `bt_connected: false` | Phone too far from device, or device off |
| `lead_off: true` | Patient must adjust electrodes |
| `quality: "poor"` | Reposition electrodes, check skin contact |
| `device_battery` low | Charge device before starting (user's responsibility) |
| Recording gaps in status log | BT range issue — advise patient to keep phone closer |
| App killed by Android | Battery optimization not disabled — reopen app |

## Quick Reference

```
holter_discover          → find phone on WiFi
holter_pair              → secure pairing (6-digit code)
holter_get_status        → battery, BT, lead-off, storage
holter_check_signal      → 10s ECG quality check
holter_verify_setup      → GO/NO-GO pre-flight check
holter_start             → begin recording
holter_stop              → end recording
holter_add_event         → diary entry (text + tag)
holter_get_events        → read diary
holter_download          → full recording (XML)
holter_get_summary       → quick stats
```
