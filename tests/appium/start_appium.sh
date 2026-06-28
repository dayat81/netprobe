#!/bin/bash
# Start Appium server with proper environment
export ANDROID_HOME=/home/hidayat/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
exec appium "$@"
