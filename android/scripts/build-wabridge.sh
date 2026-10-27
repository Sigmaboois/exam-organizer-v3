#!/bin/bash
# Builds the WhatsApp bridge (wabridge/, Go) into app/libs/wabridge.aar with gomobile.
# Run from the android folder inside Ubuntu (WSL). Needs Go, gomobile and the Android NDK.
set -e
export ANDROID_HOME=/root/android-sdk
export ANDROID_NDK_HOME=$(ls -d $ANDROID_HOME/ndk/* 2>/dev/null | sort -V | tail -1)
export PATH=/usr/local/go/bin:/root/go/bin:$PATH
export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"
command -v gomobile >/dev/null || { echo "gomobile isn't installed in Ubuntu"; exit 1; }
[ -n "$ANDROID_NDK_HOME" ] || { echo "The Android NDK isn't installed in Ubuntu"; exit 1; }

cd wabridge
[ -f go.sum ] || go mod tidy
mkdir -p ../app/libs
# Phones (64 and 32-bit ARM) and the x86_64 emulator
CGO_ENABLED=1 gomobile bind -target=android/arm64,android/arm,android/amd64 -androidapi 26 \
  -ldflags="-s -w" -o ../app/libs/wabridge.aar .
ls -la ../app/libs/wabridge.aar
