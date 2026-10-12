# Device checks

Things to try or look at on the real devices (Hugerock X7 tablet, Pixel 9a) that couldn't be
tested in the emulator. Newest first; tick them off or delete them when done.

Commands assume the device is connected over USB. With both connected, pick one with
`-s <serial>` (see `adb devices`). `adb` lives in `~/Android/Sdk/platform-tools/`.

## 2026-10-13 — Calls as a slim card and a floating icon

Needs the new Curveon Companion on the Pixel (it now says whether a call has been answered) and the
new Curveon on the X7. Both were installed; the X7 and the Pixel were never connected to each other
while testing, so none of this has been seen with a real call.

- [ ] Ring the Pixel with the X7 on the full map: a slim card (small picture, name, red Decline,
      green Answer) in the bottom right, not the big photo. In the dashboard view the same slim card
      sits on top of the right-hand column, with music or messages below it.
- [ ] Answer: on the full map the card becomes a green phone icon. Drag it: it settles on the left edge
      or just left of the map's buttons, and sits there for the next call. Tap it: name, Hang up and
      Speaker open for a few seconds. It stays visible with Settings, GPX or Phone open.
- [ ] Call placed from the X7's Phone screen: same icon once it connects.
- [ ] Android below 12 on the phone (if any): an answered call may still show as ringing; say so.

## 2026-10-12 — Corners, panels over the map, swipe, new menu icons

- [ ] X7 riding (not the demo ride): the trip bar sits top left, the speed top right, the next turn
      bottom left, music bottom right (full map), and the map's buttons keep clear of all four. A
      call on the full map takes the bottom right, left of the buttons.
- [ ] Pixel sideways and upright (Curveon on the phone): the same, with nothing over the +/− and
      3D buttons; the map's (i) licence button sits halfway up the left edge and opens its text.
- [ ] Rail: the open screen's button is blue; pressing it again closes the screen, pressing another
      one swaps it in; Back closes it; the keyboard in "Where to?" doesn't hide the field.
- [ ] Upright (X7 or Pixel): Settings and GPX & rides swipe between their pages, and the tab row
      follows. Sliders and horizontal lists inside the pages still scroll without changing page.
- [ ] Welcome tour (Settings → Help): Where to?, Plan a trip and Roundtrip light up the motorcycle
      button and say what to press first.

## 2026-10-11 — New app icons (motorcycle on the curve)

- [ ] Pixel: Curveon and Curveon Companion (the cyan phone with the new art on its screen) show
      the new icon in the app drawer; the round mask keeps both mirrors. With Themed icons on
      (wallpaper settings), both show a clean one-colour version.
- [ ] X7: install Curveon there too (it wasn't reachable) and check the icon on its launcher.

## 2026-10-11 — Day road colours on the night map

- [ ] Night map: roads have the day map's colours (white streets, yellow main roads and motorways)
      with the same outlines. Too bright to ride by at night? Then say so; the old night colours are
      in git (`app/src/main/assets/map/styles/dark.json` before this commit).

## 2026-10-11 — Speed limits without a map

- [ ] Pixel (no maps installed), online and with a GPS fix: within a couple of minutes
      `adb -s <pixel> shell ls /sdcard/Android/data/com.curveon.tablet/files/speedlimits/` shows
      `norway.sqlite` (the country it is in); the limit sign then shows on roads, e.g. in the demo ride
      or "Hold a finger on a road".

## 2026-10-11 — New layout of the cards over the map

- [ ] While navigating, full map: the next-turn card sits bottom right beside the music player, both
      the same height and not overlapping; the time-left bar is top, beside the speed and as high.
- [ ] Dashboard: the same top bar (arrival, km · time, X); tap it and a second line folds out below
      with next stop (tap: route menu), fuel and add stop. The turn card is bottom right of the map.
- [ ] 3D / heading up: the arrow stays clear of the turn card (it sits a bit higher than before).
- [ ] Phone (portrait and landscape): bar and speed fit side by side; player goes above the turn card.

## 2026-10-11 — No U-turn on E39 at Heiane (Stord)

- [ ] Plan a trip to McDonald's Stord from the south-west (say from Føyno): the route should take the
      exit to the right off E39, the roundabout and the bridge, with no U-turn on E39. Worth a
      look at other motorway exits too: they should look as before.

## 2026-10-11 — Welcome tour texts and drawings

- [ ] Settings → Help → Start the tour (the Pixel needs the newest install first): the phone step
      says a tablet that can't call needs Curveon Companion on the phone; Settings says offline
      maps, phone link and more; the map step only mentions holding a place; after music and
      messages comes an engine-data drawing; "Change just one part" ends with a dashed line being
      pulled out, as when editing on the map.

## 2026-10-11 — Full map or dashboard per orientation

- [ ] On the phone: in portrait pick the full map, in landscape the dashboard. Rotate back and
      forth: each orientation returns to what it last had. Restart Curveon: same again.

## 2026-10-11 — Map view remembered per mode

- [ ] Fresh (or after clearing settings): the map without a trip is north up; start a trip and it
      goes to 3D. Stop the trip: back to north up.
- [ ] Press the compass button during navigation (say to heading up), stop and start another trip:
      heading up again. The choice made while browsing the map is kept separately.

## 2026-10-11 — Demo mode

- [ ] Settings → Display → Demo ride: switch it on, plan a trip and press Go: the ride starts by
      itself as a simulation, from the trip's own start point if you set one (not from where the
      tablet is). Switch it off: back to real GPS. Switched on during a trip: that trip is
      simulated from here.

## 2026-10-11 — Places on the map

- [ ] Settings → Display → On the map: switch on Fuel, Speed cameras, Viewpoints, …; zoom in on a town
      you have a map of: the places show with icons and names (fuel green, cameras red, ferries grey),
      tapping one opens its card. Riding, they should keep appearing ahead without the map stuttering.
- [ ] Switch them off again: the map is clean as before.

## 2026-10-11 — Welcome tour

- [ ] Start Curveon on the X7: the welcome tour opens by itself (once). Walk through it: the lit
      ring should sit neatly round each rail button, the map buttons, the speed and the cards, and
      every card should fit on screen (also the long "Planning a trip" one).
- [ ] First start (clear the app's data to see it again): the tour opens with Language · Språk, and
      carries on in the language you tap. Six small animations (tablet–phone–intercom, just a phone, plan, edit on the map, mark one part
      with the three-dots button, draw a roundtrip) loop without text.
- [ ] Settings → Help → Start the tour: it starts again; the symbol list below reads well.
- [ ] On the Pixel held upright: the tour turns the screen to landscape while it is open, and back
      to upright when it closes.

## 2026-10-11 — Official speed limits; road info when holding a finger on a road

**Official speed limits.** Where a country publishes them freely, the speed sign now shows the
official limit of the road you're on and falls back to OpenStreetMap elsewhere: Norway (Statens
vegvesen NVDB, public roads), Finland (Digiroad), the Netherlands (NDW/RWS WKD), Flanders (AWV).
Built monthly by the curveon-data repo (`speedlimits` release, ~7 MB for Norway); the tablet
fetches the file for each country it has a map of, with the map, or by itself ~20 s after
starting when online. Credits under Settings → Maps & data → Speed limits. Around Bømlo/Stord/
Fitjar OSM has no limit on 47 % of kommunal veg and differs on ~5 % of main roads; the official
file gave the right limit at all 1 497 test points.

**Road info.** Holding a finger on a road shows, in the card that opens, its speed limit sign,
name/number, kind (main road, country road, track…) and surface, and whether the limit is
official or from OpenStreetMap. Information only.

- [ ] X7/Pixel online: after a start, Settings → Maps & data → Speed limits says "Norge: Statens vegvesen, NVDB …".
- [ ] Riding on a kommunal veg that showed no sign before: does it show one now, and is it right?
- [ ] Hold a finger on a few roads (main road, gravel road, track): is the info right?

## 2026-10-11 — Gravel reach when drawing a loop

With Gravel or Offroad in the drawing bar, the "Gravel within N km / anywhere" chip is there too.
The loop's shape is found with gravel's full pull; the reach applies when it is ridden as a trip
through its points (each stretch between two points keeps that close to the ordinary road), and
can be changed on the sheet afterwards. Checked in the emulator.

- [ ] Draw a gravel loop inland with "within 3 km": less gravel than "anywhere", but some?

## 2026-10-11 — Brighter roads on the dark map

Roads on the night map were barely lighter than the land (#333–#474 on #1f1f1f). They are now
clear greys, brightest for the biggest roads (small #4b–#5f, main #6f, motorway #8a), tunnels a
step darker, edges darker than the land; background, water and buildings unchanged. Checked in
the emulator.

- [ ] Riding at night: are the roads easy to tell from the land now, without glare? The blue route still the brightest?

## 2026-10-11 — No motorways or E-roads

The motorway chip is a list: Motorways OK / No motorways / **No motorways or E-roads**. The routing
data has road classes, not numbers, so "E-roads" means trunk roads: in Norway the E-roads and the
biggest national roads (Rv 7, Rv 13 where tagged trunk); still ridden where there is no other way.
Haugesund–Bergen fastest: 61 km on trunk → 6 km (139 → 165 km). Checked in the emulator.

- [ ] A trip you'd normally get E39/E134 on: does it keep off them, and is the detour sensible?

## 2026-10-11 — Rides keep their times in GPX (for lining up with video)

A ride saved to the GPX collection now keeps when each point was ridden, and exported GPX files
have `<time>` (UTC, tenths of a second) on every track point and in the metadata, so video tools
can line the track up with a film. Rides are recorded to a tenth of a second from now on (older
rides have whole seconds; saving one of those to GPX again gives it times too). Times in imported
GPX files are kept and written back out; a track exported reversed gets none. Unit tested; installed on the X7.

- [ ] Save a ride to GPX, export it, open in a video/GPX tool (e.g. GoPro Quik, Telemetry Overlay): the times match the ride.

## 2026-10-10 — Altitude (m a.s.l.) under the speed, optional

Settings → Display → **Altitude** shows the height above sea level under the speed (off by
default). It comes from the GPS receiver's own NMEA (GGA, height above sea level); Android's
altitude is above the ellipsoid, ~40 m higher in Norway, and is only used (or Android 14's sea-level
height) when the receiver sends no NMEA. Recorded rides now get sea-level heights too. Checked in
the emulator; installed on the X7 (the Pixel wasn't reachable).

- [ ] Compare with a known height (a summit sign, or the sea: ~0–5 m at the quay) on the X7 and the Pixel.

## 2026-10-10 — Gravel within reach of the route; the route line coloured by surface

With Gravel or Offroad picked, a chip next to it says how far off the ordinary route gravel may take
the trip: within 1/3/5/10/20 km, or anywhere. The ordinary route is planned first, then the gravel
one with less and less pull towards gravel until it stays that close; failing that, the ordinary
route. Gravel itself pulls harder now (before, Gravel barely changed a route): Kongsberg–Notodden
curvy gets 17 km gravel (33 → 43 km, 6 km off); within 3 km it stays on the ordinary road. On the
west coast few gravel roads are tagged in OpenStreetMap, so there it changes less. The previewed
route is drawn orange where it is gravel and purple on tracks. Checked in the emulator; installed on the X7.

- [ ] Gravel near home: does it find any? Does "within N km" behave?
- [ ] Are the orange/purple stretches readable on the X7 in sunlight?

## 2026-10-10 — Max length and surface when drawing a loop; draw again

The drawing bar has **Whole area / Max N km** (50–300) and the surface chip; with a max, the loop is
planned round the area shrunk towards the start, so it stays about that long (tests: max 50 → 48
km, max 80 → 55 km from Bømlo in a big area). The loop's sheet has the same max chip (plans the loop
again, on the road style picked there) and **Draw again** (same start, max and surface). Austevoll
in a drawn area: it is reachable (ferry Sandvikvåg–Hufthamar) but only that way in and out, so a
loop over it rides the ferry and Stord roads twice and loses to loops that don't. Checked in the
emulator; installed on the X7 (the Pixel wasn't reachable).

- [ ] Draw with Max 80 km on the X7: about 80 km or less?
- [ ] Change the max on the sheet, and Draw again.

## 2026-10-10 — Close Curveon from the notification; warning when it idles in the background

The "GPS and navigation are running" notification now has **Close Curveon**: it closes the screen,
GPS, the phone link and OBD and ends the app (Android keeps only its small notification reader).
When Curveon has been out of sight for 10 minutes without a trip being navigated, a second
notification with a sound says it is still using GPS and battery, with the same button; opening
Curveon takes it away. Checked in the emulator (with the wait shortened): GPS off after closing. Installed on the X7 and the Pixel.

- [ ] On the Pixel: leave Curveon for another app for 10 min (no trip): the warning sounds.
- [ ] "Close Curveon" from the notification: Curveon is gone; opening it again starts normally.
- [ ] Navigating with another app in front: no warning.

## 2026-10-10 — Roundtrip is drawn straight away; loops ride less road twice

"Roundtrip" in Where to? now goes straight to drawing on the map (the direction/distance dialog is
gone): the bar shows "About N km round" while drawing, and "From …" when a start elsewhere was set.
The loop is planned curvy with the last route options; the sheet then offers every style, ferries
and motorways as for any trip. Loops now steer clear of road they already rode: where the best loop
still rides a stretch twice, those legs are routed again with that road made expensive (allowed
when it's the only way, e.g. off an island). On test loops round Bremnes/Haugesund the share ridden
twice went from 12% to 5% on average; drawn areas on Bømlo/Stord from ~30% to 7–17%. Checked in the
emulator.

- [ ] Roundtrip from home: does it still ride stretches twice? Where (screenshot)?
- [ ] How long a drawn loop takes on the X7 now.

## 2026-10-10 — Route options say what they do

The option chips under a route (and in Roundtrip) were hard to read: a struck-through
"~~Motorways~~" didn't say clearly whether motorways were on or off. They now say it in words:
"Motorways OK" (grey) or "No motorways" (amber, icon crossed out in red); the same for ferries;
the surface chip says "Tarmac only" (then Mixed, Gravel, Offroad). Checked in the emulator.

- [ ] Ask Øystein whether the chips are clear now.

## 2026-10-10 — Favourites: hold a place on the map, keep it as Home or by name

Holding a finger on the map (no trip) now asks: **Go here** (the trip preview as before) or **Save
as favourite** – Home with one tap (a new Home replaces the old), or a name (the place's, to
change). Riding or planning, the same hold also offers it next to stop / destination / start.
Favourites come first in Where to? (Home leads) for the destination, a stop or the start; hold
one to delete it. Checked in the emulator; installed on the X7 and the Pixel.

- [ ] Save Home on the X7, then Where to? → Home plans the way home.
- [ ] A named favourite as a stop while riding (+ Stop → the favourite).
- [ ] Hold a favourite in Where to? → delete.

## 2026-10-10 — Music pill no longer covers the trip bar; no crash turning the phone

In the full-screen map with music playing, the music pill sat on top of the trip bar (arrival,
km and time left) where both don't fit side by side (the phone, upright): it now moves up above
it. Turning the phone while navigating crashed the app (the map read road names before it had
drawn after the turn); it now waits. Checked in the emulator at Pixel size; installed on the X7 and the Pixel.

- [ ] Phone upright, navigating, full map, music playing: the pill sits above the trip bar.
- [ ] Turn the phone while navigating: no crash, the road name after the next turn still shows.
- [ ] The blue route line is thicker when riding (about twice as wide zoomed in, as before zoomed out): easy to follow, not hiding the road names?

## 2026-10-10 — Roundtrip in "Where to?": draw the area, start elsewhere, edit the loop

The Roundtrip button on the rail is gone: Roundtrip is now a row in "Where to?", shown only when
no trip is planned or being ridden. Its dialog has **Draw area**: the map goes flat and zooms out,
one finger draws the area to ride in (two fingers still move the map), and on letting go a loop
inside it is worked out (Shuffle gives another). "From my position" in "Where to?" (or the start
chip on the loop's sheet) starts the loop somewhere else. The pen icon on a loop's sheet turns it
into an ordinary trip with via points about every 8 km, to drag and change like any other.
Update: a found loop now *is* such a trip straight away – the same sheet as "Where to?" (start,
+ Stop, the via points, Fastest/Curvy/Super curvy, edit on map), titled "Roundtrip from …", with
Shuffle for another loop; changing the start plans a new loop from there.
Checked in the emulator (Haugesund, Leirvik); installed on the X7 and the Pixel.

- [ ] Draw an area with a finger on the X7: the stroke follows the finger, two fingers pan/zoom.
- [ ] How long a drawn loop takes on the X7 (`adb logcat -s Curveon CurveonPlan` → "Routed Roundtrip in … s"; 1–15 s on the PC).
- [ ] Draw round Bømlo/Stord from home: does the loop use the area and stay off dead ends?
- [ ] A loop's sheet looks like a planned trip; drag a point on the map, the rest of the loop stays.
- [ ] Shuffle on a loop (also after changing road style) gives another loop.

## 2026-10-10 — The map stays put while planning; a button shows the whole trip

Planning a trip no longer zooms the map out by itself each time the route changes (picking places,
stops, detours, route options): it stays where you put it. The new button top right (four arrows,
under the speed) fits the whole trip, like zoom extents. Only when a trip first appears and isn't
already in view is it fitted once; your own position is only included when the trip starts near
you. Checked in the emulator; installed on the X7.

- [ ] Plan a trip far from where you are, pick places on the map: the map doesn't jump.
- [ ] The four-arrow button shows the whole trip above the sheet.
- [ ] The X on a planned trip asks first: Keep / Save / Discard.

## 2026-10-10 — Rider glides on the map, quicker off the line after a wrong turn, new 3D, ride trace

The arrow and the map now move every frame (carried forward between the once-a-second GPS fixes,
plus ~0.25 s for the receiver's delay) instead of easing to each fix a second late. Taking another
road lets go of the route line after ~7 m when heading away from it (before: only once the
navigator called it off route, 40 m+ for 3 fixes). 3D: tilt 50° (was 60°), rider ~3/4 down the
map, zoom 17.2 at 20 km/h → 15.6 at 90, in a bit when a turn is under 300 m away. Installed on the
X7 (the Pixel was offline: `ANDROID_SERIAL=<pixel> ./gradlew :app:installDebug`).

Every app start writes a trace to `Android/data/com.curveon.tablet/files/navtrace/trace-*.csv`
(raw GPS fixes, route matching, where the arrow was drawn, routes, OBD values; kept 14 days).

- [ ] Ride a route, including a junction where you deliberately go the wrong way, in heading-up
      and in 3D. Does the arrow glide, keep up with the bike at junctions, leave the line at once?
- [ ] Then pull the traces and tell me roughly when/where (time of day, which junction):
      `adb -s <X7> pull /sdcard/Android/data/com.curveon.tablet/files/navtrace/ ~/develop/Curveon-data/navtrace/`
      (Pixel: same path with its own serial).

## 2026-10-10 — Speed on the map, engine panel (OBD2)

Speed, limit, clock (and speed camera / GPS lines) are always on the map now, top right. Settings →
Engine: engine panel on/off, the OBD2 adapter (paired Bluetooth devices, or Demo), and which values
(fuel, RPM, coolant, battery). Tablet: panel above the music; phone upright: music bottom left,
panel bottom right, map up to the top. Checked in the emulator with Demo. Installed on both devices.

- [x] Adapter connects on the Ténéré 700 (2020): RPM, coolant and battery come through.
- [ ] Fuel: the T7 doesn't answer PID 012F. On the first connection after each app start the
      tablet now notes what the ECU supports plus 20 s of raw bus traffic in
      `files/navtrace/obd-*.txt` (the readings show up ~30 s later than usual that one time).
      Connect once with a full tank and once with it low (ignition on, engine idling is fine),
      then pull the navtrace folder as above, so we can look for the fuel gauge on the bus.
- [ ] Riding with a turn banner: it sits top left and doesn't cover the speed (phone upright too).

## 2026-10-10 — Stops down a side street are ridden to, and one map editor

Two changes, installed on the X7 (the Pixel was offline: `./gradlew :app:installDebug` with it as
`ANDROID_SERIAL` once Wireless debugging is back on).

- Stops: a stop just off the way (e.g. a side street in Haugesund) is now ridden to; before, the
  route rode past it ~200 m away (BRouter cut the out-and-back to it). Trips with stops are now
  routed leg by leg. (The first try, 7e75557, turned plain A→B trips into there and back; fixed.)
  `:routing:test` StopsVisitedTest covers both.
- The edit-on-map button (and a long press on the route) opens the same editor as "+ Stop" → Pick
  on map: tap to add a point, drag the line to bend the route, tap/hold a point for its menu
  (remove, move, change place, add stop before), the dotted-line button marks a part of the route.

- [ ] X7: Bømlo → Haugesund without stops is one way (~67 km), not there and back.
- [ ] X7: add a stop in a side street near the route: the blue line goes into the street to it.
- [ ] Edit button on the route preview → drag the line → a detour; marking button → two pins.
- [ ] Tap a stop on the map → menu → Change place opens the search and comes back to the trip.

## 2026-10-10 — Two fingers on the map zoom, never drag the route

While editing or building a trip, a pinch whose first finger lands on the route line or a point no
longer starts a detour: as soon as the second finger touches, the drag is dropped and the map
zooms/turns. Checked in the emulator with a scripted two-finger pinch starting on the line (route
unchanged, map zoomed). Installed on both devices.

- [ ] Pixel/X7: pinch-zoom with one finger starting on the blue line: no dashed detour, no new point.

## 2026-10-10 — "+ Stop" on the map asks: on the way, or the new end?

Picking stops on the map from "+ Stop" on a trip that already has an end: the first tap asks "A
stop on the way, or the new end?". On the way: that point and the next ones become numbered stops
where they fit along the route; New end: the trip rides on to them (the old end stays a stop).
Asked once per round of picking. Checked in the emulator. Installed on both devices.

- [ ] X7: install, then "+ Stop" → Pick on map → tap a point beside the route → On the way.

## 2026-10-10 — Hold a point on the map to remove it

While editing a trip on the map or building it point by point, holding a finger still on a point
(white dot, numbered stop, start or destination) opens its menu with "Remove point" (a stop also
keeps moving earlier/later). Removing the destination makes the point before it the destination.
Dragging a point works as before. Checked in the emulator. Installed on both devices.

- [ ] Pixel: a long press on a white dot gives a buzz and the menu; Remove reroutes without it.
- [ ] A quick drag of a point still moves it (no menu popping up mid-drag).

## 2026-10-10 — Save a route to Where to?, GPX, or both

The route's save dialog has two ticks: Where to? (saved routes, as before) and GPX track, with
the GPX group to put it in (an existing one, no group, or a new one, as when saving a ride). The
choice is remembered for next time. The GPX copy has the route's line and its stops as waypoints.
Checked in the emulator (saved to both, the track shows up in its GPX group). Installed on both.

- [ ] Pixel portrait: the dialog fits, the group name doesn't push the tick off the row.
- [ ] Export the saved GPX track from the GPX tab and open it elsewhere: line and stops are there.

## 2026-10-10 — Build a trip point by point on the map

"Pick on map" (for a destination or a stop) has a new button (a zigzag line) next to the search
icon. Switched on, each tap on the map rides the trip on to there right away (the old end becomes
a point on the way); the bar shows the trip's time and length, Undo takes the last point back and
the tick finishes into the route preview. It stays on until switched off. Points can be dragged
while building. Checked in the emulator (X7 landscape, Pixel size portrait). Installed on both.

- [ ] Pixel: plan A → B → C → D by tapping; the map stays where you tap (no zooming out between points).
- [ ] A tap right on the route line also adds the next point (e.g. riding back the same way).
- [ ] Point by point is now always how a destination/stop is picked on the map (no toggle). Picking
      the start, a stop while riding, or "Change place"/"Add stop before" still picks one place.

## 2026-10-10 — Mark the part of the route to change

While editing a trip on the map, the new button (a line with dots) left of Undo marks a part of
the route: drag along the line, and its two ends get pinned as small white points, so dragging the
line between them only changes that part. Done is a round tick on a phone. Checked in the
emulator (X7 landscape, Pixel size portrait). Installed on both devices.

- [ ] Pixel portrait: editing shows the tick (Done) at the right of the bar.
- [ ] Long trip (e.g. Bømlo → Oslo): mark a part at Haukeli, drag the line out to a side road;
      the route outside the marked part stays as it was, and Undo takes the marking away in one go.
- [ ] Marking with a finger that drifts off the line still slides the orange part along the route.

## 2026-10-10 — The route preview scrolls instead of losing GO

When the route preview runs out of height (the Pixel in landscape with a gravel warning), the part
between the title and the options scrolls; the options and GO always stay at the bottom. Checked in
the emulator at Pixel size, landscape. Installed on both devices.

- [ ] Pixel landscape: the trip with the gravel warning shows Tarmac/Ferries/Motorways and GO;
  swipe the cards up to read the whole warning.

## 2026-10-10 — The clock sits by the speed

The clock moved from the rail to the right end of the speed card (and of the speed box on the
full-screen map), so the rail's buttons get the room. Checked in the emulator on the X7 (both
layouts) and phone upright. Installed on both devices.

- [ ] Pixel upright: the six buttons along the bottom have room now.
- [ ] Riding with a speed limit sign showing: speed, sign and clock all fit on the X7 and Pixel.

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
