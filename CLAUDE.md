# Curveon

Offline motorcycle dashboard for a Hugerock X7 tablet (7", 1920x1080 landscape, Android 13) on the
handlebars, plus a companion app on the rider's Pixel 9a. Module overview, features and build
commands are in [README.md](README.md); goals in [App plan.md](App%20plan.md) and [dashboard.md](dashboard.md).

## Build and run

- JDK 21 lives in `~/.local/share/jdks/jdk-21.0.12.1+1` (set via `org.gradle.java.home` in
  `~/.gradle/gradle.properties`); the system JRE 25 can't build. SDK: `~/Android/Sdk`.
- Emulators: `~/Android/Sdk/emulator/emulator -avd Hugerock_X7` (main target, 960x540 dp) or
  `-avd Curveon_Phone` (for `:companion`). The emulator has no Bluetooth: use demo phone data / demo ride.
- Tablet: `./gradlew :app:installDebug`, then
  `adb shell am start -n com.curveon.tablet/com.curveon.MainActivity`.
- Phone: `./gradlew :companion:installDebug` (package `com.curveon.companion`).
- Tests: `./gradlew test`. `:routing:test` routes over real Norway data.
- Large data (pmtiles, BRouter segments, search index, OSM extracts) lives outside the repo in
  `~/develop/Curveon-data`; on the tablet it goes under `Android/data/com.curveon.tablet/files/`.

## How to work here

- **Offline first.** The rider is normally without a connection in Norway; anything online is an
  extra, never a requirement.
- **Minimal UI.** Prefer few controls that do the right thing in context over new buttons: reuse an
  existing surface or gesture (tap a card, long-press the map, empty search state) and show
  info only when it matters. Rail screens open as a `CockpitDialog`.
- **No voice guidance** (deliberately not planned).
- **Every text in two languages.** The rider picks English or Norwegian in Settings → Display. Any
  text the rider can see goes in `res/values/strings.xml` (English) *and* `res/values-nb/strings.xml`
  (Norwegian Bokmål), never as a literal in Kotlin: `stringResource(R.string.x)` in Compose,
  `context.getString(...)` elsewhere, `pluralStrings` for counts. Keep the Norwegian natural (not
  word-for-word) and as short as the English so it fits the cockpit cards. Names from data (places,
  roads, tracks) are not translated.
- **Companion is a background service** (Bluetooth link, notifications, media, voice replies), not a
  navigation app: keep its UI to setup/status. It stays in this repo so both apps share `:protocol`.
- **Device checks.** Whenever the user should test or run something on the real X7 or Pixel
  (e.g. `adb logcat -s PhoneLink` after a call), add it to [device-checks.md](device-checks.md)
  (newest first, checkboxes) in the same change, as well as saying it in chat. Remove items once
  the user reports back.
- Bluetooth, real calls, tunnel dead reckoning and gyro lean can't be verified in the emulator;
  say so rather than claiming they work.
- Commit messages describe the change from the rider's point of view (see `git log`).
- Installing a new build on the X7 and/or Pixel is always allowed whenever they are connected
  (`adb devices`); do it after each change without asking.
- Committing and pushing are always allowed once a build has been installed on the devices (X7 or
  Pixel); no need to ask first.
- The app was renamed from VibeNav on 2026-10-06: anything still saying vibenav is stale.
