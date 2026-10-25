# Curveon

Offline motorcycle dashboard for a Hugerock X7 tablet (7", 1920x1080 landscape, Android 13) on the
handlebars, plus a companion app on the rider's Pixel 9a. Modules, features in detail, build and
release steps are in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md); the rider-facing overview in
[README.md](README.md).

## Build and run

- JDK 21 lives in `~/.local/share/jdks/jdk-21.0.12.1+1` (set via `org.gradle.java.home` in
  `~/.gradle/gradle.properties`); the system JRE 25 can't build. SDK: `~/Android/Sdk`.
- Emulators: `~/Android/Sdk/emulator/emulator -avd Hugerock_X7` (the X7, API 33, 960x540 dp) or
  `-avd Curveon_Phone` (API 36; for `:companion`, and for Curveon at phone size). The emulator has no
  Bluetooth: use demo phone data / demo ride. Rotation: `adb shell settings put system user_rotation 0`
  (portrait) / `1` (landscape).
- **The Pixel 9a on the emulator.** Phone screens are checked on `Curveon_Phone` dressed as the Pixel
  9a (same as the `pixel9a` variant in `tools/ui_matrix.py`), not on the rider's real phone:
  `adb shell wm size 1080x2424; adb shell wm density 420`,
  `adb shell cmd overlay enable-exclusive --category com.android.internal.display.cutout.emulation.hole`,
  `adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural`
  (undo with `wm size reset; wm density reset` and `cmd overlay disable com.android.internal.display.cutout.emulation.hole`).
  Curveon_Phone has no maps or routing data; for a screen that needs them (route preview etc.) dress
  the X7 AVD the same way, but it runs older Android, so check dialogs on Curveon_Phone.
- **Layouts** must work on the X7 in landscape and on a phone (Pixel 9a, 411 dp wide) in both
  orientations: `isNarrowScreen()` (CockpitDialog.kt) stacks what sits side by side on the tablet.
- Tablet: `./gradlew :app:installDebug`, then
  `adb shell am start -n com.curveon.app/com.curveon.MainActivity`.
- Phone: `./gradlew :companion:installDebug` (package `com.curveon.companion`).
- The X7 and the Pixel may be on USB or on wifi; how to find and connect them over wifi (pairing,
  `tcpip 5555`, last known addresses) is in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md#devices-over-wifi).
  With several devices, pick one with `-s <serial>` / `ANDROID_SERIAL`.
- **Curveon on the Pixel is release-signed** (since 2026-10-15), so it can also update itself from
  GitHub releases: install it with `./gradlew :app:assembleRelease` (signing env from
  `~/.curveon-signing/signing.env`) and `adb install -r app/build/outputs/apk/release/app-release.apk`,
  never `installDebug` (Android refuses a debug build over it). Release builds have no DEV aids
  (demo data, fake OBD) and no `run-as`, but do have the demo ride (Settings → Navigation).
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
- The group relay (and the web planner) runs on a VPS: `ssh curveon-vps`; what runs there, secrets
  and how to rebuild it are in `~/.curveon-vps/README.md` (never in git).

## Devices: installing and branches

- **A device never loses what it runs.** Make branches and worktrees whenever that is the safest way
  to work (a session's own worktree, the nightly run's `nightly/*`); ask which branch is current if
  it looks wrong. Before building for a device from a branch, merge the newest local `main` into it
  (`git log --oneline HEAD..main`; local `main` can be ahead of `origin/main`). Before every install,
  check `git log --oneline HEAD..<branch>` against `main` and every other active branch
  (`worktree-*`, `web-planner`, `origin/*`): if any has commits the device already runs, stop and
  ask. (2026-10-21 a build from a fresh branch off `main` wiped 34 commits of group rides, friends,
  mic search etc. off the Pixel; 2026-10-20 a branch on an old `main` brought fixed bugs back.)
- **Installing:** a device on USB (`adb devices` shows a `usb:` transport) and the Pixel can always be
  installed on, after each change, without asking. The X7 when it is reached only over wifi (on the
  handlebars) is not: say what is waiting to be installed and end the message with the question
  whether to install; once the rider says yes, do it for that batch.
- **The X7 runs the release build by default** (`assembleRelease`, signing env as for the Pixel), because
  the rider wants to feel how fast it really responds. When a debug build is better (logging, DEV aids
  like demo data, `run-as`, a crash to chase), put it on and say why; switch back to release when done.
  **Switching between debug and release needs an uninstall first (different signing keys), which
  deletes the app's data and the maps under `Android/data/com.curveon.app/files/`:** say so and get a
  yes before each switch, and copy the maps back from `~/develop/Curveon-data` afterwards.
- **Don't drive the rider's real devices.** Installing is fine, but don't tap around, change the app's
  settings or start rides or groups on the real X7 or Pixel: try things on the emulator.
- **Read the build before committing.** Look at the whole output of `./gradlew` and `adb install`.
  `BUILD FAILED`, `INSTALL_FAILED…` or a non-zero exit means it is not done: find the cause and
  say what it was before committing or reporting success. A failure that is expected (e.g.
  `installDebug` over the release-signed Pixel) is handled (`ANDROID_SERIAL=<device>`), not ignored.
- Committing and pushing are always allowed once a build has been installed on the devices (X7 or
  Pixel); no need to ask first.

## How to work here

- **Offline first.** Everything core works without a connection, and online too. Maps and other large
  resources (routing, search) can be downloaded per country; downloading a country gets everything
  for it. Small resources may come with the online app when the rider is in the country they cover.
- **Minimal UI.** Prefer few controls that do the right thing in context over new buttons: reuse an
  existing surface or gesture (tap a card, long-press the map, empty search state) and show
  info only when it matters. Rail screens open as a `CockpitDialog`.
- **Add, don't repurpose.** "Put it in X" means a new option inside X. Never change what an existing
  tap or gesture already does (2026-10-22: a tap on the arrival bar was made to open the route menu
  instead of folding it out). If repurposing seems better, propose it and wait for a yes.
- **Self-explanatory, and say what is missing.** A feature must be understood without explanation. If
  it needs something that isn't in place (another app, a permission, Bluetooth, a connection, a
  download), tell the rider in plain words what is missing and what to do; never fail silently. Hide
  options that can never apply; explain the ones shown that can't work right now.
- **Choose from what the rider sees.** Pick a data source or approach by what the rider sees and
  where it must work (every country, offline and online, phone and tablet), not by which code is
  nearest. Name the choice and the obvious alternative in the plan. "Only in Norway", "only online",
  "only on the tablet" is a problem to raise, never a footnote (2026-10-23: the free-ride marker
  snapped to NVDB lines, which exist only in Norway and didn't match the drawn map).
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
  Pixel 9a emulator (above) in both portrait and landscape, while navigating and not navigating
  (demo ride), and with the music player on and off (demo phone data). Overlap with other elements is
  the main thing to look for every time; check the X7 AVD too. Other kinds of screens: the nightly
  matrix (below).
- **Nothing off screen on phones (recurring bug, ~14 times).** The Pixel 9a has a camera hole and
  gesture navigation; the plain X7 AVD has neither, so it hides this. Any new or changed screen,
  dialog or sheet is checked on the Pixel 9a emulator, in portrait *and* landscape, before it is
  called done: every button fully on screen, and for a dialog window `adb shell dumpsys window windows`
  shows its `Requested` size inside its `frame`. Every `Dialog` calls `HideSystemBars()`
  (CockpitDialog.kt), which lets the window cover the camera hole; without it Android puts the window
  below the hole at full height and cuts off its bottom.
- **Screen matrix at night, never during the day.** `tools/ui_matrix.py` dresses the `Curveon_Phone`
  emulator up as eight common screens (360-411 dp phones, 720p budget phone, 21:9, large text,
  foldable inner screen, 10" tablet, X7) in portrait and landscape and checks that the named buttons
  are wholly on screen. A session never runs it: when a change could plausibly look wrong on some
  kind of screen (layout, dialogs, rows of buttons, text that may not fit, anything sized to the
  screen), queue it in the same change with
  `python3 -I tools/ui_matrix_queue.py add --scenarios <names> "<what changed, what to look for>"`
  (`--new "<how to get there; what must fit>"` for a screen with no scenario yet), and say in chat that
  it is queued. The night run (03:30; how it works: the top of `tools/ui_matrix_nightly.sh`) fixes
  what it finds on `nightly/<branch>` and publishes a report with pictures on
  https://claude.ai/artifact/5985b69pxJdmJ1yK1opdfb. A session starts with last night's summary
  (SessionStart hook): pass it on, and merge `nightly/<branch>` into the branch only after the rider
  says yes. After changing any `tools/ui_matrix*` file run `tools/ui_matrix_nightly.sh install`.
- **Never call Curveon "free" / "gratis"** (website, README, store text, changelog, app texts): the
  rider plans some income from it on Google Play. "Open source", "no account", "no ads" are fine;
  saying that a map source or a service the app uses costs nothing is not about Curveon's price.
- **Every text in every language.** The rider picks one of eleven languages (`AppLanguage.kt`:
  English, Norwegian, Swedish, Danish, Finnish, Icelandic, German, Spanish, French, Dutch, Polish) in
  Settings → Display or on the first start. Any text the rider can see goes in
  `res/values/strings.xml` (English) *and* every `res/values-<lang>/strings.xml`, in the app and in
  `:companion`, never as a literal in Kotlin: `stringResource(R.string.x)` in Compose,
  `context.getString(...)` elsewhere, `pluralStrings` for counts. `TranslationsTest` fails when a
  language lacks a text or its placeholders differ. Write each language naturally (not word for
  word) and as short as the English so it fits the cockpit cards. Names from data (places, roads,
  tracks) are not translated.
- **Companion is a background service** (Bluetooth link, notifications, media, voice replies), not a
  navigation app: keep its UI to setup/status. It stays a separately installed app, in this repo so
  both apps share `:protocol`. What it reads on the phone lives in `:phone`, which Curveon also uses
  for "This device" (Curveon on a phone being its own companion); changes there affect both.
- **Device checks.** Whenever the rider should test or run something on the real X7 or Pixel
  (e.g. `adb logcat -s PhoneLink` after a call), add it to [device-checks.md](device-checks.md)
  (newest first, checkboxes) in the same change, as well as saying it in chat. Remove items once
  the rider reports back. The rider ticks them on the phone on a private claude.ai page
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
- Commit messages describe the change from the rider's point of view (see `git log`).
- **Changelog and release notes** (`app/src/main/assets/changelog.json`, the GitHub release text)
  are what riders read on their phones before updating. Write them easy to read and a little
  enthusiastic, in whole sentences that say what changed and why it is nice for the rider. Don't
  strip them down so far that the change is hard to understand, and keep them non-technical (no
  driver names, data sources or internals; "the map no longer crashes", not "OpenGL instead of
  Vulkan"). Every entry has all eleven languages, each written naturally, not word for word.
  **A released version's entry is frozen.** Before adding a changelog point, find the newest
  released version (`gh release list --repo atlehavik/curveon -L 1`; local tags may be missing). If
  the entry for `versionName` is that version, it has been released: never add to it. Start the
  next patch version instead (new entry at the top of `changelog.json`, and `versionCode` +
  `versionName` raised in `app/build.gradle.kts`) and put the point there; later changes go in the
  same entry until it is released. Never edit an already released entry, not even a typo, unless the
  rider asks.

## Website and web planner

- **Website (curveon.no):** the source is `website/`; it is deployed by hand-free script
  `tools/deploy-website.sh` (FTPS, login in `~/.curveon-signing/website.env`; no `--apply` = dry run,
  show the list and get a yes before `--apply`). The download button links to
  `https://github.com/atlehavik/curveon/releases/latest`, which follows every GitHub release by
  itself, so a new release needs no website change. After a release, check that
  `gh release view --repo atlehavik/curveon` still lists both `Curveon-<v>.apk` and
  `Curveon-Companion-<v>.apk`, and that the button on curveon.no still reaches them.
- **Web planner (relay.curveon.no/plan):** changes to it may be deployed right away with
  `tools/deploy-webplanner.sh --apply` once its browser test (`tools/webplanner_test.mjs`) passes, without asking
  first; say so in chat and send the rider a push notification each time, so the rider notices it went live.
  Run the test against a local relay as often as needed, but against the live planner at most once
  per deploy: each run opens ~3 relay channels from this PC's IP, the relay allows 10 per IP per hour,
  and the rider's own QR pairing comes from the same IP.
