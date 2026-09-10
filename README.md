# TrafficWatcher

Sideloaded situational-awareness app for recreational drone pilots in Canada.
Polls free, keyless, crowdsourced ADS-B feeds, filters aircraft against a
geofence (follow-phone circle, fixed circle, or hand-drawn polygon) plus an
altitude ceiling (ASL or AGL), and raises high-priority **local notifications**
(with bearing, distance, and a map deep link) when matching aircraft enter the
monitored zone. Zero accounts, zero subscriptions, zero Google components:
designed for **GrapheneOS without Play Services**.

> **Advisory only.** Many low-altitude aircraft (GA, helicopters, ultralights,
> gliders) do not transmit ADS-B and CANNOT appear here. You must always
> maintain visual line of sight and give way to crewed aircraft (CARs Part IX).

## Tech stack

- Kotlin, Jetpack Compose, MVVM + unidirectional data flow, coroutines/Flow
- Manual DI (`AppContainer` — no framework, stated design decision)
- Room (profiles), DataStore Preferences (settings), SharedPreferences (snooze)
- OkHttp + kotlinx.serialization
- osmdroid (OpenStreetMap) with a unique HTTP User-Agent per OSM tile policy;
  tile server URL is user-overridable in Settings
- `android.location.LocationManager` (GPS) — no fused provider
- minSdk 26, targetSdk 34, JDK 17, Gradle Kotlin DSL, no ProGuard, no
  analytics/tracking whatsoever

## Prerequisites

- JDK 17 (`winget install EclipseAdoptium.Temurin.17.JDK` or equivalent)
- Android SDK: platform 34, build-tools 34.0.0, platform-tools
  (set `sdk.dir` in `local.properties`, or install Android Studio)
- No Google Play Services needed at build or run time.

## Build (headless)

```bash
./gradlew assembleRelease        # release APK (unsigned unless keystore.properties present)
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests (geometry, altitude filter, alert state machine)
```

Output: `app/build/outputs/apk/release/app-release.apk`

## Release keystore generation & signing

1. Generate a key (one time, keep it safe — you'll need it for every update):

   ```bash
   keytool -genkeypair -v \
     -keystore trafficwatcher.keystore \
     -alias trafficwatcher \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Create `keystore.properties` in the project root:

   ```properties
   storeFile=trafficwatcher.keystore
   storePassword=YOUR_STORE_PASSWORD
   keyAlias=trafficwatcher
   keyPassword=YOUR_KEY_PASSWORD
   ```

3. `./gradlew assembleRelease` now signs the APK automatically
   (see `app/build.gradle.kts`). Verify with:

   ```bash
   "$LOCALAPPDATA/Android/Sdk/build-tools/34.0.0/apksigner.bat" verify \
     --print-certs app/build/outputs/apk/release/app-release.apk
   ```

## Install on a GrapheneOS Pixel (adb)

Enable Developer options → USB debugging (or Wireless debugging), then:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

GrapheneOS sideloading is identical to any Android 14 device — no
Play Store involvement. On first install Android will ask you to allow
installs from the source (if using the built-in "Install via USB" from an
app) — direct `adb install` needs no such toggle.

## GrapheneOS runtime-permission checklist

Do these after first launch (Settings → Apps → TrafficWatcher → Permissions,
or via the in-app prompts):

| Permission | Required? | Notes |
|---|---|---|
| **Notifications** | Required for alerts | Prompted on first Start. Without it, no heads-up alerts appear. |
| **Location** | Required on Android 14+ to start the monitoring foreground service (its type is `location`); needed continuously for FOLLOW_PHONE mode | Prompted on first Start. Choose "While using the app" — sufficient while monitoring. |
| **Battery optimization** | Recommended | Settings screen → "Request exemption" (system dialog). Android may throttle the service with the screen off otherwise; GrapheneOS is generally less aggressive but the exemption guarantees multi-hour runs. |
| Microphone, Camera, Contacts, etc. | Never requested | The app uses none of them. |
| Network sandbox (GrapheneOS) | Keep enabled | Needed to reach the ADS-B feeds, Open-Meteo, and OSM tiles. |

Also note in GrapheneOS **Settings → Apps → TrafficWatcher**: the app has no
"Unrestricted" data access requirement — ordinary foreground access suffices
because the foreground service holds a notification.

## Test mode (verify alerting with airplane mode ON)

1. Map tab → pick a profile → **Start**.
2. Turn on airplane mode / kill data — polling will fail (harmless).
3. Test tab → set altitude/distance/bearing (default: 2 km NE, 2,500 ft) →
   **Inject aircraft**.
4. A heads-up notification appears within 2 poll cycles (~24 s at the
   default 12 s interval). Tapping **Map** opens
   `https://globe.adsbexchange.com/?icao=<hex>`; **FR24** opens the callsign
   page (hidden when no callsign); **Snooze 5 min** suppresses alerts.
5. **Remove** clears the injection.

## Data sources & API credits

- ADS-B: community feeds (all free, keyless, user-reorderable in Settings):
  - https://api.adsb.lol/v2 (adsb.lol)
  - https://api.airplanes.live/v2 (airplanes.live)
  - https://api.adsb.fi/v2 (adsb.fi)
  - Query: `GET {base}/point/{lat}/{lon}/{radius_nm}` (tar1090/readsb v2 API)
- Terrain elevation (AGL ceilings, on-demand only):
  https://api.open-meteo.com/v1/elevation (~90 m DEM)
- Map tiles: OpenStreetMap contributors — https://www.openstreetmap.org/copyright

## AGL approximation (documented in-app)

AGL = aircraft altitude MSL − **profile** terrain elevation (one Open-Meteo
sample for the profile center, ~90 m resolution). Terrain varies inside a
zone, and aircraft altitude is barometric/geometric MSL, not true height
above terrain. Treat AGL figures as advisory.

## Known limitations

- **MLAT coverage**: MLAT positions (shown as "MLAT" in alerts) are only
  available around equipped receiver clusters and are less accurate/older
  than ADS-B.
- **Latency**: crowdsourced feeds typically lag real time by 1–15 s, plus the
  configured poll interval. Alerts are *not* collision avoidance.
- **Data provenance**: positions come from volunteer receivers; coverage can
  drop out, aircraft can be missing, and fields (`alt_baro` = "ground",
  missing hex, etc.) are filtered defensively but data quality varies.
- **No ADS-B ≠ no aircraft**: gliders, ultralights, most helicopters and many
  GA aircraft do not broadcast. See the disclaimer.
- Polygons must not cross the antimeridian (±180° longitude) — not an issue
  for Canadian operations.
- v1 does not do per-aircraft terrain lookups (rate limits) — AGL is the
  profile-constant approximation described above.

## Project layout

```
app/src/main/java/ca/trafficwatcher/
  domain/    pure Kotlin: models, GeoMath (haversine/bearing/compass/PIP),
             Pipeline (geofence+altitude filter), AircraftTracker (alert FSM), Units
  data/      Room (profiles), SettingsStore (DataStore), AdsBClient (failover),
             OpenMeteoClient, DTOs (kotlinx.serialization)
  service/   MonitoringService (foreground, coroutine poll loop), Notifier,
             MonitorState, SnoozeStore/Receiver, TestInjector
  ui/        Compose: Home (osmdroid map + FAB), Profiles, Profile editor
             (polygon drawing), Settings, Test mode, first-run disclaimer
app/src/test/ JUnit: GeoMathTest, AltitudeFilterTest, TrackerTest
```