# Device checks

Things to try or look at on the real devices (Hugerock X7 tablet, Pixel 9a) that couldn't be
tested in the emulator. Newest first; tick them off or delete them when done.

Commands assume the device is connected over USB. With both connected, pick one with
`-s <serial>` (see `adb devices`). `adb` lives in `~/Android/Sdk/platform-tools/`.

## 2026-10-10 — GO drops its word when squeezed

The route preview's GO button shows arrow and "GO"/"KJØR" when it has room, and just a bigger arrow
when it's squeezed (it spilled out of the button on the Pixel in landscape). Installed on both devices.

- [ ] Pixel landscape (dashboard): plan a trip; the button shows just the arrow, inside its pill.
- [ ] X7 and Pixel upright: the button still says KJØR/GO.

## 2026-10-10 — Routing online without downloading the country

Planning a trip somewhere with no routing data (e.g. Curveon on the Pixel, no countries downloaded)
now fetches just the routing tiles the trip needs from brouter.de when online (5x5 degrees, about
5–65 MB each), shown as "Fetching routing data (1 of 2)…" in the preview. They stay for next time,
so that area then works offline too. No map or search is downloaded. Tried in the phone emulator
(Oslo → Hamar: two tiles, ~93 MB, then the three routes in ~8 s).

- [ ] Pixel, online, no countries: plan a trip; it fetches routing data, then shows the routes and GO works.

## 2026-10-10 — Lock the screen landscape or portrait

Settings → Display → Screen: Turns / Landscape / Portrait. Locked, the screen stays exactly the way
it was held when chosen (not even flipping upside down), whatever Android's auto-rotate says.
Tried in the emulator.

- [ ] X7: Settings → Display → Screen → Landscape while it sits on the mount; then ride over bumps
      (or tilt it upright by hand): the screen doesn't turn.

## 2026-10-09 — Portrait: speed on top, dialogs that fit

Upright, the speed card now sits above the map. The dialogs stack on a phone instead of squeezing:
Settings, GPX and the map download get their page tabs along the top (only the open one labelled),
the phone's tabs get a row of their own, Roundtrip puts the compass above the options, and the
route preview gives GO a full-width row. Checked at Pixel 9a size in the emulator (search, route
preview, place menu, save, navigation, every rail dialog); the X7 in landscape looks as before.

- [ ] Pixel upright: go through Settings, GPX, Roundtrip, Phone and a route preview; anything cut off?

## 2026-10-09 — Portrait

Curveon now turns with the device (Android's auto-rotate): held upright, the map is on top, speed and
music/messages below it, and the rail is a row along the bottom; while planning a trip the map
gets the cards' room too. Landscape is as before. Tried in the emulator (dashboard, full map,
search, route preview).

- [ ] Pixel upright: the 411 dp wide screen still fits the route preview's three options and GO.

## 2026-10-09 — Curveon on a phone: camera hole, rail, hints

On the Pixel (held sideways) the rail moves clear of the camera hole, the rail buttons are a bit
smaller so the map toggle fits, the "no offline map" note only shows when there is no map at all
(no connection either), and the music card says how to use the phone itself. Checked on the Pixel 9a with the
hole on the left; the X7 looks as before.

- [ ] Pixel: turn it the other way round (hole on the right): the cards on the right move clear of it.
- [ ] Curveon on a phone without downloaded countries, wifi and mobile data off: the map shows
      "No map here without a connection…". With a connection there is no note at all.

## 2026-10-09 — "This device" as its own phone

Settings → Phone has a new choice, **This device**: Curveon reads messages, calls and music from the
apps on the same device, with no companion and no Bluetooth (for riders who have only a phone).
The companion app still works as before and is still installed on its own. Its code moved into a
shared `:phone` module. Tried in the emulator (music apps found, a test message
showed on the dashboard).

- [ ] Pixel (companion reinstalled 2026-10-09, music already reaches the X7): a message, a call and
      a spoken reply still come through. After this update the companion's notification access had
      to be turned on again (the listener moved to `com.curveon.phone`; done with
      `adb shell cmd notification allow_listener com.curveon.companion/com.curveon.phone.CurveonNotificationListener`).
- [ ] X7: Settings → Phone → *Use* on the Pixel again after trying "This device" (switching back works).
- [ ] Optional, with Curveon on the Pixel: *This device* → allow access; a WhatsApp message shows on
      Curveon's message card, and the music card controls Spotify.

## 2026-10-09 — Choose the start before the destination

"Where to?" now has a "From my position" row at the top. Tap it to pick the start first (search, a
recent place or the map), then search for the destination. The route is then worked out from
that start straight away, not from where you are first. Tried in the emulator (from Lillehammer to Hamar).

- [ ] Plan a trip that starts far away (another part of the country): the preview comes up quickly
      and reads "From …".
- [ ] Choosing the start with "Pick on map" brings you back to "Where to?" with that start.

## 2026-10-09 — Offroad as a fourth surface choice

The surface chip now cycles Tarmac → Mixed → Gravel → Offroad. Offroad goes looking for tracks
(rough grades too) and dirt, grass and earth, before gravel; tarmac only where there's nothing else.
It still keeps to roads and tracks a motorcycle is allowed on (no paths or trails). The route line
is purple. Hamar–Lillehammer in the tests: Gravel 19 km unpaved and no tracks, Offroad 33 km and
9 km of tracks.

- [ ] Plan a trip with Offroad near home: the tracks it picks are ones the X7 bike can actually
      ride (if grade 4–5 tracks turn out too rough, say so and they'll cost more).
- [ ] The purple route line is easy to see on both the day and the night map.

## 2026-10-09 — Warning when a tarmac route forces gravel

With Tarmac chosen, a route that still has gravel (100 m or more) shows a framed warning above GO:
how much gravel and where the first of it starts. The folded bar shows "⚠ 400 m gravel". The router
only takes gravel on Tarmac when there's no tarmac road short of a huge detour. Tried on the X7
(Stautland on Bømlo: 400 m gravel at the end; with Mixed the warning goes away).

- [ ] Plan a trip to a cabin or farm on a gravel road: the warning is clear enough before pressing GO.
- [ ] A normal tarmac trip shows no warning (no false alarms from short untagged bits).

## 2026-10-09 — No home/recents buttons while typing

Dialogs no longer bring back the back/home/recents bar (it also squeezed the map). The bar that
comes with the keyboard can't be hidden by an app, so the X7 was switched to gesture navigation
(`adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural`):
with the keyboard up there is only a ▼ (hide keyboard) bottom left and a thin home line that needs a
swipe up, not a tap. Undo: Settings → System → Gestures → System navigation → 3-button navigation.
Tried on the X7 (Where to?, Settings).

- [ ] Type an address on the bike: ▼ hides the keyboard, and you no longer land on the home screen by accident.
- [ ] Elsewhere (Google Maps etc.) back is now a swipe in from the left or right edge: OK with gloves?
- [ ] A delete confirmation (long-press a saved route in Where to?) also shows no button bar.

## 2026-10-09 — Gravel and soft roads on the map

From zoom 11 in, roads the routing data knows as unpaved are dashed over the map: grey = gravel or
compacted, brown = softer, rough or unknown ground. Read from the routing segments, so it works
wherever routing data is installed. Tried in the emulator (forest roads east of Elverum).

- [ ] On the X7, zoom to a forest-road area (or ride somewhere with gravel): the dashes appear
  within about a second after the map stops moving, and panning stays smooth.
- [ ] Spot-check a road you know is gravel and one you know is tarmac: right colour?
- [ ] Dark map: the grey and brown still show.

## 2026-10-09 — Online map and online search

Outside the downloaded countries the map now comes from OpenFreeMap (free, no key) when the tablet is
online. Search shows offline results first, then mixes in Photon and (in Norway) Kartverket's place
names/addresses. Tried in the emulator (Arvika in Sweden; "Lyklingv" from Bømlo).

- [ ] On the phone hotspot, pan the X7's map into Sweden: roads and towns appear in the same style.
  Pan back into Norway: `adb logcat -s CurveonMap` should say "Online map off".
- [ ] Turn the hotspot off and pan to where you looked before: the map should still be there (cache).
- [ ] With the hotspot on, search for a farm or bay name near home: an "online" result appears a
  moment after the offline ones. With it off, search behaves as before.
- [ ] Riding with the online map shown: does the X7 keep up (smooth scrolling, no lag)?

## 2026-10-08 — Tracks with Tarmac

Before, Tarmac forbade every `highway=track` (and grade4/5 were forbidden even with Gravel), so a
destination on a farm/forest road stopped on the nearest tarmac. Now tracks are a last resort.

- [ ] With Tarmac: Where to? → long-press the map on a gravel/forest track → Go here: the route should
  end on the track. A trip between towns should still use no tracks.
- [ ] Follow a GPX track that runs over gravel roads with Tarmac selected: it should stay on the GPX line.

## 2026-10-08 — Route start at home

In OSM your road (way 520497619) is joined to the roads only through the cycle path (the map is
wrong). Cycle paths/footpaths are now used only at the very start/end of a route when there is no
road way out (as little as possible, no length limit), never in the middle. From your house that is
18 m of path stub onto Stavlandsvegen. Tried in the emulator with the GPS at your house.

- [ ] At home on the X7: plan a trip → the line starts at the house, "Bear left · Fv4998 Stavlandsvegen"
  after ~70 m. Ride it: no "Recalculating" on the way down to the road.
- [ ] Ride home with a trip to the house: it should end at the house.
- Still worth fixing in OSM (join the service road to Stavlandsvegen), so other apps get it right too.

## 2026-10-08 — Editing a trip on the map

Tried in the emulator: long-press on the route line → edit mode, dragging the line (dashed guess,
then the new route), dragging the new point again, Undo, tapping a point → menu, Add stop before,
saving a changed saved route under its name (updated, not copied).

- [ ] On the X7 with gloves: is it easy to grab the line (22 dp either side) and the stop handles
  (30 dp) without panning the map by accident? Too fiddly → say so and they can be made bigger.
- [ ] Ride a saved A→B route without changing it: it should still follow its saved line (and rejoin it
  after a wrong turn) like before.

## 2026-10-08 — Pick on map

Tried in the emulator: destination, + Stop and the start chip each open the map from "Pick on
map", the dialog shrinks into the bar, tap drops a pin, confirm works and the dashboard comes back.

- [ ] On the X7 with gloves: is a tap on the map reliable enough to drop the pin (not taken as a
  small pan)? And is the shrink animation smooth on the X7, or does it stutter?

## 2026-10-08 — Phone status, music app favorites

Tried on the X7 + Pixel: Settings → Phone shows Pixel 9a, battery, "connected since" and the
detected music apps; starring Spotify (not running) and swiping the music card to it started it
on the phone (Spotify refuses Curveon's media browser, the play key fallback worked after ~8 s).

- [ ] Spotify should now start in ~4 s instead of ~8 s (a refused browser goes straight to the
  play key): close Spotify on the phone, swipe to it on the X7.
- YouTube Music can't be started from the tablet when it isn't running (it only lets Android
  Auto/the system start it; tried play keys and "play from search"). Once it has played on the
  phone it can be switched to like the others.
- [ ] Star NRK Radio / Podme too, close them on the phone (swipe away from
  recents), then swipe to each on the X7. Which start, and how? `adb -s 58301JEBF17776 logcat -s CurveonMedia`
  → "browser connected … play" / "started by play key" / "didn't start" (the tablet then says so).
- [ ] Forget in Settings → Phone: the phone moves to "Paired phones"; tapping it there links it again.

## 2026-10-08 — Stops, planner, saving, wrong turns

Tried in the emulator with fake GPS (Voss → Bergen, then riding off towards Granvin): the
question card, Turn back (banner says "Make a U-turn" until you have turned), New route from here
with Keep/Skip stops, Rejoin further ahead on a saved route, the automatic choice after 20 s,
quiet rejoins in Voss centre, + Stop / route menu / save name while riding. The decisions were also
simulated over real roads on the desktop (`WrongTurnSimulationTest`). Still to try on the bike:

- [ ] Ride a planned trip and take a wrong turn on purpose where there is a quick way round
  (a block, a parallel road): it should re-route quietly, no card. Then one onto a road that
  leads away: the card should come, and Turn back / New route from here should do what they say.
- [ ] Ignore the card and keep riding: after ~20 s it should pick "New route from here" itself.
  Stop at the roadside instead: it should wait for you.
- [ ] After "New route from here", leave the route again within 5 minutes: no new card, just a
  new route (follow mode, e.g. riding behind someone).
- [ ] Is 40 m / 3 min / 10 % (Settings → Navigation) a good default on real roads? Too many or
  too few cards? Each decision is logged:
  `adb -s <x7> logcat -s CurveonNav` → "Off route at … m, heading …: rejoin/ask/replan".
- [ ] Re-routing takes a few seconds on the X7 (several router calls). If "Recalculating…" stays
  up much longer than ~5 s on long trips, tell me.
- [ ] Planner at home: long-press the map → Start here, add stops, save; later in Where to?
  the saved route should follow exactly the same line.

## 2026-10-08 — Offline search in every country

Tried in the emulator: Norway's search built from the map (~2 min, "basic search"), then replaced by
the full one from the cloud ("fuller search available" → Get missing). On the X7 the old Norway
index carried over as the full one. Still to try on the X7:

- [ ] Download a new country (e.g. Sweden, once https://github.com/atlehavik/curveon-data/releases/tag/search
  has `sweden.sqlite.gz`): search finds a town, a street with house number and a fuel station there.
- [ ] Search while in Norway still finds Norwegian addresses first; with two countries installed,
  results from both show up.
- [ ] Optional: in airplane mode, build search from the map for a small country (Get missing with
  no connection falls back to it) and see how long it takes on the X7.

## 2026-10-08 — Download maps by country

Tried in the emulator (Faroe Islands: download, list, delete) and on the X7 (Faroe Islands next to
Norway on the map, then deleted again). Still to try on the X7:

- [ ] Settings → Maps & data → Download countries → Sweden (or another country you'll ride):
  progress, then the map shows across the border and routes work there.
- [ ] Pick two or three countries in a row: they queue ("Waiting") and download one after another;
  Cancel on a waiting one takes it out.
- [ ] Border areas (e.g. Svinesund): roads look normal where the two maps overlap (no doubled labels).
- [ ] Free space: DMD2 keeps its own 29 GB; Curveon can't read it (Android blocks other apps' folders).

## 2026-10-08 — Bike stays on the road while navigating

Only unit-tested (the tracker); needs a real ride with a route on the X7:

- [ ] On the route, the bike sits on the route line and points along the road, also where GPS
  drifts (trees, steep valleys) instead of floating beside it.
- [ ] Take a different road on purpose: the bike stays on the line for ~3 s, then jumps to your
  real position as the reroute starts. Shouldn't feel too sticky or too eager.
- [ ] Tight hairpins: the arrow turns with the road without lagging badly.

## 2026-10-08 — Rides: select/delete, auto-save to GPX; map compass, 3D, "where am I" glow

Selecting, deleting, auto-save switch, 3D, recenter and edge glow tested in the emulator (no map
tiles there, so 3D was only seen on a blank map). On the X7:

- [ ] After a real ride with "Auto-save new rides to GPX" on: the ride shows up as a track in the
  chosen GPX group once it ends (20 min standing still, or the app closing does *not* count).
- [ ] 3D while riding: road ahead readable, rider not too low; tilt/zoom feel right in twisty roads.
- [ ] Pan the map away while riding: the cyan glow on the edge points the right way and follows as
  you move; the ◎ button brings you back and disappears.
- [ ] A tap on the small dashboard map no longer goes full screen (only the rail button does).

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
