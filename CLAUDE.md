# Curveon

Offline motorcycle dashboard for a tablet on the handlebars (made on a Hugerock X7: 7", 1920x1080
landscape, Android 13), plus a companion app on the rider's phone. Modules, features in detail, build and
release steps are in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md); the rider-facing overview in
[README.md](README.md).

The maintainer's own setup (paths on this PC, the rider's real devices and how to install on them) is kept
outside git and read from here when it exists: @~/.claude/curveon-personal.md

## Build and run

- Build with JDK 21 (newer JREs can't build).
- Emulators: `emulator -avd Hugerock_X7` (the tablet, API 33, 960x540 dp) or
  `-avd Curveon_Phone` (API 36; for `:companion`, and for Curveon at phone size). The emulator has no
  Bluetooth: use demo phone data / demo ride. Rotation: `adb shell settings put system user_rotation 0`
  (portrait) / `1` (landscape).
- **The Pixel 9a on the emulator.** Phone screens are checked on `Curveon_Phone` dressed as the Pixel
  9a (same as the `pixel9a` variant in `tools/ui_matrix.py`):
  `adb shell wm size 1080x2424; adb shell wm density 420`,
  `adb shell cmd overlay enable-exclusive --category com.android.internal.display.cutout.emulation.hole`,
  `adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural`
  (undo with `wm size reset; wm density reset` and `cmd overlay disable com.android.internal.display.cutout.emulation.hole`).
  Curveon_Phone has no maps or routing data; for a screen that needs them (route preview etc.) dress
  the X7 AVD the same way, but it runs older Android, so check dialogs on Curveon_Phone.
- **Layouts** must work on the tablet (X7) in landscape and on a phone (Pixel 9a, 411 dp wide) in both
  orientations: `isNarrowScreen()` (CockpitDialog.kt) stacks what sits side by side on the tablet.
- Tablet: `./gradlew :app:installDebug`, then
  `adb shell am start -n com.curveon.app/com.curveon.MainActivity`.
- Phone: `./gradlew :companion:installDebug` (package `com.curveon.companion`).
- Devices over wifi (pairing, `tcpip 5555`): [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md#devices-over-wifi).
  With several devices, pick one with `-s <serial>` / `ANDROID_SERIAL`.
- Release builds have no DEV aids (demo data, fake OBD) and no `run-as`, but do have the demo ride
  (Settings → Navigation). A release-signed install can't be replaced by a debug build without an uninstall.
- **Crash reports** (`CrashLog.kt`): one `.txt` per crash under
  `Android/data/com.curveon.app/files/crashes/` on the device, also on release builds:
  `adb -s <device> pull /sdcard/Android/data/com.curveon.app/files/crashes`. Native crashes and
  ANRs are copied from Android on the next start; a native one's `.txt` has the crashing thread's
  stack, and the whole tombstone (all threads, recent log) is in the `.pb` next to it. Look here
  first when the rider says the app crashed.
- **Map renderer: OpenGL ES** (`android-sdk-opengl`), not MapLibre's default Vulkan: Vulkan crashed
  in the Mali driver on a Pixel 9a (`vkCmdBeginRenderPass`), and the X7 has a Mali GPU too. Switching
  back is the artifact name in `gradle/libs.versions.toml`.
- Tests: `./gradlew test`. `:routing:test` routes over real Norway data.
- Large data (pmtiles, BRouter segments, search index, OSM extracts) lives outside the repo; on the
  tablet it goes under `Android/data/com.curveon.app/files/`.
- The group relay (and the web planner) runs on a VPS, its source outside this repo.
- **Read the build before committing.** Look at the whole output of `./gradlew` and `adb install`.
  `BUILD FAILED`, `INSTALL_FAILED…` or a non-zero exit means it is not done: find the cause and
  say what it was before committing or reporting success.

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
  the main thing to look for every time; check the X7 AVD too.
- **Nothing off screen on phones (recurring bug, ~14 times).** The Pixel 9a has a camera hole and
  gesture navigation; the plain X7 AVD has neither, so it hides this. Any new or changed screen,
  dialog or sheet is checked on the Pixel 9a emulator, in portrait *and* landscape, before it is
  called done: every button fully on screen, and for a dialog window `adb shell dumpsys window windows`
  shows its `Requested` size inside its `frame`. Every `Dialog` calls `HideSystemBars()`
  (CockpitDialog.kt), which lets the window cover the camera hole; without it Android puts the window
  below the hole at full height and cuts off its bottom.
- **The night run is parked (since 2026-11-05, the rider's decision).** The timer is off. Don't queue anything, and don't
  run `tools/ui_matrix_nightly.sh install`, which turns it on again, unless the rider asks for the night run back.
  It was meant to find and fix visual faults on all kinds of screens and made them instead (the Settings menu, above).
  `tools/ui_matrix.py` and its files stay in the repo.
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
- Bluetooth, real calls, tunnel dead reckoning and gyro lean can't be verified in the emulator;
  say so rather than claiming they work.
- **A redesign keeps what the rider could do.** Before removing or replacing a button, step, setting
  or mode, list what the old version could do and check that every item is still possible. If
  something is dropped on purpose, say so in the plan first and ask; never decide it silently. Change
  only what was asked: improving *how* something works (e.g. finding the fuel signal) is not
  permission to remove a neighbouring feature (e.g. the tank table).
- **Never change how something looks or is laid out unless the rider asked for just that.** This is absolute, for
  every session, and it covers fixes: a screen that does not fit gets the smallest change that keeps it
  the same screen (shorter text, a smaller margin, wrapping, scrolling), never another kind of menu, another arrangement
  or icons in place of words. If only a redesign would do, ask. "All on screen" is not "looks right": before calling a
  screen done, or merging anything that touches one, compare it with how it looked before and say what differs. Anything
  that differs and was not asked for is a fault, and it goes back. (2026-11-04: the night run turned the Settings menu
  on a phone from a list with names into a grid of icons, and a session with before and after in front of it merged
  it and called it right.)
- **Before a build goes to a device or out:** `python3 -I tools/check_dex_registers.py <apk>` must say ok. A method of
  the app over 255 registers made the compiler write code Android 17 refused to run (1.2.10 crashed at start on
  Android 17): split such a method, `DashboardScreen` first of all, which is near the limit.
- **A release only when the rider says release.** Before it goes out, show what looks or works differently for riders
  since the last release, with pictures of what looks different, and wait for a yes to that list.
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
  `tools/deploy-website.sh` (FTPS, login kept outside git; no `--apply` = dry run,
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
