#!/bin/bash
# Run by build-apk.ps1 from the android folder (inside Ubuntu on WSL).
# Copies the project onto Ubuntu's own disk (much faster), builds the APK and copies it back to
# dist\Exam Organizer.apk. Everything is also written to the log file.
# Usage: bash scripts/build-apk.sh <log file> [build|test|test-only]
LOG="${1:-logs/build.log}"
MODE="${2:-build}"
exec > >(tee -a "$LOG") 2>&1
TEE_PID=$!
finish() { exec 1>&- 2>&-; wait "$TEE_PID" 2>/dev/null; }
trap finish EXIT

step() { echo "[$(date +%H:%M:%S)]       $*"; }
fail() { echo "[$(date +%H:%M:%S)] PROBLEM: $*"; exit 1; }

export ANDROID_HOME=/root/android-sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:/opt/gradle/bin:$PATH
# WSL's VirtioProxy networking (needed for the iPhone) breaks Java's IPv6 loopback, which Gradle
# uses to talk to its own helper processes. IPv4 works, so every Java process is told to use it.
export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"
command -v gradle >/dev/null || fail "Gradle isn't installed in Ubuntu."
[ -d "$ANDROID_HOME/platforms/android-35" ] || fail "The Android SDK isn't installed in Ubuntu."
step "$(java -version 2>&1 | head -1)"

step "Copying the project into Ubuntu..."
SRC="$(pwd)/"
DST=/root/ExamOrganizerAndroid/
mkdir -p "$DST"
rsync -a --delete --exclude build --exclude .gradle --exclude .kotlin --exclude logs "$SRC" "$DST" \
  || fail "Couldn't copy the project."

# Build your Gemini key in, from the desktop app's src/api_key.py (never printed, never committed)
KEYFILE="$SRC/../src/api_key.py"
if [ -f "$KEYFILE" ]; then
  GEMINI_API_KEY=$(python3 - "$KEYFILE" <<'PY'
import re, sys
found = re.search(r"""GEMINI_API_KEY\s*=\s*["']([^"']*)["']""", open(sys.argv[1], encoding="utf-8").read())
print(found.group(1).strip() if found else "")
PY
)
  export GEMINI_API_KEY
  if [ -n "$GEMINI_API_KEY" ]; then step "Built-in Gemini key: added from src/api_key.py"; else step "Note: src/api_key.py has no key"; fi
else
  step "Note: no src/api_key.py, so the app has no built-in Gemini key (add one in the app's Settings)."
fi

cd "$DST"
if [ "$MODE" = test ] || [ "$MODE" = test-only ]; then
  [ -d /root/corpus ] || fail "No test papers in /root/corpus."
  step "Testing paper detection on the real Cambridge papers..."
  gradle --no-daemon --console=plain -q testReleaseUnitTest -Dcorpus=/root/corpus || fail "The tests failed (see above)."
  [ -f app/build/parser-corpus-report.txt ] && cat app/build/parser-corpus-report.txt
  [ -f app/build/thresholds-report.txt ] && cat app/build/thresholds-report.txt
  [ "$MODE" = test-only ] && exit 0
fi

step "Building the APK (the first build downloads Android's build tools and takes a few minutes)..."
gradle --no-daemon --console=plain assembleRelease 2>&1 | grep -vE "^(> Task|$)" || true
APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || fail "The build failed (see the errors above)."
mkdir -p "$SRC/../dist"
cp "$APK" "$SRC/../dist/Exam Organizer.apk"
step "APK ready: dist/Exam Organizer.apk ($(du -h "$APK" | cut -f1))"
