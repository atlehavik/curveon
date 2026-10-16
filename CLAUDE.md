# Curveon

Offline motorcycle dashboard for a Hugerock X7 tablet (7", 1920x1080 landscape, Android 13) on the
handlebars, plus a companion app on the rider's Pixel 9a. Module overview, features and build
commands are in [README.md](README.md); goals in [dashboard.md](dashboard.md).

## Build and run

- JDK 21 lives in `~/.local/share/jdks/jdk-21.0.12.1+1` (set via `org.gradle.java.home` in
  `~/.gradle/gradle.properties`); the system JRE 25 can't build. SDK: `~/Android/Sdk`.
- Emulators: `~/Android/Sdk/emulator/emulator -avd Hugerock_X7` (main target, 960x540 dp) or
  `-avd Curveon_Phone` (for `:companion`, and for Curveon itself at phone size, e.g. portrait). The
  emulator has no Bluetooth: use demo phone data / demo ride. Curveon_Phone has no maps or routing
  data; to see a route preview etc. at Pixel 9a size, shrink the X7 AVD instead:
  `adb shell wm size 1080x2400; adb shell wm density 420` (undo with `wm size reset; wm density reset`).
  Rotation: `adb shell settings put system user_rotation 0` (portrait) / `1` (landscape).
- **Layouts** must work on the X7 in landscape and on a phone (Pixel 9a, 411 dp wide) in both
  orientations: `isNarrowScreen()` (CockpitDialog.kt) stacks what sits side by side on the tablet.
- Tablet: `./gradlew :app:installDebug`, then
  `adb shell am start -n com.curveon.tablet/com.curveon.MainActivity`.
- **The X7 is reached over wifi, not USB** (it lives on the handlebars). Before installing, find it:
  `adb mdns services` lists it as `_adb-tls-connect` once Wireless debugging is on and this PC is
  paired; `adb connect <ip:port>` from that line (the port changes each time Wireless debugging is
  switched on). Not paired yet: on the X7, Settings → Developer options → Wireless debugging → Pair
  device with pairing code, then `adb pair <ip:pairport> <code>` (ask the rider for the code).
  The X7 is already paired (mDNS name `adb-241181d1-…`, usually 192.168.86.250). Android switches
  Wireless debugging off by itself whenever the tablet's wifi drops (screen off/sleep, roaming,
  reboot), so it often has to be turned on again; "Connection refused" or "device offline" means
  that happened (`adb mdns services` may still show a stale entry). Once connected, run
  `adb -s <ip:port> tcpip 5555` and use `<ip>:5555` from then on: that survives wifi drops until a
  reboot. With several devices, pick the X7 with `-s <ip:port>` / `ANDROID_SERIAL`.
- Phone: `./gradlew :companion:installDebug` (package `com.curveon.companion`).
- **Curveon on the Pixel is release-signed** (since 2026-10-15), so it can also update itself from
  GitHub releases: install it with `./gradlew :app:assembleRelease` (signing env from
  `~/.curveon-signing/signing.env`) and `adb install -r app/build/outputs/apk/release/app-release.apk`,
  never `installDebug` (Android refuses a debug build over it). Release builds have no DEV aids
  (demo data, fake OBD) and no `run-as`, but do have the demo ride (Settings → Navigation). The Pixel is found on wifi like the X7 (`adb connect <ip>:5555`,
  last seen at 192.168.86.199); the X7 stays on debug builds.
- **Crash reports** (`CrashLog.kt`): one `.txt` per crash under
  `Android/data/com.curveon.tablet/files/crashes/` on the device, also on release builds:
  `adb -s <device> pull /sdcard/Android/data/com.curveon.tablet/files/crashes`. Native crashes and
  ANRs are copied from Android on the next start; a native one's `.txt` has the crashing thread's
  stack, and the whole tombstone (all threads, recent log) is in the `.pb` next to it. Look here
  first when the rider says the app crashed.
- **Map renderer: OpenGL ES** (`android-sdk-opengl`), not MapLibre's default Vulkan: Vulkan crashed
  in the Mali driver on the Pixel 9a (five times on 2026-10-16, `vkCmdBeginRenderPass`), and both
  devices have Mali GPUs. Switching back is the artifact name in `gradle/libs.versions.toml`.
- Tests: `./gradlew test`. `:routing:test` routes over real Norway data.
- Large data (pmtiles, BRouter segments, search index, OSM extracts) lives outside the repo in
  `~/develop/Curveon-data`; on the tablet it goes under `Android/data/com.curveon.tablet/files/`.

## How to work here

- **Offline first.** The rider can be offline. Application must work online, but maps and other large rescourses must have option do be downloaded. Smaller rescourses (file size) should be included in online version if user is physically present in the country the rescourses include. All rescourses for a country should be included when downloading offline map for said country.
- **Minimal UI.** Prefer few controls that do the right thing in context over new buttons: reuse an
  existing surface or gesture (tap a card, long-press the map, empty search state) and show
  info only when it matters. Rail screens open as a `CockpitDialog`.
- **Battery and responsiveness.** Every choice in the app (libraries, renderers, polling, sensors,
  background work) is weighed for battery use and for how responsive the app stays. When crash
  reports show that something must change, and the fix (e.g. another driver path or library) costs
  more battery than today, crash-freedom weighs heavier: a crash while riding is worse than the
  extra battery. Say what the change costs.
- **Google Play rules.** Every choice (permissions, libraries and their licences, what is downloaded
  after install, foreground services, background work, data collected) is weighed against Google
  Play's developer policies and technical requirements (e.g. 16 KB page-aligned native libraries),
  even though releases go out on GitHub today. Say when a choice breaks or risks a Play rule, and
  note it in [google-play.md](google-play.md).
- **New visual elements: test for overlap.** Before a new visual element goes in, try it in the
  phone-size emulator (Pixel, see Build and run) in both portrait and landscape, while navigating and
  not navigating, and with the music player on and off. Overlap with other elements is the main
  thing to look for every time; check the X7 layout too.
- **Every text in two languages.** The rider picks English or Norwegian in Settings → Display. Any
  text the rider can see goes in `res/values/strings.xml` (English) *and* `res/values-nb/strings.xml`
  (Norwegian Bokmål), never as a literal in Kotlin: `stringResource(R.string.x)` in Compose,
  `context.getString(...)` elsewhere, `pluralStrings` for counts. Keep the Norwegian natural (not
  word-for-word) and as short as the English so it fits the cockpit cards. Names from data (places,
  roads, tracks) are not translated.
- **Companion is a background service** (Bluetooth link, notifications, media, voice replies), not a
  navigation app: keep its UI to setup/status. It stays a separately installed app, in this repo so
  both apps share `:protocol`. What it reads on the phone lives in `:phone`, which Curveon also uses
  for "This device" (Curveon on a phone being its own companion); changes there affect both.
- **Device checks.** Whenever the user should test or run something on the real X7 or Pixel
  (e.g. `adb logcat -s PhoneLink` after a call), add it to [device-checks.md](device-checks.md)
  (newest first, checkboxes) in the same change, as well as saying it in chat. Remove items once
  the user reports back.
- Bluetooth, real calls, tunnel dead reckoning and gyro lean can't be verified in the emulator;
  say so rather than claiming they work.
- Commit messages describe the change from the rider's point of view (see `git log`).
- **Changelog and release notes** (`app/src/main/assets/changelog.json`, the GitHub release text)
  are what riders read on their phones before updating. Write them easy to read and a little
  enthusiastic, in whole sentences that say what changed and why it is nice for the rider. Don't
  strip them down so far that the change is hard to understand, and keep them non-technical (no
  driver names, data sources or internals; "the map no longer crashes", not "OpenGL instead of
  Vulkan"). Norwegian written naturally, not translated word for word.
- Installing a new build on the X7 (over wifi, see above) and/or Pixel is always allowed whenever they are reachable
  (`adb devices`); do it after each change without asking.
- Committing and pushing are always allowed once a build has been installed on the devices (X7 or
  Pixel); no need to ask first.
- The app was renamed from VibeNav on 2026-10-06: anything still saying vibenav is stale.
