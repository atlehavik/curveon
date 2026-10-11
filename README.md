# Curveon

Offline motorcycle dashboard for a handlebar-mounted Android tablet (target: Hugerock X7, 7"
1920x1080, Android 13), with a companion app on the rider's phone. See [App plan.md](App%20plan.md)
and [dashboard.md](dashboard.md) for the goals.

| Module | What it is |
|---|---|
| `app` | The tablet app (`com.curveon.tablet`): map, navigation, dashboard |
| `companion` | Phone app (`com.curveon.companion`), installed on its own: mirrors messages, calls and music to the tablet over Bluetooth |
| `phone` | What a phone shares (messages, calls, music, contacts, spoken replies), read on the device itself; used by `companion`, and by `app` when it is its own phone |
| `routing` | Offline routing on BRouter: motorcycle profile, roundtrips, GPX following, route tracking |
| `protocol` | Tablet ⇄ phone messages (length-prefixed JSON over RFCOMM) |
| `tools/map`, `tools/search` | Scripts that build the offline map and search index |

## What works

- **Offline maps of any country** (Protomaps vector tiles, MapLibre): Settings → Maps & data →
  Download countries, by continent like DMD2; each country brings its routing data and offline
  search (full from the public curveon-data repo, or built from the map). Several
  countries show together. Dark style, heading-up follow, long-press to navigate somewhere.
  Outside the downloaded countries the map comes from OpenFreeMap's free online tiles (no key) in the
  same colours, when there is a connection; tiles seen once stay cached (300 MB) for offline use.
- **Offline routing** with Fastest / Curvy / Super curvy, Tarmac / Mixed / Gravel / Offroad, avoid
  ferries / motorways; ETA and remaining distance; rerouting when you leave the route.
  Every road a motorcycle may use is routable, tracks of any grade included: with Tarmac a track is
  only a last resort (to reach a place that is on one), never a short cut. Cycle paths and footpaths
  are never used (not allowed by law) – except at the very start or end when the map joins the place
  to the roads only through one (the map is wrong, or the place is on a path): then the first/last leg
  uses as little path as there is no road way around, however long that is. Never in the middle.
  Online with no country downloaded, just the routing tiles a trip needs are fetched as it is planned
  (and kept).
- **Roundtrip** (from "Where to?" when no trip is planned): draw the area to ride in on the map with a finger; the loop avoids riding the same road twice. Can start elsewhere than where you are, and "Edit on map" turns the loop into an ordinary trip through via points.
- **GPX**: import tracks/routes, ride them (also reversed), routing onto the track first. Tracks live in
  groups (a multi-track file becomes one) that can show/hide, colour and invert all their tracks at once
  while keeping each track's own settings; tracks and groups can be renamed and exported as GPX.
  Waypoints show as coloured dots when zoomed out and as an icon per kind (camping, fuel, ferry, …)
  when zoomed in; tapping one shows everything the file has on it (address, phone, links, …).
- **Destination search**, offline (towns, addresses with house numbers, fuel, cafés,
  viewpoints, campsites, ferries, passes…, and businesses from Overture Maps), plus online results
  when the tablet has a connection: offline hits show at once, then Photon (OSM, worldwide) and in
  Norway Kartverket's place names (farms, bays, lakes, …) and addresses are mixed in.
- **Route preview**: Fastest / Curvy / Super curvy side by side (time, km, curviness, gravel and
  ferries) before you ride; surface and avoid options adjust them in place.
- **Stops**: pick a place while riding → *Add stop* (inserted where it adds least detour) or *Go here*.
- **Editing a planned trip**: tap a stop chip to move it earlier/later, *Change place* (search or pick
  on map), *Add stop before*, or remove it. **Edit on map** (long-press the route line, or the squiggle in
  the preview): drag a stop, the start or the destination to move it, or drag the line itself to make
  the route go through that point (a dashed guess follows the finger, then it re-routes); Undo / Done.
- **Saved A→B routes** open with their stops, start and destination: ridden on the exact saved line until
  something is changed; saving again under the same name updates the saved route.
- **Pick on map** in every search (destination, stop, start): the search shrinks into a bar on the full
  map; tap to drop a pin (tap again to move it), then *Go here* / *Add stop* / *Start here*.
- **Saved routes and recents** show up in the empty search, with a *Nearest fuel* shortcut.
- **Fuel along the route** in the trip bar (tap to add it as a stop), amber when the gap is long.
- **Speed camera warnings** (OSM fixed cameras), navigating or free riding.
- **Road numbers/names** in the turn banner ("Turn right · Fv33 Storgata").
- **Day/night map** that follows the sun at your position (or fixed in Settings).
- **Tunnels**: position keeps moving along the route at your speed when GPS drops out.
- **Ride log**: rides record automatically (distance, time, speeds, climb, gyro lean angle);
  find them under Rides in the GPX & rides dialog (rail), save one to the GPX collection or ride one again.
- **Speed limit sign**, while navigating and while free riding: the official limit where a country
  publishes them freely (Norway: Statens vegvesen NVDB; Finland: Digiroad; the Netherlands: NDW/RWS;
  Flanders: AWV), built monthly by the curveon-data repo (`tools/speedlimits/`); OSM's maxspeed elsewhere.
- **Places on the map** (Settings → Display → On the map; only fuel until the rider chooses): fuel, speed cameras, viewpoints, mountain
  passes, camping, food and coffee, ferries, motorcycle shops and workshops can be kept on the map at all
  times, from the offline search index of the downloaded countries; each appears when the map is zoomed in
  enough for it to be readable, and a tap opens it like a GPX waypoint.
- **Road info**: hold a finger on a road to see its speed limit, name or number, kind and surface.
- **Dashboard** (Android Auto-like): speed, limit and clock on the map; newest message with
  Reply/Dismiss, calls with Answer/Decline, music with full cover art when nothing else needs the space.
- **Engine panel** (Settings → Engine, optional): fuel, RPM, coolant and battery from an ELM327
  OBD2 adapter paired over Bluetooth (classic serial profile); readings the bike doesn't report stay "–".
  "Demo" makes up values. Above the music on the tablet; beside it, bottom right, on a phone held upright.
- **Spoken replies**: Reply → the phone listens through the headset → the tablet shows what was
  heard → Send.
- **Welcome tour** on the first start (language first; animated how-tos: how tablet, phone and intercom fit together, Curveon on just a phone, planning, editing a trip on the map, drawing a roundtrip): the screen dims and a lit ring walks over the buttons and
  gestures with a card for each; again any time from Settings → Help, which also lists every symbol.
- **Demo ride** (Settings) drives the active route without GPS; **demo phone data** (Phone link)
  shows the dashboard without a phone.

Not done: voice guidance (deliberately not planned). The OBD2 link is untested with a real adapter.

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
3. On the tablet: Settings → Phone → pick the phone. Another paired phone can be picked there any
   time (pair it in Android's Bluetooth settings first).

Curveon turns with the device (landscape or portrait, as Android's auto-rotate says).

Curveon running on a phone (or a tablet with its own apps) needs no companion: Settings → Phone →
**This device**, then allow notification access and contacts/calls/microphone there.
