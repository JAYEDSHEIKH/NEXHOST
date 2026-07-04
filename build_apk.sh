#!/usr/bin/env bash
set -e

export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "=== MojoLauncher APK Builder ==="
echo "ANDROID_HOME: $ANDROID_HOME"
echo "Java: $(java -version 2>&1 | head -1)"
echo ""

./gradlew :app_pojavlauncher:assembleFullDebug --no-daemon "$@"

echo ""
echo "=== Build complete ==="
echo "APK location:"
find app_pojavlauncher/build/outputs/apk -name "*.apk" 2>/dev/null || echo "No APKs found yet"
