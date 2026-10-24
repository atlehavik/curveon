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
  `adb shell am start -n com.curveon.app/com.curveon.MainActivity`.
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
  `Android/data/com.curveon.app/files/crashes/` on the device, also on release builds:
  `adb -s <device> pull /sdcard/Android/data/com.curveon.app/files/crashes`. Native crashes and
  ANRs are copied from Android on the next start; a native one's `.txt` has the crashing thread's
  stack, and the whole tombstone (all threads, recent log) is in the `.pb` next to it. Look here
  first when the rider says the app crashed.
- **Map renderer: OpenGL ES** (`android-sdk-opengl`), not MapLibre's default Vulkan: Vulkan crashed
  in the Mali driver on the Pixel 9a (five times on 2026-10-16, `vkCmdBeginRenderPass`), and both
  devices have Mali GPUs. Switching back is the artifact name in `gradle/libs.versions.toml`.
- Tests: `./gradlew test`. `:routing:test` routes over real Norway data.
- Large data (pmtiles, BRouter segments, search index, OSM extracts) lives outside the repo in
  `~/develop/Curveon-data`; on the tablet it goes under `Android/data/com.curveon.app/files/`.

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
  note it in [google-play.md](docs/google-play.md).
- **Place names are always on top.** A new or changed map layer (lines, fills, heat, dashes, route,
  tracks) must never cover place names: they stay above every line layer (see `raisePlaceNames` in
  CurveonMapView.kt, and the order in `OfflineMaps.styleJson`). Check it with the layer switched on,
  not only off, at a zoom where names show.
- **New visual elements: test for overlap.** Before a new visual element goes in, look at it on the
  Pixel in both portrait and landscape, while navigating and not navigating, and with the music player
  on and off. Overlap with other elements is the main thing to look for every time; check the X7
  layout too (the X7 itself when it is on USB). Other kinds of screens: the nightly matrix (below).
- **Nothing off screen on the Pixel (recurring bug, ~14 times).** The Pixel 9a has a camera hole and
  runs API 37; the X7 AVD has neither, so it hides this. Any new or changed screen, dialog or sheet is
  checked on the Pixel, in portrait *and* landscape, before it is called done: every button fully on
  screen, and for a dialog window `adb shell dumpsys window windows` shows its `Requested` size inside
  its `frame`. Every `Dialog` calls `HideSystemBars()` (CockpitDialog.kt), which lets the window cover
  the camera hole; without it Android puts the window below the hole at full height and cuts off its
  bottom. (On `Curveon_Phone` the hole is
  `adb shell cmd overlay enable-exclusive --category com.android.internal.display.cutout.emulation.hole`.)
- **Screen matrix at night, never during the day.** `tools/ui_matrix.py` dresses the `Curveon_Phone`
  emulator up as eight common screens (360-411 dp phones, 720p budget phone, 21:9, large text,
  foldable inner screen, 10" tablet, X7; camera holes, notches, 3-button and gesture navigation) in
  portrait and landscape and checks that the named buttons are wholly on screen. A run takes long, so a
  session never runs it: when a change could plausibly look wrong on some kind of screen (layout,
  dialogs, rows of buttons, text that may not fit, anything sized to the screen), queue it in the same
  change with `python3 -I tools/ui_matrix_queue.py add --scenarios <names> "<what changed, what to look for>"`
  (`--new "<how to get there; what must fit>"` for a screen with no scenario yet), and say in chat that
  it is queued. At 03:30 `tools/ui_matrix_nightly.sh` (systemd user timer) runs the queue per branch on
  the emulator in the worktree `.claude/worktrees/nightly-ui`, fixes what it finds on `nightly/<branch>`
  (never the rider's branch, never the X7 or Pixel), and publishes the report with pictures on the
  private page https://claude.ai/artifact/5985b69pxJdmJ1yK1opdfb. A session starts with last night's
  summary (SessionStart hook): pass it on, and merge `nightly/<branch>` into the branch only after the
  rider says yes. Queue, results and logs are in `~/.curveon-nightly`. After changing any
  `tools/ui_matrix*` file run `tools/ui_matrix_nightly.sh install`; `~/.curveon-nightly/bin/ui_matrix_nightly.sh check`
  tests the setup.
- **Never call Curveon "free" / "gratis"** (website, README, store text, changelog, app texts): the
  rider plans some income from it on Google Play. "Open source", "no account", "no ads" are fine;
  saying that a map source or a service the app uses costs nothing is not about Curveon's price.
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
  the user reports back. The rider ticks them on the phone on a private claude.ai page
  (https://claude.ai/artifact/9iV3p3tvJyTcW2pUdbrTMW); `tools/device_checks_sync.py` syncs it both
  ways (how: its docstring, and the memory note on the page).
- Bluetooth, real calls, tunnel dead reckoning and gyro lean can't be verified in the emulator;
  say so rather than claiming they work.
- **A redesign keeps what the rider could do.** Before removing or replacing a button, step, setting
  or mode, list what the old version could do and check that every item is still possible. If
  something is dropped on purpose, say so in the plan first and ask; never decide it silently. Change
  only what was asked: improving *how* something works (e.g. finding the fuel signal) is not
  permission to remove a neighbouring feature (e.g. the tank table).
- **Plan before a bigger change.** For a change that touches several files, write 3–4 lines first:
  what changes, what stays, and what (if anything) is removed. Then implement.
- **Read the build before committing.** Look at the whole output of `./gradlew` and `adb install`.
  `BUILD FAILED`, `INSTALL_FAILED…` or a non-zero exit means it is not done: find the cause and
  say what it was before committing or reporting success. A failure that is expected (e.g.
  `installDebug` over the release-signed Pixel) is handled (`ANDROID_SERIAL=<device>`), not ignored.
- Commit messages describe the change from the rider's point of view (see `git log`).
- **Changelog and release notes** (`app/src/main/assets/changelog.json`, the GitHub release text)
  are what riders read on their phones before updating. Write them easy to read and a little
  enthusiastic, in whole sentences that say what changed and why it is nice for the rider. Don't
  strip them down so far that the change is hard to understand, and keep them non-technical (no
  driver names, data sources or internals; "the map no longer crashes", not "OpenGL instead of
  Vulkan"). Norwegian written naturally, not translated word for word.
  **A released version's entry is frozen.** Before adding a changelog point, find the newest
  released version (`gh release list --repo atlehavik/curveon -L 1`; local tags may be missing). If
  the entry for `versionName` is that version, it has been released: never add to it. Start the
  next patch version instead (new entry at the top of `changelog.json`, and `versionCode` +
  `versionName` raised in `app/build.gradle.kts`) and put the point there; later changes go in the
  same entry until it is released. Never edit an already released entry, not even a typo, unless the
  rider asks.
- **NEVER build a device from the wrong branch.** Worktrees and branches are fine where normal git
  work calls for them (a session's own worktree, the nightly run's `nightly/*`); ask which branch is
  current if it looks wrong. What must never happen is a device losing what it runs: before every
  install, check `git log --oneline HEAD..<branch>` against `main` and every `worktree-*` / `origin/*`
  branch: if any has commits the device already runs, stop and ask. (2026-10-21 a build from a fresh
  branch off `main` wiped 34 commits of group rides, friends, mic search etc. off the Pixel.)
- **Website (curveon.no):** the source is `website/`; it is deployed by hand-free script
  `tools/deploy-website.sh` (FTPS, login in `~/.curveon-signing/website.env`; no `--apply` = dry run,
  show the list and get a yes before `--apply`). The download button links to
  `https://github.com/atlehavik/curveon/releases/latest`, which follows every GitHub release by
  itself, so a new release needs no website change. After a release, check that
  `gh release view --repo atlehavik/curveon` still lists both `Curveon-<v>.apk` and
  `Curveon-Companion-<v>.apk`, and that the button on curveon.no still reaches them.
- **Installing:** a device connected by USB (`adb devices` lists it with a `usb:` transport, e.g. the X7 at
  the desk) and the Pixel (over wifi) can always be installed on, after each change, without asking. The X7 when
  it is reached only over wifi (on the handlebars) is not: say what is waiting to be installed and end the
  message with the question whether to install; once the rider says yes, do it for that batch.
- **The X7 runs the release build by default** (`assembleRelease`, signing env as for the Pixel), because
  the rider wants to feel how fast it really responds. When a debug build is better (logging, DEV aids
  like demo data, `run-as`, a crash to chase), put it on and say why; switch back to release when done.
  While the X7 is on USB it can always be installed on, in either build, without asking. **Switching
  between debug and release needs an uninstall first (different signing keys), which deletes the app's
  data and the maps under `Android/data/com.curveon.app/files/`:** say so and get a yes before each
  switch, and copy the maps back from `~/develop/Curveon-data` afterwards.
- Committing and pushing are always allowed once a build has been installed on the devices (X7 or
  Pixel); no need to ask first.
- The app was renamed from VibeNav on 2026-10-06: anything still saying vibenav is stale.
