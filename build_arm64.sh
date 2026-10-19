#!/bin/bash
set -e

echo "=== Building NekoBoxForAndroid (arm64-v8a only) ==="

BASEDIR="$(pwd)"

# 1. Check prerequisites
if ! command -v go &> /dev/null; then
    echo "Error: 'go' is not installed or not in PATH."
    exit 1
fi

if ! command -v java &> /dev/null && [ -z "$JAVA_HOME" ]; then
    echo "Error: 'java' is not installed and JAVA_HOME is not set."
    exit 1
fi

if [ -z "$ANDROID_HOME" ]; then
    if [ -d "$HOME/Android/Sdk" ]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    elif [ -d "$LOCALAPPDATA/Android/Sdk" ]; then
        export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
    else
        echo "Error: ANDROID_HOME is not set."
        exit 1
    fi
fi

# 2. Prepare sing-box & libneko sources with x25519mlkem768 patch
echo ">> Step 1: Preparing core sources..."
bash buildScript/lib/core/get_source.sh

# 3. Build libcore.aar for arm64-v8a
echo ">> Step 2: Building native libcore.aar (target: android/arm64)..."
bash buildScript/lib/core.sh

# 4. Prepare geoip / geosite assets
echo ">> Step 3: Preparing assets..."
bash buildScript/init/action/gradle.sh

# 5. Build APK
echo ">> Step 4: Compiling arm64-v8a APK..."
./gradlew app:assembleOssRelease

echo "=== Build Complete ==="
find app/build/outputs/apk -name "*arm64-v8a*.apk"
