# VibeNav

Offline motorcycle dashboard for a handlebar-mounted Android tablet (target: Hugerock X7, 7"
1920x1080, Android 13), with a companion app on the rider's phone. See [App plan.md](App%20plan.md)
and [dashboard.md](dashboard.md) for the goals.

| Module | What it is |
|---|---|
| `app` | The tablet app (`com.vibenav.tablet`): map, navigation, dashboard |
| `companion` | Phone app (`com.vibenav.companion`): mirrors messages, calls and music to the tablet over Bluetooth |
| `routing` | Offline routing on BRouter: motorcycle profile, roundtrips, GPX following, route tracking |
| `protocol` | Tablet ⇄ phone messages (length-prefixed JSON over RFCOMM) |
| `tools/map`, `tools/search` | Scripts that build the offline map and search index |

## What works

- **Offline map** of Norway (Protomaps vector tiles, MapLibre), dark style, heading-up follow,
  tap map for full screen, long-press to navigate somewhere.
- **Offline routing** with Fastest / Curvy / Super curvy, Tarmac / Mixed / Gravel, avoid
  ferries / motorways; ETA and remaining distance; rerouting when you leave the route.
- **GoRide-style roundtrip**: pick direction(s) and distance.
- **GPX**: import tracks/routes, ride them (also reversed), routing onto the track first.
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
  tap the speed card to see them, export GPX or ride one again.
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
./gradlew :companion:installDebug    # phone app (with the phone connected)
./gradlew test                       # unit tests; :routing:test also routes over real Norway data
```

Emulator matching the tablet: AVD `Hugerock_X7` (API 33, 1080x1920 @ 320 dpi, landscape).

## Data for the tablet (one-time)

Everything lives under `Android/data/com.vibenav.tablet/files/` on the tablet; copy over USB
or with `adb push`.

| Folder | File(s) | Size | How to get it |
|---|---|---|---|
| `maps/` | `norway.pmtiles` | 1.7 GB | `tools/map/build-norway-map.sh` (or Settings → Import map) |
| `routing/segments4/` | `E*_N*.rd5` (16 files) | 210 MB | Settings → Download routing data (when online), or from https://brouter.de/brouter/segments4/ |
| `search/` | `norway-search.sqlite` | 150 MB | `tools/search/build_search_index.py` (see `tools/search/README.md`) |

## Phone setup (Pixel)

1. Pair phone and tablet in Android's Bluetooth settings.
2. Install the companion app, open it, grant **Notification access** and **Bluetooth,
   microphone, contacts**, tap **Start sharing with tablet**.
3. On the tablet: Phone link (phone icon in the rail) → pick the phone.
