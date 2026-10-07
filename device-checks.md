# Device checks

Things to try or look at on the real devices (Hugerock X7 tablet, Pixel 9a) that couldn't be
tested in the emulator. Newest first; tick them off or delete them when done.

Commands assume the device is connected over USB. With both connected, pick one with
`-s <serial>` (see `adb devices`). `adb` lives in `~/Android/Sdk/platform-tools/`.

## 2026-10-07 — Language: English / Norsk

Tested in the emulator (switching both ways, Settings stays open). On the X7:

- [ ] Settings → Display → Språk/Language: pick Norsk; the whole app turns Norwegian at once.
- [ ] Ride with navigation in Norwegian: turn banner ("Sving til høyre · deretter …") fits, and
  numbers use decimal comma (12,5 km).
- [ ] Android Settings → Apps → Curveon → Language lists English and Norsk too.
- [ ] Reset if needed: `adb -s 241181d1 shell cmd locale set-app-locales com.curveon.tablet --locales en`

## 2026-10-07 — GPX groups: database upgrade on the X7

The GPX groups build was installed on the X7 by mistake while it was connected. It upgrades the
database the first time the app opens (your 20 TET tracks become the group "TET S"). This upgrade
was tested in the emulator on a copy of the X7's data. A backup of the X7 database taken before the
upgrade is in `~/develop/Curveon-data/db-backup-2026-10-07/x7/`.

- [ ] Open Curveon on the X7, then GPX: one group "TET S" (20 tracks) and your tracks shown as before.
- [ ] Rides: all 7 rides still there; "Save to GPX" on a ride puts it in the group you pick.
- [ ] Map: a track set to inverted (← in the GPX list) shows its arrows the other way.
- [ ] Waypoint details: install the newest build (`./gradlew :app:installDebug` with only the X7
      connected), then GPX → Import → `Download/S.gpx` again. Your group and track settings are kept;
      the waypoints now get address, phone, links, etc. Do the same for any other TET file you use.
- [ ] Map: zoomed out, Sweden's waypoints are coloured dots; zoomed in (about 30 km across the
      screen) they turn into icons. Tap one (e.g. a campsite) and check the details look right.

## 2026-10-07 — incoming call card disappears after a moment

On the dashboard the call card showed briefly, then the music card took its place. The cause wasn't
found yet. The tablet now logs which of two things happened.

- [ ] Ride/sit with the X7 connected to the phone, have someone call you, let it ring ~20 s.
- [ ] Then, with the X7 on USB:
  ```
  adb logcat -d -s PhoneLink
  ```
  - `Phone removed call …` → the phone itself removed the call notification (phone-side fix needed).
  - `Link dropped during a call` → the Bluetooth link to the tablet dropped while it rang.
  - Neither line → the card wasn't removed; describe what you saw instead.

## 2026-10-07 — choosing the music app (Spotify vs. podcast)

- [ ] With both Spotify and the podcast app opened on the phone, the music card on the tablet shows
      small dots at the top. Flip the card sideways (drag): it should pause the first app and play the other.
- [ ] Stop all music, then tap the empty music card: it should offer the app icons to pick from.
- [ ] "Phone not connected": turn sharing on in Curveon Companion, tap the music card on the tablet —
      it should connect within a few seconds instead of waiting for the next automatic try.
