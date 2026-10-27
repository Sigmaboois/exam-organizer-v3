#!/usr/bin/env bash
# Compatibility entry point; keep the Developer Mode install flow in one place.
exec bash "$(dirname "${BASH_SOURCE[0]}")/install-iphone-trigger-devmode.sh" "$@"
