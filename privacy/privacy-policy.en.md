# Curveon privacy policy

Effective [date]. This policy covers the apps **Curveon** (`com.curveon.app`) and **Curveon Companion**
(`com.curveon.companion`).

**In short:** Curveon has no accounts, no advertising and no analytics. What the apps read on your phone stays
on your own devices. What leaves them is requests to online map and search services and, if you choose to ride
with others, encrypted messages through a relay server we run. The relay cannot read the content.

Controller and contact: [name], [email address].

## What the apps use, and why

| What | Why | Where it goes |
|---|---|---|
| **Location** (GPS) | To show you on the map, navigate and record rides | Stays on the device. A plain-text trace of recent rides (positions, speed) is kept in the app's own folder for 14 days, then deleted. When you share your position in a group, see "Riding groups and friends" below |
| **Contacts** | To show favourites, names and photos in the phone view, and to call | Read on your phone, shown on your Curveon display. Not sent to anyone else |
| **Call log** | To show recent and missed calls so you can call back without touching the phone | Read when the phone connects and when you open the phone view, up to the 200 latest calls, kept in memory only. Never changed or sent to a server |
| **Notifications** (notification access) | To show messages, calls and the music that plays, and to let you reply | Read on the phone, passed to your display. Not stored |
| **Microphone** | Voice replies to messages | Handled by the phone's own speech recognition, which prefers offline recognition. If the phone's speech service uses the network, that service (normally Google's) sends the audio, under its own terms. Curveon keeps no recordings |
| **Bluetooth** | The link between the phone and the display, and an optional OBD2 adapter for engine data | Stays between your devices |
| **Installed apps** (apps with a launcher icon and music apps) | To show the name of the app that sent a message or plays music | Used on the device only |
| **Camera** | To scan a friend's QR code or a group invite | The image is only used to read the code and is not stored |

## What is sent over the internet

Only when you use these functions, and only over an encrypted connection (HTTPS):

- **Place search.** What you type, and your approximate position (so the nearest results come first), go to
  Photon (photon.komoot.io, Komoot) and, for Norwegian place names and addresses, what you type goes to Kartverket
  (ws.geonorge.no). These services see your IP address, and Curveon does not control what they keep.
- **Weather forecast.** When you plan a trip or open Where to?, Curveon asks MET Norway (api.met.no, the Norwegian
  Meteorological Institute) for the forecast for the places the trip goes through. What is sent is the middle of a
  square of about 10 × 10 km, never your exact position. If the tablet has no internet, the phone with Curveon
  Companion does the same for it. MET sees the IP address. You can switch it off in Settings → Navigation → Weather.
- **Online map.** The map tiles come from OpenFreeMap (tiles.openfreemap.org). They see which area you look at
  and your IP address.
- **Downloads of maps and road data** (Settings): map files from Protomaps (build.protomaps.com) and routing data
  from BRouter (brouter.de); search data and speed limits from Curveon's data repository on GitHub (github.com).
  If you plan a trip online without a downloaded country, only the routing data that trip needs is fetched.
  These services see your IP address and which regions you ask for.
- **New versions** (only the edition from GitHub): Curveon asks GitHub (api.github.com) whether a new version
  is out, and downloads it from there. GitHub sees your IP address.

- **Bug reports and ideas** (Settings → Help, only when you tap Send). What you write, the pictures you pick, the app
  version, the device model and Android version, and, if you leave it ticked, the start of the latest crash report go
  through our relay (relay.curveon.no) to a **public** issue on GitHub (github.com/atlehavik/curveon), where anyone can
  read it. The relay passes it on and keeps nothing; pictures are re-encoded first, which removes a photo's location.
  GitHub (Microsoft) handles the issue under its own privacy terms. The app keeps the issue's number and link so you
  can follow it, and asks GitHub how it stands when you open Help. To have an issue removed, ask us (contact below).

With downloaded maps you can ride fully offline.

## Riding groups and friends

This is optional and off until you add a friend or start a group.

- **What you share:** your position (only while sharing, and only with the active group), nickname, colour and
  profile picture, routes and shared trips, and an SOS alert if you trigger one. The group's members see this.
- **End-to-end encrypted:** all of this is encrypted on your device with a key only the group's members hold,
  before it is sent. The relay cannot read positions, names, pictures or routes.
- **The relay** (relay.curveon.no) is run by us on a server at netcup in Germany. It forwards the
  encrypted messages. It sees your IP address when you connect (used to limit abuse, not kept in a log), group
  and participant IDs that cannot be tied to you as a person, and the size and timing of messages.
- **What the relay stores** (encrypted): the latest profile per participant, the latest routes, changes to shared
  trips, friends' invitations and an active SOS alert (until cancelled, at most 12 hours). Positions are never
  stored. Everything is deleted automatically after 30 days of inactivity.
- **Friends** are private channels between two people. Friend invitations travel as a code or QR code you hand
  over yourself.
- **Leaving and deleting:** you can leave a group or remove a friend at any time. What is left on the relay is
  encrypted and expires by itself. Contact us if you want it removed sooner.

## What is kept on your device

Saved routes, places, tracks, rides, settings, friends and shared routes are stored in the app. Delete them in
the app, by clearing the app's data or by uninstalling it. The Android backup may copy app data to your Google
account if you have turned device backup on; that is handled by Google under your account's settings.

## Sharing, selling, children

We do not sell or share your data, do not use it for advertising or profiling, and do not build profiles.
The apps are not aimed at children.

## Your rights and choices

You decide each permission in Android's settings, and the apps tell you what stops working without it (for
example the call log: the phone view's Recents tab stays empty). We hold no personal data about you
apart from the encrypted data kept temporarily on the relay, so there is little to request access to. You have
the right to access, correction and deletion under the GDPR, and to complain to the Norwegian Data Protection
Authority (Datatilsynet). Contact [email address].

## The web planner

The web planner (relay.curveon.no/plan, also reached from curveon.no/plan) has no account and no cookies. The points you plan through go to our server to work out the road; it answers and keeps nothing, and nothing about you is logged. What you search for goes to Photon (Komoot) and Kartverket, and a point you click on the map goes to Photon to be named. A trip you send to Curveon is encrypted in your browser with a key that only the browser and the apps that scanned its code have; the relay keeps it, unreadable, for up to 30 days so the app can fetch it. The key and the list of trips you sent are kept in your browser's local storage, and you can clear them with the browser's site data. While a phone or tablet is connected to the page, Curveon keeps its planned trips there (the newest 60) encrypted on the relay the same way, so the page can show them; ending the session under Planned → From the web stops it.

## The website

curveon.no and the invite page at relay.curveon.no use no tracking or cookies. The server sees your IP address
when you open them, and it is not kept in a log.

## Changes

If this changes, the new version replaces this page and the date at the top.
