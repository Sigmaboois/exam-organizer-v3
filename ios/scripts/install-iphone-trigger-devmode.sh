#!/usr/bin/env bash
# Run from the ios project folder inside Ubuntu/WSL.
# Usage: bash scripts/install-iphone-trigger-devmode.sh [log file] [release|debug]
# This does NOT bypass Apple's Developer Mode security or guarantee the toggle appears.
# It removes the premature local check so Xtool can attempt the normal development install.
set -o pipefail
case "${2:-release}" in debug|release) ;; *) echo "Configuration must be debug or release."; exit 1 ;; esac
LOG="${1:-logs/install.log}"
CONFIG="${2:-release}"
mkdir -p "$(dirname "$LOG")"
exec > >(tee -a "$LOG") 2>&1
TEE_PID=$!
finish() { exec 1>&- 2>&-; wait "$TEE_PID" 2>/dev/null; }
trap finish EXIT

step() { echo "[$(date +%H:%M:%S)] $*"; }
fail() { echo "[$(date +%H:%M:%S)] PROBLEM: $*"; exit 1; }

step "Loading Swift..."
. /root/.local/share/swiftly/env.sh || fail "Swift isn't installed in Ubuntu."
step "$(swift --version 2>&1 | head -1)"
step "$(xtool --version 2>&1 | head -1)"

# Talk to the iPhone/iPad through Apple's service on Windows (Apple Devices app).
export USBMUXD_SOCKET_ADDRESS=127.0.0.1:27015

step "Looking for the connected iPhone/iPad..."
DEVICE=$(idevice_id -l 2>/dev/null | head -1)
[ -n "$DEVICE" ] || fail "No device found. Connect it by USB, unlock it, tap Trust if prompted, ensure Apple Devices is open on Windows, then rerun."
step "Found: $(ideviceinfo -u "$DEVICE" -k DeviceName 2>/dev/null) (iOS/iPadOS $(ideviceinfo -u "$DEVICE" -k ProductVersion 2>/dev/null))"

DEVMODE=$(idevicedevmodectl -u "$DEVICE" list 2>/dev/null | awk '{print $2}')
if [ "$DEVMODE" = "enabled" ]; then
  step "Developer Mode is reported enabled."
else
  step "Requesting the Developer Mode setting on this device..."
  if idevicedevmodectl -u "$DEVICE" reveal; then
    step "Developer Mode reveal request accepted."
  else
    step "The device did not accept the reveal request; continuing with the standard install attempt."
  fi
  step "On the device: Settings > Privacy & Security > Developer Mode. Enable it, restart, unlock and confirm Enable."
  step "If installation fails because Developer Mode is off, finish those steps and run install-iphone.cmd again."
fi

step "Copying the project into Ubuntu..."
SRC="$(pwd)/"
DST=/root/ExamOrganizer/
mkdir -p "$DST"
rsync -a --delete --exclude .build --exclude xtool --exclude .swiftpm --exclude .bsp --exclude logs --exclude .venv --exclude venv --exclude __pycache__ "$SRC" "$DST/" || fail "Couldn't copy the project."

# Build in the Gemini key from the desktop app's src/api_key.py, if present.
# Do not print or commit the key itself.
KEYFILE="$SRC/../src/api_key.py"
SECRETS="$DST/Sources/ExamOrganizer/Secrets.swift"
if [ -f "$KEYFILE" ]; then
  python3 - "$KEYFILE" "$SECRETS" <<'PY'
import json, re, sys
text = open(sys.argv[1], encoding="utf-8").read()
found = re.search(r'''GEMINI_API_KEY\s*=\s*["']([^"']*)["']''', text)
key = found.group(1).strip() if found else ""
with open(sys.argv[2], "w", encoding="utf-8") as file:
    file.write("// Generated from src/api_key.py. Do not commit this file.\n"
               "enum Secrets {\n    static let geminiAPIKey = %s\n}\n" % json.dumps(key))
print("[key] Built-in Gemini key: " + ("added from src/api_key.py" if key else "src/api_key.py has no recognized key"))
PY
  [ "$?" -eq 0 ] || fail "Could not generate the built-in key file."
elif [ ! -f "$SECRETS" ]; then
  step "Note: no src/api_key.py; app will need a key configured in Settings."
  [ ! -f "$DST/Secrets.swift.example" ] || cp "$DST/Secrets.swift.example" "$SECRETS"
fi

case "$CONFIG" in debug|release) ;; *) fail "Configuration must be 'debug' or 'release'." ;; esac
step "Running Xtool development build/install ($CONFIG)..."
step "If Xtool or iPadOS says Developer Mode is required, enable it on the device, restart, confirm the prompt, and rerun this script."
cd "$DST" || fail "Could not enter copied project directory."
xtool dev run -c "$CONFIG" || fail "Xtool couldn't build/install the app. Read the log above; if Developer Mode is required, enable it on the device and rerun."
step "Xtool reports installation completed."
