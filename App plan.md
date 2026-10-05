
# Appname: VibeNav

# Short description
* An application to use on motorcycle for both navigation and music control / message notification ++.

# Reason for app
* The app will be runned on a mostly offline tablet with bluetooth connection to phone.
* Phone will have music player and other phone communication with a dedicated intercom headset. However, since I have a tablet with bluetooth communication to the phone, i want to mirror control and notification. Want to implement similar UI as of android auto in this manner.
* I am inspired by dmd2, but not satisfied with its complexity, and also not satisfied with the lack of navigation options that GoRide had.
* I am also inspired by the now discontinued GoRide app from Tomtom where they had a very simple user interface for navigation. But I wanted a more modern and feature-rich app.
* I am also inspired by the look and style of android auto dashboard screen.

# Goals
* Only application to show on motorcycle screen at all time.
* Great implementation of open street map
* Great navigation options with turn-by-turn directions, and motorcycle specific options like amount of curvy roads, gravel selections etc.

## Dasboard
* 2/3 screen used for map
* 1/3 screen is shared for:
* * Speedometer / tachometer
* * Music control / information
* * Notification from apps like SMS, WhatsApp, etc..
* * Optional more info from ODB2, but not unless setting is enabled.
* When cliking on map, it should show map in full screen.
* When map is in full screen, a button should be visible to return to dashboard.

## Map / navigatin specification
* Map must be able to run offline. Therefore use OpenStreetMap.
* Map must have good support for GPX files.
* Map should show speed limits when available.
* Navigation should include:
* * Turn-by-turn directions
* * Option to select road type (fastest, curvy, very curvy)
* * Option to select preferred ground (road, gravel, etc.)
* * Option to include ferrys, highways, etc.
* Roundtrip option should be available. Be inspired by GoRide where you could easily select a roundtrip route, and just select approximate directions (north, south, east, west) and approximate distances.

### Music control
* Music is played on a mobile phone, not on this app.
* This app should have control over the music on the mobile phone.
* This app should fetch song information from the mobile phone, and also images from cd cover art.

### Notifications
* This app should show notifications from apps like SMS, WhatsApp, etc.
* This app should show images from mobile phone book, so that it looks native.

## Other
* As you see, a mobile phone application is also required with a dedicated app on that phone.
* You must make a recipe on how the app should work on the mobile phone, as a there will be a vibe coded app on the mobile phone that will communicate with this app on a later date.
