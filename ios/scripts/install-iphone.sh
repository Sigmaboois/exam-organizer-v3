#!/bin/bash
# Run by install-iphone.ps1 from the ios folder (inside Ubuntu on WSL).
# Copies the project onto Ubuntu's own disk (building there is much faster), builds it and
# installs it on the plugged-in iPhone. Everything is also written to the log file.
# Usage: bash scripts/install-iphone.sh <log file> [release|debug]
LOG="${1:-logs/install.log}"
CONFIG="${2:-release}"
exec > >(tee -a "$LOG") 2>&1
TEE_PID=$!
# Let tee finish writing before the window shows the result, or the last lines go missing
finish() { exec 1>&- 2>&-; wait "$TEE_PID" 2>/dev/null; }
trap finish EXIT

step() { echo "[$(date +%H:%M:%S)]       $*"; }
fail() { echo "[$(date +%H:%M:%S)] PROBLEM: $*"; exit 1; }

step "Loading Swift..."
. /root/.local/share/swiftly/env.sh || fail "Swift isn't installed in Ubuntu."
step "$(swift --version 2>&1 | head -1)"
step "$(xtool --version 2>&1)"

# Talk to the iPhone through Apple's own service on Windows (the Apple Devices app).
# USB pass-through with usbipd breaks large messages, so it isn't used.
export USBMUXD_SOCKET_ADDRESS=127.0.0.1:27015

step "Looking for the iPhone..."
DEVICE=$(idevice_id -l 2>/dev/null | head -1)
[ -n "$DEVICE" ] || fail "No iPhone found. Plug it in, unlock it, tap Trust if it asks, then run this again."
step "Found: $(ideviceinfo -k DeviceName 2>/dev/null) (iOS $(ideviceinfo -k ProductVersion 2>/dev/null))"
DEVMODE=$(idevicedevmodectl list 2>/dev/null | grep "$DEVICE" | awk '{print $2}')
[ "$DEVMODE" = "enabled" ] || fail "Developer Mode is off on the iPhone: Settings > Privacy & Security > Developer Mode."

step "Copying the project into Ubuntu..."
SRC="$(pwd)/"
DST=/root/ExamOrganizer/
mkdir -p "$DST"
rsync -a --delete --exclude .build --exclude xtool --exclude .swiftpm --exclude .bsp --exclude logs "$SRC" "$DST" \
  || fail "Couldn't copy the project."
# Build your Gemini key in, from the desktop app's src/api_key.py (never printed, never committed)
KEYFILE="$SRC/../src/api_key.py"
SECRETS="$DST/Sources/ExamOrganizer/Secrets.swift"
if [ -f "$KEYFILE" ]; then
  python3 - "$KEYFILE" "$SECRETS" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
found = re.search(r"""GEMINI_API_KEY\s*=\s*["']([^"']*)["']""", text)
key = found.group(1).strip() if found else ""
with open(sys.argv[2], "w", encoding="utf-8") as file:
    file.write("// Made by the installer from src/api_key.py. Never commit this file.\n"
               "enum Secrets {\n    static let geminiAPIKey = \"%s\"\n}\n" % key)
print("[key]       Built-in Gemini key: " + ("added from src/api_key.py" if key else "src/api_key.py has no key"))
PY
elif [ ! -f "$SECRETS" ]; then
  step "Note: no src/api_key.py, so the app has no built-in Gemini key (add one in the app's Settings)."
  cp "$DST/Secrets.swift.example" "$SECRETS"
fi

step "Building ($CONFIG) and installing. If it asks to revoke a certificate, type yes."
cd "$DST"
xtool dev run -c "$CONFIG" || fail "xtool couldn't build or install the app (see above)."
step "Installed on the iPhone."
