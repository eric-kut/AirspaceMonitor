#!/usr/bin/env bash
# Verify the release APK signature. Run from anywhere:  bash verify-apk.sh
set -e
cd "$(dirname "$0")"

export JAVA_HOME='/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
export PATH="$JAVA_HOME/bin:$PATH"

APK=app/build/outputs/apk/release/app-release.apk
APKSIGNER="$LOCALAPPDATA/Android/Sdk/build-tools/34.0.0/apksigner.bat"

if [ ! -f "$APK" ]; then
  echo "No APK found at $APK — run: bash build-release.sh"
  exit 1
fi

"$APKSIGNER" verify --print-certs "$APK"
echo
echo "Signature OK. Copy $APK to the phone and install with SAI."