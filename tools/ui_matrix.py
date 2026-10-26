#!/usr/bin/env python3
"""
Screen matrix: runs Curveon's screens on one emulator dressed up as the common kinds of Android screens
(sizes, densities, camera holes and notches, navigation bars, large text), in portrait and landscape, and
checks that nothing the rider must reach ends up off screen.

    python3 -I tools/ui_matrix.py -s emulator-5556 [--variants pixel9a,small360] [--scenarios crop] [--out DIR] [--json FILE]

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
import json
import os
import re
import subprocess
import sys
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
        self.extra = []  # problems a scenario found on the screens it passed on the way (a tour's steps)

    def sh(self, *args, check=False):
        r = subprocess.run(["adb", "-s", self.serial, "shell", *args], capture_output=True, text=True)
        if check and r.returncode != 0:
            raise RuntimeError(f"adb shell {' '.join(args)}: {r.stderr.strip()}")
        return r.stdout

    def screenshot(self, path):
        with open(path, "wb") as f:
            subprocess.run(["adb", "-s", self.serial, "exec-out", "screencap", "-p"], stdout=f, check=True)

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
    """No trip left planned from a scenario before (the trip card would come back on the next start)."""
    d.sh(f"run-as {PKG} rm -f files/plan-draft.json")


def go_home(d, demo=None):
    """Curveon started afresh on the dashboard; demo=True/False first turns demo phone data on or off (debug build)."""
    d.sh("am", "force-stop", PKG)
    forget_plan(d)
    # The tour scenarios leave the welcome tour or the trip card's walk-through unseen: not over the other screens.
    d.sh(f"run-as {PKG} sed -i -e 's/name=\"tourSeen\" value=\"false\"/name=\"tourSeen\" value=\"true\"/' "
         f"-e 's/name=\"tripHelpSeen\" value=\"false\"/name=\"tripHelpSeen\" value=\"true\"/' shared_prefs/settings.xml")
    if demo is not None:
        old, new = ("false", "true") if demo else ("true", "false")
        d.sh(f"run-as {PKG} sed -i 's/name=\"demo\" value=\"{old}\"/name=\"demo\" value=\"{new}\"/' shared_prefs/phone_link.xml")
    d.sh("am", "start", "-n", f"{PKG}/com.curveon.MainActivity")
    time.sleep(6)


def scenario_dashboard(d):
    go_home(d)
    return [["Settings", "Innstillinger"], ["Friends", "Venner"]]


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
    d.sh(f"run-as {PKG} sed -i -e 's/name=\"tripHelpSeen\" value=\"true\"/name=\"tripHelpSeen\" value=\"false\"/' "
         f"-e 's/name=\"tourSeen\" value=\"false\"/name=\"tourSeen\" value=\"true\"/' shared_prefs/settings.xml")
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


SCENARIOS = {"dashboard": scenario_dashboard, "settings": scenario_settings, "crop": scenario_crop,
             "mappoint": scenario_mappoint, "sendtablet": scenario_sendtablet, "groups": scenario_groups,
             "groupdetail": scenario_groupdetail, "friends": scenario_friends, "nudge": scenario_nudge,
             "qrscan": scenario_qrscan, "friendrequest": scenario_friendrequest,
             "webplan": scenario_webplan, "webplanscan": scenario_webplanscan, "selfie": scenario_selfie,
             "callbig": scenario_callbig, "callmsg": scenario_callmsg, "tour": scenario_tour, "triphelp": scenario_triphelp,
             "ridebuttons": scenario_ridebuttons, "gpxmenu": scenario_gpxmenu, "plannedmenu": scenario_plannedmenu}


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
    for rw, rh, (l, t, r, b) in d.dialog_windows():
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
            if sc and (sc[2] - sc[0]) > 3 * (sc[3] - sc[1]) and x2 > x1 and (abs(x2 - sc[2]) <= 2 or abs(x1 - sc[0]) <= 2):
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
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    d = Device(a.serial)
    wanted = [v for v in VARIANTS if v[0] in a.variants.split(",")]
    shots = {s: [] for s in a.scenarios.split(",")}
    failures, results = [], []
    try:
        for v in wanted:
            for rot in [int(r) for r in a.rotations.split(",")]:
                apply_variant(d, v, rot)
                for s in shots:
                    tag = f"{v[0]}-{'land' if rot else 'port'}"
                    try:
                        d.extra = []
                        must = SCENARIOS[s](d)
                        problems = d.extra + check(d, must)
                    except LookupError as e:
                        problems = [f"could not get there: {e}"]
                    path = os.path.join(a.out, f"{s}_{tag}.png")
                    d.screenshot(path)
                    shots[s].append((f"{tag}{'  !' if problems else ''}", path))
                    results.append({"scenario": s, "tag": tag, "problems": problems, "shot": os.path.abspath(path)})
                    for p in problems:
                        failures.append(f"{s} / {tag}: {p}")
                    print(f"{s:10} {tag:18} {'OK' if not problems else 'PROBLEM'}", flush=True)
                    for p in problems:
                        print(f"           {p}", flush=True)
    finally:
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
