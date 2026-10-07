# Device checks

Things to try or look at on the real devices (Hugerock X7 tablet, Pixel 9a) that couldn't be
tested in the emulator. Newest first; tick them off or delete them when done.

Commands assume the device is connected over USB. With both connected, pick one with
`-s <serial>` (see `adb devices`). `adb` lives in `~/Android/Sdk/platform-tools/`.

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
      small dots at the top. Swipe the card sideways: it should pause the first app and play the other.
- [ ] Stop all music, then tap the empty music card: it should offer the app icons to pick from.
- [ ] "Phone not connected": turn sharing on in Curveon Companion, tap the music card on the tablet —
      it should connect within a few seconds instead of waiting for the next automatic try.
