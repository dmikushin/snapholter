#!/usr/bin/env bash
# Manages a local Android emulator for SnapECG Holter development.
#
# Subcommands:
#   setup    Download the Android SDK + system image and create the AVD
#            (one-time, ~3 GB download, ~5–10 min).
#   start    Boot the emulator in the background and wait until it's ready.
#   stop     Send a graceful shutdown to the running emulator.
#   status   Print whether the AVD exists, whether it's running, and the
#            attached adb device.
#   build    Run build_docker.sh assembleDebug.
#   deploy   build → start (if needed) → install/replace APK → launch app.
#   logcat   Tail the app's tags from the running emulator.
#   wipe     Delete the AVD (next setup will recreate it).
#   help     Print this banner.
#
# Configuration (env vars):
#   SNAPECG_ANDROID_SDK   SDK install dir (default: ~/.local/share/snapecg-android-sdk)
#   SNAPECG_AVD_NAME      AVD name (default: snapecg-test)
#   SNAPECG_API_LEVEL     Android API to target (default: 34)
#   SNAPECG_ABI           system-image ABI (default: x86_64; arm64-v8a on Apple silicon)
#   SNAPECG_HEADLESS      "1" to run the emulator with -no-window (default: 0)

set -euo pipefail

# ---- Configuration ---------------------------------------------------------

SDK_HOME="${SNAPECG_ANDROID_SDK:-$HOME/.local/share/snapecg-android-sdk}"
AVD_NAME="${SNAPECG_AVD_NAME:-snapecg-test}"
API_LEVEL="${SNAPECG_API_LEVEL:-34}"
ABI="${SNAPECG_ABI:-x86_64}"
IMAGE_TYPE="default"
HEADLESS="${SNAPECG_HEADLESS:-0}"

# Pinned commandline-tools build. Bump deliberately; Google rotates the URL
# but every prior stable build remains downloadable indefinitely.
CMDLINE_TOOLS_BUILD="11076708"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_PATH="$SCRIPT_DIR/.docker-build/outputs/apk/debug/app-debug.apk"
APP_ID="dev.snapecg.holter"
MAIN_ACTIVITY="$APP_ID/.ui.MainActivity"

# Tools we expect to find inside SDK_HOME after setup. Keep these on PATH so
# subcommands can call `adb`, `emulator`, etc. without absolute paths.
SDK_TOOLS_DIR="$SDK_HOME/cmdline-tools/latest/bin"
SDK_PLATFORM_TOOLS_DIR="$SDK_HOME/platform-tools"
SDK_EMULATOR_DIR="$SDK_HOME/emulator"

export ANDROID_HOME="$SDK_HOME"
export ANDROID_SDK_ROOT="$SDK_HOME"
export PATH="$SDK_TOOLS_DIR:$SDK_PLATFORM_TOOLS_DIR:$SDK_EMULATOR_DIR:$PATH"

# ---- Pretty-print helpers --------------------------------------------------

c_red()    { printf "\033[31m%s\033[0m" "$*"; }
c_green()  { printf "\033[32m%s\033[0m" "$*"; }
c_yellow() { printf "\033[33m%s\033[0m" "$*"; }
c_dim()    { printf "\033[2m%s\033[0m" "$*"; }
log()      { echo "$(c_dim "[emulator]") $*"; }
warn()     { echo "$(c_yellow "[warn]") $*" >&2; }
fail()     { echo "$(c_red "[fail]") $*" >&2; exit 1; }
success()  { echo "$(c_green "[ok]") $*"; }

# ---- Preflight -------------------------------------------------------------

require_kvm() {
    if [ ! -e /dev/kvm ]; then
        fail "/dev/kvm missing. Without KVM the x86_64 emulator runs in software \
mode and is glacial. Either enable VT-x/AMD-V in BIOS or set SNAPECG_ABI=arm64-v8a \
on Apple silicon."
    fi
    if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
        fail "/dev/kvm not readable/writable by $(id -un). Run: \
sudo usermod -aG kvm \$USER  (then log out and back in)."
    fi
}

require_cmd() {
    command -v "$1" > /dev/null 2>&1 || fail "command '$1' missing on PATH (need it for $2)"
}

# ---- setup -----------------------------------------------------------------

cmd_setup() {
    require_cmd unzip   "extracting commandline-tools"
    require_cmd curl    "downloading commandline-tools"
    require_kvm

    mkdir -p "$SDK_HOME"

    if [ ! -x "$SDK_TOOLS_DIR/sdkmanager" ]; then
        log "Downloading commandline-tools build $CMDLINE_TOOLS_BUILD…"
        local tmp
        tmp="$(mktemp -d)"
        trap "rm -rf '$tmp'" EXIT
        curl -fsSL "$CMDLINE_TOOLS_URL" -o "$tmp/cmdline-tools.zip"
        unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
        # The zip contains `cmdline-tools/`; sdkmanager expects it at
        # `cmdline-tools/latest/`.
        mkdir -p "$SDK_HOME/cmdline-tools"
        rm -rf "$SDK_HOME/cmdline-tools/latest"
        mv "$tmp/cmdline-tools" "$SDK_HOME/cmdline-tools/latest"
        success "commandline-tools installed at $SDK_TOOLS_DIR"
    else
        log "commandline-tools already present at $SDK_TOOLS_DIR"
    fi

    log "Accepting SDK licenses (auto-yes)…"
    yes 2>/dev/null | "$SDK_TOOLS_DIR/sdkmanager" --licenses > /dev/null

    log "Installing platform-tools, emulator, platform $API_LEVEL, system-image $ABI…"
    "$SDK_TOOLS_DIR/sdkmanager" \
        "platform-tools" \
        "emulator" \
        "platforms;android-$API_LEVEL" \
        "system-images;android-$API_LEVEL;$IMAGE_TYPE;$ABI"

    if "$SDK_TOOLS_DIR/avdmanager" list avd | grep -q "Name: $AVD_NAME$"; then
        log "AVD '$AVD_NAME' already exists; leaving it alone (use 'wipe' to reset)."
    else
        log "Creating AVD '$AVD_NAME'…"
        # Pixel-5-ish device profile gives a 1080×2340 portrait phone, which
        # matches what the app's portrait-only Compose UI is designed for.
        echo "no" | "$SDK_TOOLS_DIR/avdmanager" create avd \
            -n "$AVD_NAME" \
            -k "system-images;android-$API_LEVEL;$IMAGE_TYPE;$ABI" \
            -d "pixel_5"

        # Tweak the generated config.ini for headless-friendly performance.
        local cfg="$HOME/.android/avd/$AVD_NAME.avd/config.ini"
        if [ -f "$cfg" ]; then
            cat >> "$cfg" <<EOF
hw.gpu.enabled=yes
hw.gpu.mode=auto
hw.ramSize=2048
disk.dataPartition.size=4096M
EOF
        fi
        success "AVD '$AVD_NAME' created."
    fi

    success "Setup complete. Run '$0 start' to boot."
}

# ---- start / stop / status -------------------------------------------------

emulator_pid() {
    pgrep -f "emulator.*-avd $AVD_NAME" 2>/dev/null | head -n 1
}

cmd_start() {
    require_cmd "$SDK_EMULATOR_DIR/emulator" "running the emulator (run setup first)"
    require_kvm

    if [ -n "$(emulator_pid)" ]; then
        log "Emulator '$AVD_NAME' already running (pid $(emulator_pid))."
        return 0
    fi

    log "Booting emulator '$AVD_NAME'…"
    local args=(
        -avd "$AVD_NAME"
        -gpu auto
        -no-snapshot-save  # don't write a snapshot back on shutdown
        -no-audio          # silence host speaker
        -no-boot-anim      # shaves a few seconds
    )
    [ "$HEADLESS" = "1" ] && args+=(-no-window)

    # Detach so the parent shell isn't tied to the emulator process. Logs go
    # to a per-AVD file so we can grep boot failures.
    local logfile="$SDK_HOME/$AVD_NAME.log"
    : > "$logfile"
    nohup "$SDK_EMULATOR_DIR/emulator" "${args[@]}" \
        >> "$logfile" 2>&1 &
    disown || true

    log "Waiting for adb device to register…"
    "$SDK_PLATFORM_TOOLS_DIR/adb" wait-for-device

    log "Waiting for boot to complete (this can take 60–90 s on first run)…"
    local elapsed=0
    while [ "$("$SDK_PLATFORM_TOOLS_DIR/adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '[:space:]')" != "1" ]; do
        sleep 2
        elapsed=$((elapsed + 2))
        if [ "$elapsed" -ge 180 ]; then
            warn "Emulator did not finish booting in 3 minutes. Tail of log:"
            tail -20 "$logfile" >&2
            fail "boot timeout"
        fi
    done

    success "Emulator booted in ${elapsed}s. Logs: $logfile"
}

cmd_stop() {
    local pid
    pid="$(emulator_pid)"
    if [ -z "$pid" ]; then
        log "No '$AVD_NAME' emulator running."
        return 0
    fi
    log "Sending kill to emulator pid $pid…"
    "$SDK_PLATFORM_TOOLS_DIR/adb" emu kill 2>/dev/null || true
    # Fallback if adb-emu didn't take.
    sleep 2
    if kill -0 "$pid" 2>/dev/null; then
        warn "Emulator still alive; sending SIGTERM."
        kill "$pid" 2>/dev/null || true
        sleep 2
        kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
    fi
    success "Emulator stopped."
}

cmd_status() {
    if [ ! -d "$SDK_HOME" ]; then
        echo "SDK:        $(c_red "not installed") (run: $0 setup)"
        return 1
    fi
    echo "SDK:        $SDK_HOME"
    if "$SDK_TOOLS_DIR/avdmanager" list avd 2>/dev/null | grep -q "Name: $AVD_NAME$"; then
        echo "AVD:        $(c_green "$AVD_NAME") exists"
    else
        echo "AVD:        $(c_yellow "$AVD_NAME") missing (run: $0 setup)"
    fi
    if [ -n "$(emulator_pid)" ]; then
        echo "Emulator:   $(c_green "running") (pid $(emulator_pid))"
    else
        echo "Emulator:   $(c_dim "stopped")"
    fi
    echo "adb devices:"
    "$SDK_PLATFORM_TOOLS_DIR/adb" devices 2>/dev/null | sed 's/^/  /'
    if [ -f "$APK_PATH" ]; then
        local size
        size="$(du -h "$APK_PATH" | cut -f1)"
        echo "APK:        $APK_PATH ($size)"
    else
        echo "APK:        $(c_yellow "not built") (run: $0 build)"
    fi
}

# ---- build / deploy --------------------------------------------------------

cmd_build() {
    log "Building debug APK via build_docker.sh…"
    "$SCRIPT_DIR/build_docker.sh" assembleDebug
    if [ ! -f "$APK_PATH" ]; then
        fail "APK not produced at expected path: $APK_PATH"
    fi
    success "APK ready: $APK_PATH"
}

cmd_deploy() {
    [ -f "$APK_PATH" ] || cmd_build

    if [ -z "$(emulator_pid)" ]; then
        log "Emulator not running; starting it first."
        cmd_start
    fi

    log "Installing/replacing APK on device…"
    "$SDK_PLATFORM_TOOLS_DIR/adb" install -r -t "$APK_PATH"

    log "Launching $MAIN_ACTIVITY…"
    "$SDK_PLATFORM_TOOLS_DIR/adb" shell am start -W -n "$MAIN_ACTIVITY" \
        -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
        > /dev/null

    success "App launched. Tail logs with: $0 logcat"
}

# ---- logcat ----------------------------------------------------------------

cmd_logcat() {
    if [ -z "$(emulator_pid)" ]; then
        fail "Emulator not running. Start it first: $0 start"
    fi
    log "Tailing app tags. Ctrl-C to stop."
    "$SDK_PLATFORM_TOOLS_DIR/adb" logcat -v time \
        ConnectorService:V HolterService:V MainActivity:V \
        DeviceManager:V QRSDetector:V PairingStore:V \
        AndroidRuntime:E *:S
}

# ---- wipe ------------------------------------------------------------------

cmd_wipe() {
    cmd_stop || true
    if "$SDK_TOOLS_DIR/avdmanager" list avd 2>/dev/null | grep -q "Name: $AVD_NAME$"; then
        log "Deleting AVD '$AVD_NAME'…"
        "$SDK_TOOLS_DIR/avdmanager" delete avd -n "$AVD_NAME"
        success "AVD deleted. Run '$0 setup' to recreate."
    else
        log "AVD '$AVD_NAME' not found; nothing to wipe."
    fi
}

# ---- help / dispatch -------------------------------------------------------

cmd_help() {
    sed -n '2,/^set -euo pipefail$/p' "$0" | sed 's/^# //; s/^#//' | head -n -1
}

case "${1:-help}" in
    setup)  cmd_setup ;;
    start)  cmd_start ;;
    stop)   cmd_stop ;;
    status) cmd_status ;;
    build)  cmd_build ;;
    deploy) cmd_deploy ;;
    logcat) cmd_logcat ;;
    wipe)   cmd_wipe ;;
    help|-h|--help) cmd_help ;;
    *) cmd_help; exit 2 ;;
esac
