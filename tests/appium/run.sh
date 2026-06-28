#!/bin/bash
# NetProbe Appium Test Runner
# Usage:
#   ./run.sh              # Run all tests
#   ./run.sh --quick      # Quick smoke test (launch + navigation)
#   ./run.sh --probe-only # Only PROBE tab tests
#   ./run.sh --drive-only # Only DRIVE tab tests

set -e

cd "$(dirname "$0")"

# Check prerequisites
echo "🔍 Checking prerequisites..."

# Appium server
if ! curl -s http://127.0.0.1:4723/status | grep -q '"ready":true'; then
    echo "⚠️  Appium server not running. Starting..."
    appium &
    sleep 5
fi

# Emulator
if ! adb devices | grep -q "emulator-5554"; then
    echo "❌ Emulator not running. Start emulator first."
    exit 1
fi

# App installed
if ! adb shell pm list packages | grep -q "com.telcoagent.udpclient"; then
    echo "❌ NetProbe not installed. Run: adb install /tmp/netprobe-signed.apk"
    exit 1
fi

# Python deps
python3 -c "from appium import webdriver" 2>/dev/null || {
    echo "📦 Installing Appium Python Client..."
    pip install Appium-Python-Client --quiet
}

# Run tests
echo "🚀 Running NetProbe tests..."
python3 test_netprobe.py "$@"
