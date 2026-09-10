#!/usr/bin/env bash
# Signed release build for AirspaceMonitor. Run from anywhere:
#   bash build-release.sh
set -e
cd "$(dirname "$0")"

export JAVA_HOME='/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew assembleRelease

echo
echo "Signed APK: app/build/outputs/apk/release/app-release.apk"