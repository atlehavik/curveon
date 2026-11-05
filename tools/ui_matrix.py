#!/usr/bin/env python3
"""
Screen matrix: runs Curveon's screens on one emulator dressed up as the common kinds of Android screens
(sizes, densities, camera holes and notches, navigation bars, large text), in portrait and landscape, and
checks that nothing the rider must reach ends up off screen.

    python3 -I tools/ui_matrix.py -s emulator-5556 [--variants pixel9a,small360] [--scenarios crop] [--languages de,nb] [--out DIR] [--json FILE]

Use the Curveon_Phone AVD (API 36) with a debug build installed, "This device" picked as the phone
(Settings -> Phone) and at least one picture in the gallery (for the profile picture). Per variant and
orientation it writes a screenshot, and per scenario a contact sheet (`sheet_<scenario>.png`) to look over;
it prints every problem it finds and exits 1 if there were any. With --json it also writes every screen's
result (scenario, variant/orientation, problems, screenshot) to FILE, for the nightly run (ui_matrix_nightly.sh). The emulator is put back as it was at the end.

Checks, on every screen:
- a dialog window is never larger than the area Android gives it (the Pixel bug of 2026-10-22: a dialog
  placed below the camera hole at full height, so its bottom buttons were below the screen);
- every button and text the scenario names is found, lies wholly on screen and doesn't touch its edge;
- nothing clickable sticks out past the screen.
"""
import argparse
import base64
import gzip
import json
import os
import re
import sqlite3
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

PKG = "com.curveon.app"

# name, physical size (portrait), density, cutout overlay, font scale, navigation bar.
# Together they cover what riders' phones look like: dp width 360 (most Samsungs) to 411 (Pixel), tall 20:9
# and 21:9, cheap 720p phones, large text / display size, a foldable's inner screen, a 10" tablet and the X7.
VARIANTS = [
    ("pixel9a", "1080x2424", 420, "hole", 1.0, "gestural"),
    ("small360", "1080x2340", 480, "corner", 1.0, "threebutton"),
    ("budget720", "720x1600", 320, "tall", 1.0, "threebutton"),
    ("tall21x9", "1080x2520", 420, "hole", 1.0, "gestural"),
    ("bigtext", "1080x2424", 480, "hole", 1.3, "gestural"),
    ("fold_inner", "1840x2208", 420, "none", 1.0, "gestural"),
    ("tablet10", "1200x1920", 240, "none", 1.0, "threebutton"),
    ("x7", "1080x1920", 320, "none", 1.0, "threebutton"),
]
CUTOUTS = {"hole", "corner", "tall", "double", "waterfall"}


class Device:
    def __init__(self, serial):
        self.serial = serial
        self.rotation = 0  # how the variant holds the screen: 0 portrait, 1 landscape
        self.extra = []  # problems a scenario found on the screens it passed on the way (a tour's steps)
        self.scrolling_row_ok = False  # a scenario's screen has a row meant to scroll sideways: a cut button shows there is more
        self.out = "."  # where screenshots go
        self.scenario = ""  # the scenario running now
        self.tag = ""  # the variant and orientation now
        self.shots = []  # (label, path) of screens a scenario passed on the way, for its contact sheet

    def sh(self, *args, check=False):
        r = subprocess.run(["adb", "-s", self.serial, "shell", *args], capture_output=True, text=True)
        if check and r.returncode != 0:
            raise RuntimeError(f"adb shell {' '.join(args)}: {r.stderr.strip()}")
        return r.stdout

    def screenshot(self, path):
        with open(path, "wb") as f:
            subprocess.run(["adb", "-s", self.serial, "exec-out", "screencap", "-p"], stdout=f, check=True)

    def shot(self, name):
        """A screenshot of a screen on the way, for the contact sheet: the scenario's last screen is not all it shows."""
        path = os.path.join(self.out, f"{self.scenario}_{self.tag}_{name}.png")
        self.screenshot(path)
        self.shots.append((f"{self.tag} {name}", path))

    def screen(self):
        """The display's size in px as it is turned now."""
        out = self.sh("dumpsys", "window", "displays")
        m = re.search(r"cur=(\d+)x(\d+)", out)
        return (int(m.group(1)), int(m.group(2))) if m else None

    def nodes(self):
        for _ in range(3):
            self.sh("uiautomator", "dump", "/sdcard/ui_matrix.xml")
            xml = self.sh("cat", "/sdcard/ui_matrix.xml")
            if "<hierarchy" in xml:
                break
            time.sleep(1)
        else:
            return []
        root = ET.fromstring(xml[xml.index("<hierarchy"):])
        out = []

        def walk(n, scroller):
            m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", n.get("bounds", ""))
            if m:
                node = {
                    "text": n.get("text", ""), "desc": n.get("content-desc", ""), "pkg": n.get("package", ""),
                    "cls": n.get("class", ""),
                    "clickable": n.get("clickable") == "true", "bounds": tuple(int(v) for v in m.groups()),
                    "scroller": scroller,  # bounds of the nearest scrolling container round it
                }
                out.append(node)
                if n.get("scrollable") == "true":
                    scroller = node["bounds"]
            for c in n.findall("node"):
                walk(c, scroller)

        for top in root.findall("node"):
            walk(top, None)
        return out

    def find(self, labels, nodes=None):
        """The first node whose text or description is one of [labels] (or matches a regex starting with ~; a label
        starting with = matches the text only, not a description: the tour's "Next" button, not the music's)."""
        for n in nodes if nodes is not None else self.nodes():
            for label in labels:
                hay = (n["text"], n["desc"])
                if (label.startswith("~") and any(re.search(label[1:], h) for h in hay)) or label in hay \
                        or (label.startswith("=") and n["text"] == label[1:]):
                    return n
        return None

    def tap(self, labels, wait=2.0):
        n = self.find(labels)
        if n is None:
            raise LookupError(f"not on screen: {labels}")
        x1, y1, x2, y2 = n["bounds"]
        self.sh("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(wait)

    def dialog_windows(self):
        """(requested w, h, frame) of Curveon's windows other than the activity's own."""
        out = self.sh("dumpsys", "window", "windows")
        found = []
        for block in re.split(r"\n  Window #\d+ ", out):
            if f"{PKG}/" not in block.split("\n", 1)[0] or "ty=BASE_APPLICATION" in block:
                continue
            req = re.search(r"Requested w=(\d+) h=(\d+)", block)
            fr = re.search(r"\bframe=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", block)
            if req and fr:
                found.append((int(req.group(1)), int(req.group(2)), tuple(int(v) for v in fr.groups())))
        return found


# Scenarios: how to get to a screen, and what on it must be wholly on screen. Labels in English and Norwegian.
def forget_plan(d):
    """No trip left planned from a scenario before (the trip card would come back on the next start), and the screen
    turned back the way the variant holds it (the welcome tour turns it sideways, and Android keeps it so)."""
    d.sh(f"run-as {PKG} rm -f files/plan-draft.json")
    forget_offers(d)
    d.sh("settings", "put", "system", "user_rotation", str(d.rotation))


def forget_offers(d):
    """Nothing shared left to answer (a scenario's, or a test's from the day): the offer would lie over every other screen."""
    d.sh(f"run-as {PKG} rm -rf files/shared_trips.json files/shared_gpx")


def set_seen(d, *names):
    """Marks these first-time walk-throughs as seen, also when the app has never noted them: on a freshly installed
    app the settings file (and so the key) is not there yet, and the welcome tour would lie over every screen."""
    if "</map>" not in d.sh(f"run-as {PKG} cat shared_prefs/settings.xml"):
        d.sh("am", "start", "-n", f"{PKG}/com.curveon.MainActivity")  # the first start writes the file
        time.sleep(6)
        d.sh("am", "force-stop", PKG)
    for name in names:
        d.sh(f"run-as {PKG} sed -i -e 's| *<boolean name=\"{name}\"[^>]*/>||' "
             f"-e 's|</map>|    <boolean name=\"{name}\" value=\"true\" /></map>|' shared_prefs/settings.xml")


def go_home(d, demo=None, limit=0):
    """Curveon started afresh on the dashboard; demo=True/False first turns demo phone data on or off (debug build);
    limit > 0 shows that speed limit in the speed card (the emulator has no map data to read one from)."""
    d.sh("am", "force-stop", PKG)
    forget_plan(d)
    # The tour scenarios leave the welcome tour or the trip card's walk-through unseen: not over the other screens.
    set_seen(d, "tourSeen", "tripHelpSeen")
    d.sh(f"run-as {PKG} sed -i 's| *<int name=\"devSpeedLimit\"[^>]*/>||' shared_prefs/settings.xml")
    if limit:
        d.sh(f"run-as {PKG} sed -i 's|</map>|    <int name=\"devSpeedLimit\" value=\"{limit}\" /></map>|' shared_prefs/settings.xml")
    if demo is not None:
        old, new = ("false", "true") if demo else ("true", "false")
        d.sh(f"run-as {PKG} sed -i 's/name=\"demo\" value=\"{old}\"/name=\"demo\" value=\"{new}\"/' shared_prefs/phone_link.xml")
    d.sh("am", "start", "-n", f"{PKG}/com.curveon.MainActivity")
    time.sleep(6)


def scenario_dashboard(d):
    """The dashboard with a speed limit: the big sign sticks out over the speed card's top-right corner. All six
    buttons of the main menu must be whole, also sideways on the lowest screens, where it is spread over the height."""
    go_home(d, limit=80)
    return [["Where to?", "Hvor skal du?"], ["GPX & rides", "GPX og turer"], ["Phone", "Telefon"], ["Friends", "Venner"],
            ["Settings", "Innstillinger"], ["Full map", "Fullt kart"], ["80"]]


def scenario_settings(d):
    go_home(d)
    d.tap(["Settings", "Innstillinger"])
    return [["Language", "Språk"], ["Close", "Lukk"]]


def scenario_crop(d):
    go_home(d, demo=False)  # with demo phone data the profile can't be set
    d.tap(["Friends", "Venner"])
    d.tap(["Me", "Meg"])
    d.tap(["Choose from pictures", "Velg fra bilder"], wait=6)
    photo = ["~^Photo taken", "~^Bilde tatt"]
    # Android's picker slides up: a tap while it moves misses, so tap again while the picture is still there.
    for _ in range(3):
        if d.find(photo) is None:
            break
        d.tap(photo, wait=4)
    time.sleep(5)
    return [["Use this picture", "Bruk dette bildet"], ["Cancel", "Avbryt"],
            ["Crop your picture", "Beskjær bildet"]]


def scenario_mappoint(d):
    go_home(d)
    w, h = d.screen()
    d.sh("input", "swipe", str(w // 2), str(h // 3), str(w // 2), str(h // 3), "1000")
    time.sleep(3)
    return [["Map point", "Kartpunkt"], ["Go here", "Kjør hit"]]


def scenario_sendtablet(d):
    """Send a GPX track to the tablet (needs Curveon Companion installed and a track under GPX): how it went."""
    go_home(d)
    d.tap(["GPX & rides", "GPX og turer"])
    d.tap(["More", "Mer"], wait=1.5)
    d.tap(["Send to tablet", "Send til nettbrettet"], wait=3)
    return [["~^(OK|Open Companion|Åpne Companion)$"]]


def scenario_groups(d):
    """The group list (with demo phone data on, Settings -> Phone: the demo group). The rest is reached by scrolling."""
    go_home(d, demo=True)
    d.tap(["Friends", "Venner"])
    d.tap(["Group ride", "Gruppetur"])
    return [["~(?i)^(your groups|gruppene dine)$"], ["Demo ride"]]


def scenario_groupdetail(d):
    """One group opened from the list (demo phone data): the way back and the sharing switch, above the riders."""
    scenario_groups(d)
    d.tap(["Demo ride"])
    return [["Your groups", "Gruppene dine"], ["Ride with this group", "Kjør med gruppa"]]


def scenario_friends(d):
    """My friends (demo phone data: Ola shares his position, Anne does not). Look over the sheet for the rows and switches."""
    go_home(d, demo=True)
    d.tap(["Friends", "Venner"])
    d.tap(["My friends", "Mine venner"])
    return [["Show my code", "Vis koden min"], ["Scan a QR code", "Skann QR-kode"]]


def scenario_qrscan(d):
    """Scanning a friend's QR code: the camera fills the screen, and Cancel must be wholly on it (camera hole, nav bar)."""
    d.sh("pm", "grant", PKG, "android.permission.CAMERA")
    scenario_friends(d)
    d.tap(["Scan a QR code", "Skann QR-kode"], wait=4)
    return [["~^(Cancel|Avbryt)$"], ["Scan a QR code", "Skann QR-kode"]]


def scenario_nudge(d):
    """"Get attention" on a friend (demo phone data): the demo friend sees it and tries to reach the rider back, whose alert must fit."""
    scenario_friends(d)
    bell = ["Get attention", "Be om kontakt"]
    for _ in range(3):  # the friends are below the fold on a low screen (landscape, large text)
        if d.find(bell) is not None:
            break
        w, h = d.screen()
        d.sh("input", "swipe", str(w * 2 // 3), str(h * 3 // 4), str(w * 2 // 3), str(h // 4), "400")
        time.sleep(1.5)
    d.tap(bell, wait=9)
    return [["OK, I see it", "OK, jeg ser det"], ["~is trying to reach you$", "~prøver å få kontakt$"]]


def scenario_friendrequest(d):
    """"Add as friend" on a rider in the group (demo phone data): the button, and "Request sent to ..." under the name, must fit."""
    scenario_groupdetail(d)
    add = ["Add as friend", "Legg til som venn"]
    for _ in range(3):  # the riders are below the fold on a low screen (landscape, large text)
        if d.find(add) is not None:
            break
        w, h = d.screen()
        d.sh("input", "swipe", str(w * 2 // 3), str(h * 3 // 4), str(w * 2 // 3), str(h // 4), "400")
        time.sleep(1.5)
    d.tap(add, wait=2)
    return [["~^(Request sent|Forespørsel sendt)"], ["Get attention", "Be om kontakt"]]


def scenario_selfie(d):
    """Friends -> Me -> Take a picture (demo phone data off, This device): Curveon's own selfie camera, full screen.
    The picture is black on the emulator; Close, Switch camera and the shutter must be wholly on screen."""
    d.sh("pm", "grant", PKG, "android.permission.CAMERA")
    go_home(d, demo=False)
    d.tap(["Friends", "Venner"])
    d.tap(["Me", "Meg"])
    d.tap(["Take a picture", "Ta et bilde"], wait=4)
    return [["Cancel", "Avbryt"], ["Switch camera", "Bytt kamera"], ["Take the picture", "Ta bildet"]]


def start_demo_call(d):
    """Phone -> Recents -> Kari Nordmann (the top row) -> Call (demo phone data): the demo phone answers at once."""
    d.tap(["Phone", "Telefon"])
    d.tap(["Recents", "Nylige"])
    # The demo message from Kari is on the dashboard below the dialog too: tap the last one, the dialog's.
    kari = [n for n in d.nodes() if n["text"] == "Kari Nordmann"]
    if not kari:
        raise LookupError("not on screen: ['Kari Nordmann']")
    x1, y1, x2, y2 = kari[-1]["bounds"]
    d.sh("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(2)
    ring = [n for n in d.nodes() if n["text"] in ("Call", "Ring")]
    if not ring:
        raise LookupError("not on screen: ['Call', 'Ring']")
    x1, y1, x2, y2 = ring[-1]["bounds"]
    d.sh("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(4)


def scenario_callbig(d):
    """An answered call and no messages (demo phone data, both demo messages dismissed): the big call card fills the
    card column with Hang up, above the music row; on a low screen it stays one row."""
    go_home(d, demo=True)
    for _ in range(2):
        d.tap(["Dismiss", "Lukk"], wait=1.5)
    start_demo_call(d)
    return [["~^Hang up$"], ["~^(Play/pause|Spill av/pause)$"]]


def scenario_callmsg(d):
    """An answered call, music and a message at once (demo phone data): in portrait the call and the music share one
    row of icons above the message, which keeps its text, Reply and Dismiss."""
    go_home(d, demo=True)
    start_demo_call(d)
    return [["~^Hang up$"], ["Reply", "Svar"], ["Dismiss", "Lukk"], ["~^Er på Dombås"]]


def scenario_webplan(d):
    """Planned -> From the web: the dialog (connected or not) with its buttons wholly on screen."""
    go_home(d)
    d.tap(["GPX & rides", "GPX og turer"])
    d.tap(["Planned", "Planlagte"])
    d.tap(["From the web", "Fra nettet"])
    return [["Trips from the web", "Turer fra nettet"], ["Close", "Lukk"], ["~^(Fetch now|Hent nå|Scan the code|Skann koden)$"]]


def scenario_webplanscan(d):
    """Scanning the web planner's code: its own hint, and Cancel wholly on screen."""
    d.sh("pm", "grant", PKG, "android.permission.CAMERA")
    scenario_webplan(d)
    d.tap(["~^(Scan the code|Skann koden|Scan a code from another browser|Skann kode fra en annen nettleser)$"], wait=4)
    return [["~^(Cancel|Avbryt)$"], ["~(code on the planner page|koden på planleggersiden)"]]


TOUR_NEXT = ["=Next", "=Neste"]
TOUR_BUTTONS = [["Skip", "Hopp over"], ["Back", "Tilbake"], TOUR_NEXT]


def scenario_tour(d):
    """The welcome tour from the first start (tourSeen reset, Norsk picked, "Show me around"), stepped to its steps with
    a drawing: every one of them is checked on the way (title and Skip/Back/Next wholly on screen), the last is shown.
    The tour turns the phone sideways after the welcome, so portrait only differs up to there."""
    d.sh("am", "force-stop", PKG)
    forget_plan(d)
    d.sh(f"run-as {PKG} sed -i 's/name=\"tourSeen\" value=\"true\"/name=\"tourSeen\" value=\"false\"/' shared_prefs/settings.xml")
    d.sh("am", "start", "-n", f"{PKG}/com.curveon.MainActivity")
    time.sleep(6)
    if d.find(["~^(What's new|Nytt i Curveon)"]) is not None:
        d.tap(["Close", "Lukk"])
    for _ in range(4):  # the tour opens a moment after the dashboard
        if d.find(["Norsk"]) is not None or d.find(["Vis meg rundt", "Show me around"]) is not None:
            break
        time.sleep(2)
    if d.find(["Norsk"]) is not None:
        d.tap(["Norsk"], wait=5)  # the screen restarts in Norwegian
    d.tap(["Vis meg rundt", "Show me around"], wait=5)
    drawn = [["Planlegg en tur", "Plan a trip"], ["Bygg den i kartet", "Build it on the map"],
             ["Endre i kartet", "Edit on the map"], ["Endre bare en del", "Change just one part"],
             ["Lag en rundtur", "Make a roundtrip"], ["Eventyr", "Adventure"]]
    last = None
    for _ in range(24):
        nodes = d.nodes()
        step = next((t for t in drawn if d.find(["=" + x for x in t], nodes) is not None), None)
        if step is not None:
            must = [["=" + x for x in step]] + TOUR_BUTTONS
            d.extra += [f"step '{step[0]}': {p}" for p in check(d, must)]
            last = must
            if step is drawn[-1]:
                return must
        if d.find(TOUR_NEXT, nodes) is None:
            break
        d.tap(TOUR_NEXT, wait=2)
    if last is None:
        raise LookupError("no step with a drawing in the tour")
    return last


def scenario_triphelp(d):
    """The first trip planned (tripHelpSeen reset; hold the map near Drammen -> Go here, as the emulator has no search
    data): the walk over the trip card, all five steps checked on the way (title and Skip/Back/Next/OK wholly on
    screen), the last one shown. Look over the sheet for the card covering the lit part."""
    d.sh("am", "force-stop", PKG)
    forget_plan(d)
    set_seen(d, "tourSeen")
    d.sh(f"run-as {PKG} sed -i 's/name=\"tripHelpSeen\" value=\"true\"/name=\"tripHelpSeen\" value=\"false\"/' shared_prefs/settings.xml")
    subprocess.run(["adb", "-s", d.serial, "emu", "geo", "fix", "10.20", "59.74"], capture_output=True)
    d.sh("am", "start", "-n", f"{PKG}/com.curveon.MainActivity")
    time.sleep(6)
    w, h = d.screen()
    d.sh("input", "swipe", str(w // 2), str(h // 4), str(w // 2), str(h // 4), "1000")
    time.sleep(3)
    d.tap(["Go here", "Kjør hit"], wait=12)
    steps = [["Symbolene øverst", "The symbols up here"], ["Hvor turen starter", "Where it starts"],
             ["Endre ruten", "Change the route"], ["Eventyr", "Adventure"], ["Velg veien", "Choose the road"]]
    must = None
    for i, step in enumerate(steps):
        # Known by its counter ("4 AV 5"): the chip under the card is called Adventure too.
        counter = [f"~^{i + 1} (AV|OF) {len(steps)}$"]
        for _ in range(4):  # the walk opens a moment after the routes are in
            if d.find(counter) is not None:
                break
            time.sleep(2)
        last = i == len(steps) - 1
        title = [] if step[0] == "Eventyr" else [["=" + x for x in step]]
        must = [counter] + title + [["Skip", "Hopp over"] if not last else ["=OK"]] + \
            ([["Back", "Tilbake"]] if i > 0 else []) + ([TOUR_NEXT] if not last else [])
        if d.find(must[0]) is None:
            raise LookupError(f"not on screen: {step}")
        if not last:
            d.extra += [f"step '{step[0]}': {p}" for p in check(d, must)]
            d.tap(TOUR_NEXT, wait=2)
    return must


KM = ["~^[\\d\\s,.]+km$"]  # a ride's or trip's distance at the end of its row ("133 km"; not "158 rides · 8 km")


def reveal(d, labels):
    """Scrolls the list up (as the rider would) until [labels] is wholly above the bottom bar's region of the screen."""
    for _ in range(4):
        n = d.find(labels)
        w, h = d.screen()
        if n is not None and n["bounds"][3] < h * 4 // 5:
            return
        d.sh("input", "swipe", str(w * 2 // 3), str(h * 3 // 4), str(w * 2 // 3), str(h * 2 // 5), "500")
        time.sleep(1.5)


def scenario_ridebuttons(d):
    """GPX & rides -> Rides, one ride opened (needs a ride in the log): Save, Send to tablet, Share with the group and Delete in one row."""
    go_home(d)
    d.tap(["GPX & rides", "GPX og turer"])
    d.tap(["Rides", "Turer"])
    reveal(d, KM)
    d.tap(KM)
    reveal(d, ["Delete", "Slett"])
    return [["Save…", "Lagre…"], ["Send to tablet", "Send til nettbrettet"], ["Share with the group", "Del med gruppa"],
            ["Delete", "Slett"]]


def scenario_gpxmenu(d):
    """GPX & rides -> GPX, a track's menu (needs a track under GPX): Share with the group and Add to Planned."""
    go_home(d)
    d.tap(["GPX & rides", "GPX og turer"])
    d.tap(["GPX"])
    reveal(d, ["More", "Mer"])
    d.tap(["More", "Mer"], wait=1.5)
    return [["Share with the group", "Del med gruppa"], ["Add to Planned", "Legg i Planlagte"], ["Delete", "Slett"]]


def scenario_plannedmenu(d):
    """GPX & rides -> Planned, a trip opened and its menu (needs a planned trip): Send to tablet and Share with the group."""
    go_home(d)
    d.tap(["GPX & rides", "GPX og turer"])
    d.tap(["Planned", "Planlagte"])
    reveal(d, KM)
    d.tap(KM)
    reveal(d, ["More", "Mer"])
    d.tap(["More", "Mer"], wait=1.5)
    return [["Send to tablet", "Send til nettbrettet"], ["Share with the group", "Del med gruppa"]]


# The Where to? button, the dialog's Close and its microphone in Norwegian and in the languages with the longest
# placeholder in the search field; the scenario ends in Norwegian, as the emulator is set up.
WHERE_TO_LANGUAGES = [("da", "Hvor skal du hen?", "Luk", "Søg med stemmen"), ("is", "Hvert?", "Loka", "Leita með rödd"),
                      ("nb", "Hvor skal du?", "Lukk", "Søk med stemmen")]


def search_field(d):
    return next((n for n in d.nodes() if n["cls"].endswith("EditText")), None)


def field_height(d):
    n = search_field(d)
    if n is None:
        raise LookupError("not on screen: the search field")
    return n["bounds"][3] - n["bounds"][1]


def scenario_whereto(d):
    """Dashboard -> the motorcycle button (Where to?) with an empty field, in Norwegian and the longest languages: the
    placeholder on one line (look over the sheet for its ellipsis), the field as high as with text typed or the
    microphone listening, the microphone and Close wholly on screen. Upright, the shortcuts under the field (pick on
    map, nearest fuel, camping...) are a row made to scroll sideways, with the next one showing at the edge."""
    d.scrolling_row_ok = True
    d.sh("cmd", "locale", "set-app-locales", PKG, "--locales", "nb")
    go_home(d)
    d.tap(["Hvor skal du?", "Where to?"])
    empty = field_height(d)
    mic = ["Søk med stemmen", "Search by voice"]
    d.tap(mic, wait=1)
    listening = field_height(d)
    if d.find(["Snakk nå…", "Speak now…"]) is not None and listening != empty:
        d.extra.append(f"the field is {listening} px high with Speak now showing, {empty} px when empty")
    d.tap(mic, wait=1)
    field = search_field(d)
    if field is None:
        raise LookupError("not on screen: the search field")
    x1, y1, x2, y2 = field["bounds"]
    d.sh("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(2)
    d.sh("input", "text", "Oslo")
    time.sleep(2)
    if "mInputShown=true" in d.sh("dumpsys", "input_method"):  # sideways on a low screen the keyboard squeezes the dialog
        d.sh("input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.5)
    typed = field_height(d)
    if typed != empty:
        d.extra.append(f"the field is {typed} px high with text typed, {empty} px when empty")
    must = None
    for lang, where_to, close, voice in WHERE_TO_LANGUAGES:
        d.sh("cmd", "locale", "set-app-locales", PKG, "--locales", lang)
        go_home(d)
        d.tap([where_to])
        must = [[where_to], [close], [voice]]
        height = field_height(d)
        if height != typed:
            d.extra.append(f"{lang}: the empty field is {height} px high, {typed} px with text typed")
        if lang != "nb":
            d.extra += [f"{lang}: {p}" for p in check(d, must)]
    return must


REMOTE_ROWS = [["=Opp", "=Up"], ["=Ned", "=Down"], ["=Venstre", "=Left"], ["=Høyre", "=Right"], ["=OK"], ["=Tilbake", "=Back"],
               ["=Zoom inn", "=Zoom in"], ["=Zoom ut", "=Zoom out"], ["=Meny", "=Menu"], ["=Følg meg", "=Follow me"],
               ["=Hvor skal du?", "=Where to?"], ["=Si hvor du vil", "=Say where to go"], ["=Kartretning", "=Map direction"],
               ["=Kart eller dashbord", "=Map or dashboard"], ["=Neste sang", "=Next song"], ["=Forrige sang", "=Previous song"],
               ["=Spill av eller pause", "=Play or pause"], ["=Svar eller legg på", "=Answer or hang up"]]
SETTINGS_TABS = [["Skjerm", "Display"], ["Navigasjon", "Navigation"], ["Motor", "Engine"], ["Telefon", "Phone"],
                 ["Fjernkontroll", "Remote"], ["Kart og data", "Maps & data"], ["Hjelp", "Help"]]


def scenario_remote(d):
    """Settings -> Remote (no remote on the emulator, so the Bluetooth button shows): every row is scrolled to and must
    lie wholly on screen, as must the open tab and the Bluetooth button. Choosing a job opens its "press the button"
    question, which must fit too. Look over the sheet for hints cut off and for the tabs."""
    d.sh("cmd", "locale", "set-app-locales", PKG, "--locales", "nb")
    go_home(d)
    d.tap(["Settings", "Innstillinger"])
    w, h = d.screen()
    nodes = d.nodes()
    for tab in SETTINGS_TABS:
        if d.find(tab, nodes) is None:
            d.extra.append(f"the tab '{tab[0]}' is not on screen when Settings opens")
    d.tap(["Fjernkontroll", "Remote"], wait=3)
    tab_row = [["Fjernkontroll", "Remote"]]
    top = [["Ingen fjernkontroll tilkoblet", "No remote connected"], ["Bluetooth"], ["Lukk", "Close"]]
    d.extra += [f"top of the page: {p}" for p in check(d, tab_row + top)]
    unseen = list(REMOTE_ROWS)
    line = 15 * density(d)
    for _ in range(30):  # large text on a low screen: many swipes down the page
        nodes = d.nodes()
        for row in list(unseen):
            n = d.find(row, nodes)
            if n is not None and n["bounds"][3] - n["bounds"][1] >= line:  # a whole line of text, not cut by the page's edge
                x1, y1, x2, y2 = n["bounds"]
                if x1 < 0 or x2 > w:
                    d.extra.append(f"row '{row[0]}' sticks out of the screen: {n['bounds']} on {w}x{h}")
                unseen.remove(row)
        if not unseen:
            break
        d.sh("input", "swipe", str(w * 2 // 3), str(h * 3 // 4), str(w * 2 // 3), str(h * 2 // 5), "500")
        time.sleep(1)
    for row in unseen:
        d.extra.append(f"row '{row[0]}' could not be scrolled to")
    d.tap(["=Svar eller legg på", "=Answer or hang up"], wait=2)
    d.extra += [f"the question for a button: {p}" for p in check(d, [["Avbryt", "Cancel"]])]
    d.tap(["Avbryt", "Cancel"], wait=2)
    for _ in range(30):
        n = d.find(top[0])
        if n is not None and n["bounds"][3] - n["bounds"][1] >= line and d.find(["Bluetooth"]) is not None:
            break
        d.sh("input", "swipe", str(w * 2 // 3), str(h * 2 // 5), str(w * 2 // 3), str(h * 3 // 4), "300")
        time.sleep(0.7)
    # Once more: the title just in view can still have the page's top edge over its first line.
    d.sh("input", "swipe", str(w * 2 // 3), str(h * 2 // 5), str(w * 2 // 3), str(h * 3 // 4), "300")
    time.sleep(1.5)
    return tab_row + top


# Language, Settings, Display, the row's title: the long ones, German (with the longest hint of them) last, for the sheet.
SIDE_SIGN_LANGUAGES = [("fi", "Asetukset", "Näyttö", "Nopeusmerkit sivuteillä"),
                       ("is", "Stillingar", "Skjár", "Hraðamerki á hliðarvegum"),
                       ("de", "Einstellungen", "Anzeige", "Tempolimits auf Seitenstraßen")]
# Language, Ride, Save for later, Ignore, Save: the long ones (French has the longest "Save for later"), then Norwegian.
# The contact sheet shows the first of them with the keyboard down, and the last with it up.
OFFER_LANGUAGES = [("de", "Fahren", "Für später speichern", "Ignorieren", "Speichern"),
                   ("fi", "Aja", "Tallenna myöhemmäksi", "Ohita", "Tallenna"),
                   ("fr", "Rouler", "Garder pour plus tard", "Ignorer", "Enregistrer"),
                   ("nb", "Kjør", "Lagre til senere", "Ignorer", "Lagre")]
LANGUAGES = []  # with --languages: only these of the lists above (a whole run in every language takes hours)


def picked(table):
    return [row for row in table if not LANGUAGES or row[0] in LANGUAGES]


def scenario_sidesigns(d):
    """Settings -> Display, the row Speed signs on side roads (a switch and a hint that wraps) in the languages with the
    longest texts: the switch wholly on screen, not pushed out by the words. Look over the sheet for the hint cut off."""
    must = None
    languages = picked(SIDE_SIGN_LANGUAGES)
    for lang, settings, display, title in languages:
        d.sh("cmd", "locale", "set-app-locales", PKG, "--locales", lang)
        go_home(d)
        d.tap([settings])
        d.tap([display], wait=1.5)
        reveal(d, [title])
        nodes = d.nodes()
        row = d.find([title], nodes)
        if row is None:
            raise LookupError(f"{lang}: not on screen: {title}")
        must = [[title]]
        # The switch has no words of its own: the first clickable thing after the row's title.
        switch = next((n for n in nodes[nodes.index(row):] if n["clickable"] and not n["text"] and not n["desc"]), None)
        if switch is None:
            d.extra.append(f"{lang}: no switch after the row")
            continue
        x1, y1, x2, y2 = switch["bounds"]
        w, h = d.screen()
        if x1 < 0 or y1 < 0 or x2 > w - 4 or y2 > h - 4 or x2 - x1 < 30 * density(d):
            d.extra.append(f"{lang}: the switch is cut or squeezed: {switch['bounds']} on {w}x{h}")
        if lang != languages[-1][0]:
            d.extra += [f"{lang}: {p}" for p in check(d, must)]
    return must


# A share the scenarios put on the emulator, named as something the rider has (needs a GPX group and a track under GPX).
OFFER_FROM = "Kari Nordmann"
OFFER_GROUP = "Helgetur til Dovre"


def write_app_file(d, path, text):
    """Writes [text] to a file of the app's (a debug build's) as the app itself."""
    r = subprocess.run(["adb", "-s", d.serial, "shell", f"run-as {PKG} sh -c 'mkdir -p {os.path.dirname(path)}; cat > {path}'"],
                       input=text.encode(), capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(f"could not write {path}: {r.stderr.decode().strip()}")


def gpx_names(d):
    """The names of the GPX groups and tracks in the app's database (the app is stopped for it)."""
    d.sh("am", "force-stop", PKG)
    with tempfile.TemporaryDirectory() as tmp:
        for name in ("curveon_database", "curveon_database-wal", "curveon_database-shm"):
            with open(os.path.join(tmp, name), "wb") as f:
                subprocess.run(["adb", "-s", d.serial, "exec-out", "run-as", PKG, "cat", f"databases/{name}"], stdout=f)
        db = sqlite3.connect(os.path.join(tmp, "curveon_database"))
        try:
            return ([r[0] for r in db.execute("select name from gpx_groups")],
                    [r[0] for r in db.execute("select name from gpx_tracks")])
        finally:
            db.close()


def offer_trip(d, name):
    """A trip someone shared, not answered yet (SharedTrips), called [name]."""
    doc = {"v": 1, "name": name, "destination": {"name": name, "lat": 59.9, "lon": 5.5}, "stops": [], "options": {}}
    trip = {"id": "uimatrix-trip", "group": "uimatrix", "gname": OFFER_GROUP, "rev": 1, "from": "uimatrix", "nick": OFFER_FROM,
            "doc": json.dumps(doc), "accepted": False, "at": int(time.time() * 1000), "own": None}
    write_app_file(d, "files/shared_trips.json", json.dumps({"trips": [trip], "passed": {}, "ignored": []}))


def offer_gpx_group(d, name):
    """A GPX group someone shared, all of it here, not answered yet (GpxShare, SharedGpxStore), called [name]."""
    share = {"v": 1, "name": name, "group": True, "visibility": 0, "direction": 0, "sameColor": False, "color": 0, "tracks": [
        {"name": "S-01", "file": "s-01.gpx", "km": 6.5, "gain": 0, "maxEle": 0, "count": 2, "way": "", "fav": False, "cat": "",
         "show": True, "color": 0, "pois": True, "rev": False, "p": "59.900000,5.500000,0;59.910000,5.510000,0"}]}
    data = base64.urlsafe_b64encode(gzip.compress(json.dumps(share).encode())).decode().rstrip("=")
    doc = {"kind": "gpx", "id": "uimatrix", "i": 0, "n": 1, "name": name, "group": True, "tracks": 12, "km": 1234, "data": data}
    part = {"doc": json.dumps(doc), "nick": OFFER_FROM, "gname": OFFER_GROUP, "at": int(time.time() * 1000)}
    write_app_file(d, "files/shared_gpx/uimatrix/0.json", json.dumps(part))


def keyboard_top(d):
    """The y where the on-screen keyboard starts, or None when it is not up."""
    for m in re.finditer(r"type=ime frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\] visibleFrame=\S+ visible=true", d.sh("dumpsys", "window", "displays")):
        if int(m.group(4)) > int(m.group(2)):
            return int(m.group(2))
    return None


def reach_in_offer(d, labels, floor):
    """Scrolls the offer down in small steps (as the rider would) until the button with [labels] lies wholly inside the
    card, and says what is wrong if it can't. The card ends 16 dp above [floor]. A bound is cut off by the card's edge, so
    a text half out still looks whole: it is the button round it, 56 dp high when whole (PillButton), that tells."""
    dp = density(d)
    margin = int(16 * dp)
    w, _ = d.screen()
    step = int(50 * dp)
    start = max(floor - margin - 2, step + margin)
    n = None
    for _ in range(12):
        nodes = d.nodes()
        n = d.find(labels, nodes)
        if n is not None:
            x1, y1, x2, y2 = n["bounds"]
            pill = next((c for c in nodes if c["clickable"] and c["bounds"][0] <= x1 and c["bounds"][1] <= y1 and c["bounds"][2] >= x2 and c["bounds"][3] >= y2), None)
            if pill is not None and pill["bounds"][3] - pill["bounds"][1] >= 54 * dp and pill["bounds"][3] <= floor and pill["bounds"][1] >= 0 and x2 <= w:
                return None
        d.sh("input", "swipe", str(w // 2), str(start), str(w // 2), str(start - step), "700")
        time.sleep(0.7)
    if n is None:
        return f"'{labels[0]}' could not be scrolled to"
    return f"'{labels[0]}' can't be scrolled wholly into the card above y={floor}: {n['bounds']}"


def field_of_offer(d):
    return next((n for n in d.nodes() if n["cls"].endswith("EditText")), None)


def check_offer(d, seed, name_of, offers):
    """One offer with a name the rider already has, in the languages with the longest texts and at last in Norwegian: the
    name field and every button can be reached, with the keyboard down and up, and with the name typed back to the one
    that is taken (which adds a line under the field). It ends with the keyboard up and the last button in view."""
    d.sh("settings", "put", "secure", "show_ime_with_hard_keyboard", "1")  # the emulator's own keyboard would keep it down
    must = None
    offers = picked(offers)
    for lang, *buttons in offers:
        d.sh("cmd", "locale", "set-app-locales", PKG, "--locales", lang)
        d.sh("am", "force-stop", PKG)
        forget_offers(d)
        seed(d, name_of)
        go_home_keeping_offers(d)
        must = [[b] for b in buttons]
        field = field_of_offer(d)
        if field is None:
            raise LookupError(f"{lang}: no name field in the offer (is {name_of} still a name the rider has?)")
        _, h = d.screen()
        for labels in must:
            p = reach_in_offer(d, labels, h)
            if p:
                d.extra.append(f"{lang}, keyboard down: {p}")
        d.extra += [f"{lang}, keyboard down: {p}" for p in check(d, must)]
        if lang == offers[0][0]:
            d.shot(f"{lang}_keyboard_down")
        field = field_of_offer(d)
        if field is None:
            d.sh("input", "swipe", str(d.screen()[0] // 2), str(h // 3), str(d.screen()[0] // 2), str(h * 3 // 4), "400")
            time.sleep(1)
            field = field_of_offer(d)
        if field is None:
            d.extra.append(f"{lang}: the name field can't be scrolled back to")
            continue
        x1, y1, x2, y2 = field["bounds"]
        d.sh("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(3)
        floor = keyboard_top(d)
        if floor is None:
            d.extra.append(f"{lang}: the keyboard did not come up")
            continue
        field = field_of_offer(d)
        if field is None or field["bounds"][3] > floor - 16 * density(d) + 2 or field["bounds"][1] < 0:
            d.extra.append(f"{lang}: the name field is hidden while typing: {field and field['bounds']}, the keyboard starts at y={floor}")
        # Back to the name that is taken: "(2)" off the end. A line comes under the field and the buttons go grey.
        d.sh("input", "keyevent", "KEYCODE_MOVE_END")
        for _ in range(4):
            d.sh("input", "keyevent", "KEYCODE_DEL")
        time.sleep(1.5)
        for labels in must:
            p = reach_in_offer(d, labels, floor)
            if p:
                d.extra.append(f"{lang}, keyboard up: {p}")
    return must


def go_home_keeping_offers(d):
    """Curveon started afresh with the offer written before it; go_home would clear it away."""
    d.sh("settings", "put", "system", "user_rotation", str(d.rotation))
    set_seen(d, "tourSeen", "tripHelpSeen")
    d.sh(f"run-as {PKG} rm -f files/plan-draft.json")
    d.sh("am", "start", "-n", f"{PKG}/com.curveon.MainActivity")
    time.sleep(6)


def scenario_offertrip(d):
    """A shared trip whose name the rider already has among their GPX tracks: Save as, and Ride, Save for later, Ignore."""
    _, tracks = gpx_names(d)
    if not tracks:
        raise LookupError("no track under GPX to share the name of")
    return check_offer(d, offer_trip, tracks[0], [(lang, ride, later, ignore) for lang, ride, later, ignore, _ in OFFER_LANGUAGES])


def scenario_offergpx(d):
    """A shared GPX group whose name the rider already has among their groups: Save as, and Save, Ignore."""
    groups, _ = gpx_names(d)
    if not groups:
        raise LookupError("no group under GPX to share the name of")
    return check_offer(d, offer_gpx_group, groups[0], [(lang, save, ignore) for lang, _, _, ignore, save in OFFER_LANGUAGES])


SCENARIOS = {"dashboard": scenario_dashboard, "settings": scenario_settings, "crop": scenario_crop,
             "mappoint": scenario_mappoint, "sendtablet": scenario_sendtablet, "groups": scenario_groups,
             "groupdetail": scenario_groupdetail, "friends": scenario_friends, "nudge": scenario_nudge,
             "qrscan": scenario_qrscan, "friendrequest": scenario_friendrequest,
             "webplan": scenario_webplan, "webplanscan": scenario_webplanscan, "selfie": scenario_selfie,
             "callbig": scenario_callbig, "callmsg": scenario_callmsg, "tour": scenario_tour, "triphelp": scenario_triphelp,
             "ridebuttons": scenario_ridebuttons, "gpxmenu": scenario_gpxmenu, "plannedmenu": scenario_plannedmenu,
             "whereto": scenario_whereto, "remote": scenario_remote, "sidesigns": scenario_sidesigns,
             "offertrip": scenario_offertrip, "offergpx": scenario_offergpx}


def apply_variant(d, v, rotation):
    name, size, density, cutout, font, nav = v
    d.sh("settings", "put", "system", "accelerometer_rotation", "0")
    d.sh("wm", "size", size)
    d.sh("wm", "density", str(density))
    d.sh("settings", "put", "system", "font_scale", str(font))
    if cutout in CUTOUTS:
        d.sh("cmd", "overlay", "enable-exclusive", "--category", f"com.android.internal.display.cutout.emulation.{cutout}")
    else:
        for c in CUTOUTS:
            d.sh("cmd", "overlay", "disable", f"com.android.internal.display.cutout.emulation.{c}")
    d.sh("cmd", "overlay", "enable-exclusive", "--category", f"com.android.internal.systemui.navbar.{nav}")
    d.rotation = rotation
    d.sh("settings", "put", "system", "user_rotation", str(rotation))
    time.sleep(4)


def density(d):
    m = re.findall(r"density: (\d+)", d.sh("wm", "density"))
    return int(m[-1]) / 160 if m else 2.625


def check(d, must):
    problems = []
    dp = density(d)
    scr = d.screen()
    if scr is None:
        return ["could not read the screen size"]
    w, h = scr
    # With the keyboard up Android takes the window's frame in round it (a navigation bar's worth): nothing off screen.
    for rw, rh, (l, t, r, b) in d.dialog_windows() if keyboard_top(d) is None else []:
        if rh > (b - t) + 1 or rw > (r - l) + 1:
            problems.append(f"dialog window {rw}x{rh} is larger than its frame [{l},{t}][{r},{b}]: its edge is off screen")
    nodes = d.nodes()
    margin = 4
    for labels in must:
        n = d.find(labels, nodes)
        if n is None:
            problems.append(f"'{labels[0]}' is not on screen")
            continue
        x1, y1, x2, y2 = n["bounds"]
        if x1 < 0 or y1 < 0 or x2 > w or y2 > h:
            problems.append(f"'{labels[0]}' sticks out of the screen: {n['bounds']} on {w}x{h}")
        elif x2 - x1 < 20 or y2 - y1 < 20 or y2 >= h - margin or x2 >= w - margin or y1 <= margin:
            problems.append(f"'{labels[0]}' is cut or squeezed at the screen's edge: {n['bounds']} on {w}x{h}")
        elif n["clickable"] and y2 - y1 < 40 * dp:
            problems.append(f"button '{labels[0]}' is only {round((y2 - y1) / dp)} dp high: cut off or squeezed")
    for n in nodes:
        if n["pkg"] == PKG and n["clickable"]:
            x1, y1, x2, y2 = n["bounds"]
            name = n['text'] or n['desc'] or '?'
            if x2 > w + 1 or y2 > h + 1 or x1 < -1 or y1 < -1:
                problems.append(f"clickable '{name}' sticks out: {n['bounds']} on {w}x{h}")
            sc = n["scroller"]
            # In a row that scrolls sideways, a button cut by the row's edge looks like the end of the screen.
            if not d.scrolling_row_ok and sc and (sc[2] - sc[0]) > 3 * (sc[3] - sc[1]) and x2 > x1 and (abs(x2 - sc[2]) <= 2 or abs(x1 - sc[0]) <= 2):
                problems.append(f"button '{name}' is partly hidden at the edge of a sideways-scrolling row")
    return problems


def sheet(paths, out):
    from PIL import Image, ImageDraw
    thumbs = []
    for label, p in paths:
        im = Image.open(p).convert("RGB")
        im.thumbnail((360, 360))
        canvas = Image.new("RGB", (360, 380), (40, 40, 40))
        canvas.paste(im, ((360 - im.width) // 2, 20))
        ImageDraw.Draw(canvas).text((4, 2), label, fill=(255, 255, 0))
        thumbs.append(canvas)
    cols = 4
    rows = (len(thumbs) + cols - 1) // cols
    grid = Image.new("RGB", (cols * 360, rows * 380), (0, 0, 0))
    for i, t in enumerate(thumbs):
        grid.paste(t, ((i % cols) * 360, (i // cols) * 380))
    grid.save(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-s", "--serial", required=True)
    ap.add_argument("--variants", default=",".join(v[0] for v in VARIANTS))
    ap.add_argument("--scenarios", default=",".join(SCENARIOS))
    ap.add_argument("--rotations", default="0,1", help="0 = portrait, 1 = landscape")
    ap.add_argument("--out", default="ui-matrix-out")
    ap.add_argument("--json", help="also write the results here")
    ap.add_argument("--languages", default="", help="only these languages in the scenarios that go through several, e.g. de,nb")
    a = ap.parse_args()
    LANGUAGES.extend(x for x in a.languages.split(",") if x)
    os.makedirs(a.out, exist_ok=True)
    d = Device(a.serial)
    d.out = a.out
    # Near the country whose data is on the emulator: left where it stands by default (California), Curveon fetches that
    # state's search data by itself, as it should, and the emulator soon has no room to install a new build.
    subprocess.run(["adb", "-s", d.serial, "emu", "geo", "fix", "10.20", "59.74"], capture_output=True)
    wanted = [v for v in VARIANTS if v[0] in a.variants.split(",")]
    shots = {s: [] for s in a.scenarios.split(",")}
    failures, results = [], []
    try:
        for v in wanted:
            for rot in [int(r) for r in a.rotations.split(",")]:
                apply_variant(d, v, rot)
                for s in shots:
                    tag = f"{v[0]}-{'land' if rot else 'port'}"
                    d.scenario = s
                    d.tag = tag
                    d.shots = []
                    try:
                        d.extra = []
                        d.scrolling_row_ok = False
                        must = SCENARIOS[s](d)
                        problems = d.extra + check(d, must)
                    except LookupError as e:
                        problems = [f"could not get there: {e}"]
                    path = os.path.join(a.out, f"{s}_{tag}.png")
                    d.screenshot(path)
                    shots[s] += d.shots
                    shots[s].append((f"{tag}{'  !' if problems else ''}", path))
                    results.append({"scenario": s, "tag": tag, "problems": problems, "shot": os.path.abspath(path)})
                    for p in problems:
                        failures.append(f"{s} / {tag}: {p}")
                    print(f"{s:10} {tag:18} {'OK' if not problems else 'PROBLEM'}", flush=True)
                    for p in problems:
                        print(f"           {p}", flush=True)
    finally:
        d.sh("am", "force-stop", PKG)
        forget_offers(d)
        d.sh("cmd", "locale", "set-app-locales", PKG, "--locales", "nb")
        d.sh("settings", "put", "secure", "show_ime_with_hard_keyboard", "0")
        d.sh("wm", "size", "reset")
        d.sh("wm", "density", "reset")
        d.sh("settings", "put", "system", "font_scale", "1.0")
        d.sh("settings", "put", "system", "user_rotation", "0")
        for c in CUTOUTS:
            d.sh("cmd", "overlay", "disable", f"com.android.internal.display.cutout.emulation.{c}")
        d.sh("cmd", "overlay", "enable-exclusive", "--category", "com.android.internal.systemui.navbar.gestural")
    for s, paths in shots.items():
        if paths:
            sheet(paths, os.path.join(a.out, f"sheet_{s}.png"))
    if a.json:
        sheets = {s: os.path.abspath(os.path.join(a.out, f"sheet_{s}.png")) for s, p in shots.items() if p}
        with open(a.json, "w") as f:
            json.dump({"results": results, "sheets": sheets}, f, indent=1)
    print(f"\n{len(failures)} problem(s). Screenshots and contact sheets in {a.out}/")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
