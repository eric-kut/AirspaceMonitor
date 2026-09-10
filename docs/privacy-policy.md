# AirspaceMonitor — Privacy Policy

_Last updated: 2026-09-08_

AirspaceMonitor is a free, offline-first airspace awareness tool for drone pilots. It monitors public ADS-B flight data and alerts you when aircraft enter zones you define. **The app has no accounts, no advertising, no analytics, and no tracking of any kind.**

## Data the app processes on your device

- **Your defined zones and settings** (geofences, profiles, alert settings) are stored only in the app's local database on your device. They are never uploaded to us or to anyone else.
- **Event history** (aircraft enter/exit episodes, recorded positions) is stored only locally and can be exported by you as CSV. It is never transmitted.
- **Notifications and sounds** are generated locally.

## Location permission

The app requests precise location only to:

1. Show your position on the map, and
2. Support the optional "follow phone" zone mode, if you enable it.

The app does **not** access location in the background for any other purpose, does not build a location history, and does not transmit your location to us (the developer). We never receive any of your data.

## Data sent to third-party services (server requests)

To display nearby aircraft, terrain elevation, and maps, the app sends limited requests to these community/subscription-free services:

| Service | Purpose | What is sent |
|---|---|---|
| adsb.lol (https://www.adsb.lol) | ADS-B aircraft data | Coordinates of the area you are monitoring (zone center, or your device's approximate position if you use the "follow phone" mode), as a radius query |
| airplanes.live (https://airplanes.live) | ADS-B aircraft data | Same as above |
| adsb.fi (https://adsb.fi) | ADS-B aircraft data | Same as above |
| Open-Meteo (https://open-meteo.com) | Ground elevation for altitude checks | The coordinates of your zone center |
| OpenStreetMap tile servers (https://www.openstreetmap.org/copyright) | Map imagery | Standard map tile requests (tile coordinates, no identifiers) |

These requests contain **no personal identifiers** (no name, email, device ID, or account data). However, the coordinates you monitor — including your own approximate position in "follow phone" mode — are visible to those services. If this concerns you, use only fixed zone modes (circle/polygon) centred on your flying field rather than the "follow phone" mode. The app uses each service per its published terms: adsb.lol data is used under the Open Database License (ODbL); airplanes.live and adsb.fi data is used non-commercially with attribution, shown in the app under **Settings → Data sources**.

## Children

The app is not directed at children under 13 and collects no personal information from anyone.

## Changes

We will update this policy if the app's data handling changes. The current version is always available at this URL.

## Contact

Questions? Contact: _[YOUR EMAIL]_