# Curveon privacy policy

Effective 2026-10-18. This policy covers the apps **Curveon** (`com.curveon.app`) and **Curveon Companion**
(`com.curveon.companion`).

**In short:** Curveon has no accounts, no advertising, no analytics and no server of its own. What the apps
read on your phone stays on your own devices. The only things that leave them are the requests you make to
online map and search services, listed below.

Developer and contact: [name], [email address].

## What the apps use, and why

| What | Why | Where it goes |
|---|---|---|
| **Location** (GPS) | To show you on the map, navigate and record rides | Stays on the device. A plain-text trace of recent rides (positions, speed) is kept in the app's own folder for 14 days, then deleted |
| **Contacts** | To show favourites, names and photos in the phone view, and to call | Read on your phone, shown on your Curveon display. Not sent to anyone else |
| **Call log** | To show recent and missed calls on the display so you can call back without touching the phone | Same: read on request (when the phone connects and when you open the phone view), up to the 200 latest calls, kept in memory only. Never changed or sent to a server |
| **Notifications** (notification access) | To show messages, calls and the music that plays, and to let you reply | Read on the phone, passed to your display. Not stored |
| **Microphone** | Voice replies to messages | Handled by the phone's own speech recognition, which prefers offline recognition. If the phone's speech service uses the network, it is that service (normally Google's) that sends the audio, under its own terms. Curveon keeps no recordings |
| **Bluetooth** | The link between the phone and the display, and an optional OBD2 adapter for engine data | Stays between your devices |
| **Installed apps** (apps with a launcher icon and music apps) | To show the name of the app that sent a message or plays music | Used on the device only |

Curveon Companion has no internet permission and cannot send anything off the phone: it only talks to your
Curveon display over Bluetooth. <!-- Only true until the riding groups (groups.md) ship: then rewrite, see google-play.md -->

## What is sent over the internet (Curveon only)

Only when you use these functions, and only over HTTPS:

- **Place search.** What you type, and your approximate position (so the nearest results come first), go to
  Photon (photon.komoot.io, Komoot) and, for Norwegian place names and addresses, what you type goes to Kartverket
  (ws.geonorge.no). These services see your IP address, and Curveon does not control what they keep.
- **Online map.** The map tiles come from OpenFreeMap (tiles.openfreemap.org). They see which area you look at and your IP address.
- **Downloads of maps and road data** (Settings): map files from Protomaps (build.protomaps.com) and
  routing data from BRouter (brouter.de). They see your IP address and which regions you ask for.

Curveon has no other servers and sends nothing to the developer. With downloaded maps you can ride fully offline.

## What is kept on your device

Saved routes, places, tracks, rides and settings are stored in the app. Delete them in the app, or by
clearing the app's data or uninstalling it; nothing is kept elsewhere by us.
The Android backup may copy app data to your Google account if you have turned device backup on; that is
handled by Google under your Google account's settings.

## Sharing, selling, children

We do not sell or share your data, do not use it for advertising or profiling, and do not build profiles.
The apps are not aimed at children.

## Your choices

You decide each permission in Android's settings, and the apps tell you what stops working without it (for example the
call log: the phone view's Recents tab stays empty). Because no personal data is held by the developer, there is
nothing to request access to or deletion of; uninstalling removes everything on the device.

## Changes and contact

If this changes, the new version replaces this page and the date at the top. Questions: [email address].
