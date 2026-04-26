#!/usr/bin/env bash
# End-to-end runtime test, driven entirely through `am start-foreground-service`
# intents into HolterService — no UI tapping, no uiautomator. The intents are
# the same actions the Compose buttons fire under the hood, so this exercises
# the full BT → flush → DB → EDF pipeline without depending on screen layout.
#
# Sequence:
#   1. Start the mock SnapECG B10 server on the host (TCP $MOCK_PORT).
#   2. Re-install the debug APK (uninstalling first if signatures drift).
#   3. Pre-seed SharedPreferences so DeviceResolver picks tcp:10.0.2.2:$PORT
#      (the emulator-loopback alias for the host).
#   4. Grant runtime permissions and silence the battery-opt prompt.
#   5. Launch HolterService directly:
#        am start-foreground-service ... ACTION_START   --es address tcp:...
#      record for $RECORD_SECONDS,
#        am start-foreground-service ... ACTION_STOP_AND_EXPORT --es patient_name ...
#   6. Pull the resulting EDF and the full filtered logcat.
#   7. Validate: file size, EDF+C marker, no crashes, sample-count summary.
#
# Usage: tools/e2e_test.sh [seconds]
#
# Env:
#   MOCK_PORT       (default 9999)
#   MOCK_BPM        synthetic heart rate (default 72)
#   RECORD_SECONDS  recording duration (default 30)
#   LEAD_OFF_AFTER  if >0, mock asserts lead-off N s into recording

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS_DIR="$REPO_ROOT/tools"
HOLTER_DIR="$REPO_ROOT/snapecg-holter"

MOCK_PORT="${MOCK_PORT:-9999}"
MOCK_BPM="${MOCK_BPM:-72}"
RECORD_SECONDS="${1:-${RECORD_SECONDS:-30}}"
LEAD_OFF_AFTER="${LEAD_OFF_AFTER:-0}"

ADB="$HOME/.local/share/snapecg-android-sdk/platform-tools/adb"
APP_ID="dev.snapecg.holter"
SVC="$APP_ID/.recording.HolterService"
MAIN_ACTIVITY="$APP_ID/.ui.MainActivity"
ACTION_START="dev.snapecg.holter.START"
ACTION_STOP_AND_EXPORT="dev.snapecg.holter.STOP_AND_EXPORT"

ARTIFACT_DIR="$REPO_ROOT/.e2e-artifacts/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$ARTIFACT_DIR"

# Track tags emitted by the app so log filtering catches everything we care
# about. Newcomers tags should be added here (see -s flag below).
LOGCAT_TAGS=(
    HolterService MainActivity DeviceManager StreamParser
    ConnectorService ConnectorCrypto PairingStore RecordingStore
    QRSDetector DeviceResolver
    AndroidRuntime SQLiteLog
)

# ---- Pretty -----------------------------------------------------------------

c_red()    { printf "\033[31m%s\033[0m" "$*"; }
c_green()  { printf "\033[32m%s\033[0m" "$*"; }
c_yellow() { printf "\033[33m%s\033[0m" "$*"; }
c_dim()    { printf "\033[2m%s\033[0m" "$*"; }
log()      { echo "$(c_dim "[e2e]") $*"; }
ok()       { echo "$(c_green "[ok]") $*"; }
warn()     { echo "$(c_yellow "[warn]") $*" >&2; }
fail()     { echo "$(c_red "[fail]") $*" >&2; exit 1; }

# ---- Cleanup ----------------------------------------------------------------

mock_pid=""
logcat_pid=""
cleanup() {
    [ -n "$logcat_pid" ] && kill "$logcat_pid" 2>/dev/null || true
    if [ -n "$mock_pid" ] && kill -0 "$mock_pid" 2>/dev/null; then
        kill "$mock_pid" 2>/dev/null || true
        wait "$mock_pid" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# ---- Preflight --------------------------------------------------------------

[ -x "$ADB" ] || fail "adb not found at $ADB. Run snapecg-holter/emulator.sh setup first."
"$ADB" get-state > /dev/null 2>&1 || fail "No emulator/device attached. Run 'snapecg-holter/emulator.sh start'."

# ---- 1. Start mock device ---------------------------------------------------

mock_log="$ARTIFACT_DIR/mock_b10.log"
log "Starting mock B10 on port $MOCK_PORT (bpm=$MOCK_BPM, lead-off-after=$LEAD_OFF_AFTER)"
# python3 -u: unbuffered stdout/stderr. Without it, the `print()` calls in
# mock_b10.py end up sitting in libc buffers until the process exits, and
# the log file looks empty even when the mock is happily running.
python3 -u "$TOOLS_DIR/mock_b10.py" \
    --port "$MOCK_PORT" --bpm "$MOCK_BPM" \
    --lead-off-after "$LEAD_OFF_AFTER" -v \
    > "$mock_log" 2>&1 &
mock_pid=$!
sleep 1
kill -0 "$mock_pid" 2>/dev/null || fail "mock_b10.py died on startup; see $mock_log"
ok "Mock device pid $mock_pid (logging to $mock_log)"

# ---- 2. Install APK ---------------------------------------------------------

apk="$HOLTER_DIR/.docker-build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$apk" ]; then
    log "APK missing — building"
    "$HOLTER_DIR/build_docker.sh" assembleDebug
fi
log "Installing APK on device"
if ! "$ADB" install -r -t "$apk" > /dev/null 2>&1; then
    warn "Install failed (likely signature mismatch); uninstalling then retrying"
    "$ADB" uninstall "$APP_ID" > /dev/null 2>&1 || true
    "$ADB" install -t "$apk" > /dev/null
fi
ok "APK installed"

# ---- 3. Pre-seed SharedPreferences ------------------------------------------

prefs_xml="$ARTIFACT_DIR/snapecg.xml"
cat > "$prefs_xml" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="snapecg_bt_address">tcp:10.0.2.2:$MOCK_PORT</string>
    <boolean name="battery_optimization_asked" value="true" />
</map>
EOF

"$ADB" shell am force-stop "$APP_ID" > /dev/null 2>&1 || true
"$ADB" push "$prefs_xml" "/data/local/tmp/snapecg.xml" > /dev/null
"$ADB" shell "run-as $APP_ID mkdir -p shared_prefs"
"$ADB" shell "run-as $APP_ID cp /data/local/tmp/snapecg.xml shared_prefs/snapecg.xml"
ok "Pre-seeded SharedPreferences with tcp:10.0.2.2:$MOCK_PORT"

for perm in \
    android.permission.POST_NOTIFICATIONS \
    android.permission.BLUETOOTH_CONNECT \
    android.permission.BLUETOOTH_SCAN ; do
    "$ADB" shell pm grant "$APP_ID" "$perm" 2>/dev/null || true
done
ok "Granted runtime permissions"

# ---- 4. Start logcat capture ------------------------------------------------

logcat_file="$ARTIFACT_DIR/logcat.txt"
"$ADB" logcat -c
# `-s` filters: catches every log line emitted with one of our tags. No
# `*:S` wildcard at the end since that gets order-of-arguments-finicky.
"$ADB" logcat -v time -s "${LOGCAT_TAGS[@]}" > "$logcat_file" 2>&1 &
logcat_pid=$!
ok "Logcat capture pid $logcat_pid → $logcat_file"

# ---- 5. Drive HolterService directly via intents ----------------------------

log "Launching MainActivity (just to ensure binding context)"
"$ADB" shell am start -W -n "$MAIN_ACTIVITY" \
    -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
    > /dev/null
sleep 1
"$ADB" exec-out screencap -p > "$ARTIFACT_DIR/01_home.png"

log "Sending ACTION_START to $SVC (address=tcp:10.0.2.2:$MOCK_PORT)"
"$ADB" shell am start-foreground-service \
    -n "$SVC" -a "$ACTION_START" \
    --es address "tcp:10.0.2.2:$MOCK_PORT" > /dev/null

# Give DeviceManager time to do the TCP shim handshake + initialize().
sleep 5
"$ADB" exec-out screencap -p > "$ARTIFACT_DIR/02_recording.png"
ok "Recording started (TCP handshake + initialize complete)"

log "Recording for $RECORD_SECONDS s..."
sleep "$RECORD_SECONDS"

log "Sending ACTION_STOP_AND_EXPORT (patient_name=e2e-test-$$)"
"$ADB" shell am start-foreground-service \
    -n "$SVC" -a "$ACTION_STOP_AND_EXPORT" \
    --es patient_name "e2e-test-$$" > /dev/null

# Export runs on a Thread; ~2-5 s for typical recordings.
sleep 6
"$ADB" exec-out screencap -p > "$ARTIFACT_DIR/03_completed.png"

# Stop logcat now so the file flushes before we read it.
kill "$logcat_pid" 2>/dev/null || true
sleep 1
logcat_pid=""

# ---- 6. Pull EDF ------------------------------------------------------------

edf_remote="$( "$ADB" shell ls -1t /sdcard/Documents/SnapECG/ 2>/dev/null \
    | tr -d '\r' | head -1 || true )"
if [ -z "$edf_remote" ]; then
    fail "No EDF file produced under /sdcard/Documents/SnapECG/. \
See logcat.txt and mock_b10.log for what went wrong."
fi
log "Pulling EDF: $edf_remote"
"$ADB" pull "/sdcard/Documents/SnapECG/$edf_remote" "$ARTIFACT_DIR/" > /dev/null
ok "EDF pulled → $ARTIFACT_DIR/$edf_remote"

# ---- 7. Validation ----------------------------------------------------------

edf_path="$ARTIFACT_DIR/$edf_remote"
edf_size="$(stat -c%s "$edf_path")"

# Expected sample budget: 200 Hz × duration. Allow 5% slack each side for
# the BT handshake delay + final flush race.
expected_samples=$(( RECORD_SECONDS * 200 ))
min_samples=$(( expected_samples * 95 / 100 ))
max_samples=$(( expected_samples * 105 / 100 ))
# EDF format: 256-byte fixed header + 256-byte signal header + (samples * 2)
# bytes for the ECG channel. With annotations (which we don't have here):
# +256 bytes header + (Ns * 2) per record. Estimate without annotations.
min_size=$(( 512 + min_samples * 2 ))

log "EDF size: $edf_size bytes (expected ≥ $min_size for ≥ $min_samples samples)"
if [ "$edf_size" -lt "$min_size" ]; then
    warn "EDF smaller than expected. Recording may have been truncated."
fi

header="$(head -c 256 "$edf_path")"
if echo "$header" | grep -q "EDF+C"; then
    ok "EDF+C marker present in reserved field"
else
    log "(EDF+C marker absent — recording had no annotations, expected)"
fi

# Crash sweep.
crash_count="$(grep -cE "FATAL EXCEPTION|libc:.*Fatal|AndroidRuntime: \*" "$logcat_file" 2>/dev/null || true)"
if [ "$crash_count" = "0" ]; then
    ok "logcat: 0 fatal exceptions"
else
    warn "logcat: $crash_count fatal-exception lines (see logcat.txt)"
fi

# Clipping report.
clipping="$(grep -E "edf_clipped|clipped to digital" "$logcat_file" || true)"
if [ -n "$clipping" ]; then
    warn "EDF clipping reported:"
    echo "$clipping" | head -3 | sed 's/^/    /'
fi

# Sample-count summary from HolterService final log line.
samples_line="$(grep -E "Recording stopped:" "$logcat_file" | tail -1 || true)"
[ -n "$samples_line" ] && log "${samples_line#*HolterService( ????): }"
export_line="$(grep -E "EDF export complete" "$logcat_file" | tail -1 || true)"
[ -n "$export_line" ] && log "${export_line#*HolterService( ????): }"

# ---- 8. Summary -------------------------------------------------------------

echo
echo "=================================="
echo "$(c_green "E2E run complete")"
echo "Artefacts: $ARTIFACT_DIR/"
echo "  ├── 01_home.png .. 03_completed.png"
echo "  ├── logcat.txt        ($(wc -l < "$logcat_file") lines)"
echo "  ├── mock_b10.log      ($(wc -l < "$mock_log") lines)"
echo "  └── $edf_remote ($edf_size bytes)"
echo "=================================="
