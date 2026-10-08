# Curveon

Offline motorcycle dashboard for a handlebar-mounted Android tablet (target: Hugerock X7, 7"
1920x1080, Android 13), with a companion app on the rider's phone. See [App plan.md](App%20plan.md)
and [dashboard.md](dashboard.md) for the goals.

| Module | What it is |
|---|---|
| `app` | The tablet app (`com.curveon.tablet`): map, navigation, dashboard |
| `companion` | Phone app (`com.curveon.companion`): mirrors messages, calls and music to the tablet over Bluetooth |
| `routing` | Offline routing on BRouter: motorcycle profile, roundtrips, GPX following, route tracking |
| `protocol` | Tablet ⇄ phone messages (length-prefixed JSON over RFCOMM) |
| `tools/map`, `tools/search` | Scripts that build the offline map and search index |

## What works

- **Offline maps of any country** (Protomaps vector tiles, MapLibre): Settings → Maps & data →
  Download countries, by continent like DMD2; each country brings its routing data and offline
  search (full from the public curveon-data repo, or built from the map). Several
  countries show together. Dark style, heading-up follow, long-press to navigate somewhere.
- **Offline routing** with Fastest / Curvy / Super curvy, Tarmac / Mixed / Gravel, avoid
  ferries / motorways; ETA and remaining distance; rerouting when you leave the route.
- **GoRide-style roundtrip**: pick direction(s) and distance.
- **GPX**: import tracks/routes, ride them (also reversed), routing onto the track first. Tracks live in
  groups (a multi-track file becomes one) that can show/hide, colour and invert all their tracks at once
  while keeping each track's own settings; tracks and groups can be renamed and exported as GPX.
  Waypoints show as coloured dots when zoomed out and as an icon per kind (camping, fuel, ferry, …)
  when zoomed in; tapping one shows everything the file has on it (address, phone, links, …).
- **Destination search**, offline (towns, addresses with house numbers, fuel, cafés,
  viewpoints, campsites, ferries, passes…), plus online results when the tablet has a connection.
- **Route preview**: Fastest / Curvy / Super curvy side by side (time, km, curviness, gravel and
  ferries) before you ride; surface and avoid options adjust them in place.
- **Stops**: pick a place while riding → *Add stop* (inserted where it adds least detour) or *Go here*.
- **Saved routes and recents** show up in the empty search, with a *Nearest fuel* shortcut.
- **Fuel along the route** in the trip bar (tap to add it as a stop), amber when the gap is long.
- **Speed camera warnings** (OSM fixed cameras), navigating or free riding.
- **Road numbers/names** in the turn banner ("Turn right · Fv33 Storgata").
- **Day/night map** that follows the sun at your position (or fixed in Settings).
- **Tunnels**: position keeps moving along the route at your speed when GPS drops out.
- **Ride log**: rides record automatically (distance, time, speeds, climb, gyro lean angle);
  find them under Rides in the GPX & rides dialog (rail), save one to the GPX collection or ride one again.
- **Speed limit sign** from OSM data, while navigating and while free riding.
- **Dashboard** (Android Auto-like): speed + limit, newest message with Reply/Dismiss, calls
  with Answer/Decline, music with full cover art when nothing else needs the space.
- **Spoken replies**: Reply → the phone listens through the headset → the tablet shows what was
  heard → Send.
- **Demo ride** (Settings) drives the active route without GPS; **demo phone data** (Phone link)
  shows the dashboard without a phone.

Not done yet: OBD2 adapter support (the card only shows once an adapter is connected, which
isn't implemented), voice guidance (deliberately not planned).

## Build

Requirements: JDK 17+ (21 recommended) and the Android SDK (`local.properties` → `sdk.dir`).

```
./gradlew :app:installDebug          # tablet app on the connected device/emulator
./gradlew :app:installRide           # same app, not debuggable: routing ~40% faster (for the bike)
./gradlew :companion:installDebug    # phone app (with the phone connected)
./gradlew test                       # unit tests; :routing:test also routes over real Norway data
./gradlew :routing:test --tests '*RoutingBenchmarkTest*' -Dcurveon.bench=true   # routing timings
```

The ride build is signed with the debug key, so it installs over a debug build and keeps the
app's data. After installing, `adb shell cmd package compile -m speed -f com.curveon.tablet`
compiles it ahead of time instead of waiting for Android to do it overnight.

Emulator matching the tablet: AVD `Hugerock_X7` (API 33, 1080x1920 @ 320 dpi, landscape).

## Data for the tablet (one-time)

Everything lives under `Android/data/com.curveon.tablet/files/` on the tablet; copy over USB
or with `adb push`.

| Folder | File(s) | Size | How to get it |
|---|---|---|---|
| `maps/` | `<country>.pmtiles` | Norway 1.7 GB | Settings → Download countries (when online), or `tools/map/build-norway-map.sh` / Settings → Import |
| `routing/segments4/` | `E*_N*.rd5` | Norway 210 MB | Comes with each country, or from https://brouter.de/brouter/segments4/ |
| `search/` | `<country>.sqlite` | Norway 150 MB | Comes with each country (see `tools/search/README.md`) |

The country list (`app/src/main/assets/map/countries.json`: outlines as zoom-10 tiles, sizes,
routing tiles) is rebuilt with `tools/map/build-countries.sh`; only needed if borders or the
split of big countries should change.

## Phone setup (Pixel)

1. Pair phone and tablet in Android's Bluetooth settings.
2. Install the companion app, open it, grant **Notification access** and **Bluetooth,
   microphone, contacts**, tap **Start sharing with tablet**.
3. On the tablet: Phone link (phone icon in the rail) → pick the phone.
