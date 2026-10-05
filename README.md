# VibeNav

Offline motorcycle dashboard for a handlebar-mounted Android tablet (target: Hugerock X7,
7" 1920x1080, Android 13): OpenStreetMap navigation with curvy-road routing, plus music and
notification mirroring from the rider's phone. See [App plan.md](App%20plan.md) for goals.

## Build and run

Requirements: JDK 17+ (21 recommended) and the Android SDK (`local.properties` → `sdk.dir`).

```
./gradlew installDebug          # build + install on the running emulator/device
./gradlew testDebugUnitTest     # unit tests
```

Emulator matching the target tablet: AVD `Hugerock_X7` (API 33, 1080x1920 @ 320 dpi, landscape).

```
~/Android/Sdk/emulator/emulator -avd Hugerock_X7 &
```

## Offline map

The map is a Protomaps PMTiles extract of Norway (zoom 0–14, ~1.7 GB) rendered with MapLibre.
Build it with `tools/map/build-norway-map.sh`, then copy `norway.pmtiles` to
`Android/data/com.vibenav.tablet/files/maps/` on the tablet (USB file transfer or `adb push`), or
use **Settings → Import .pmtiles map file**. Without a map file the app falls back to online OSM
tiles.
