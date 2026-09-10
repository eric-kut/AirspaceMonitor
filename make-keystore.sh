#!/usr/bin/env bash
# One-time release keystore generation for TrafficWatcher.
# Run from the project root:  bash make-keystore.sh
set -e
cd "$(dirname "$0")"

KEYTOOL="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin/keytool"

if [ -f trafficwatcher.keystore ]; then
  echo "trafficwatcher.keystore already exists — not overwriting it."
  exit 1
fi

"$KEYTOOL" -genkeypair -v \
  -keystore trafficwatcher.keystore \
  -alias trafficwatcher \
  -keyalg RSA -keysize 4096 -validity 10000

echo
echo "Done. Now create keystore.properties in the project root:"
echo "  storeFile=trafficwatcher.keystore"
echo "  storePassword=<the keystore password you entered>"
echo "  keyAlias=trafficwatcher"
echo "  keyPassword=<the key password you entered>"