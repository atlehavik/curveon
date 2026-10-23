#!/usr/bin/env python3
"""
Screen matrix: runs Curveon's screens on one emulator dressed up as the common kinds of Android screens
(sizes, densities, camera holes and notches, navigation bars, large text), in portrait and landscape, and
checks that nothing the rider must reach ends up off screen.

    python3 -I tools/ui_matrix.py -s emulator-5556 [--variants pixel9a,small360] [--scenarios crop] [--out DIR]

Use the Curveon_Phone AVD (API 36) with a debug build installed, "This device" picked as the phone
(Settings -> Phone) and at least one picture in the gallery (for the profile picture). Per variant and
orientation it writes a screenshot, and per scenario a contact sheet (`sheet_<scenario>.png`) to look over;
it prints every problem it finds and exits 1 if there were any. The emulator is put back as it was at the end.

Checks, on every screen:
- a dialog window is never larger than the area Android gives it (the Pixel bug of 2026-10-22: a dialog
  placed below the camera hole at full height, so its bottom buttons were below the screen);
- every button and text the scenario names is found, lies wholly on screen and doesn't touch its edge;
- nothing clickable sticks out past the screen.
"""
import argparse
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
        """The first node whose text or description is one of [labels] (or matches a regex starting with ~)."""
        for n in nodes if nodes is not None else self.nodes():
            for label in labels:
                hay = (n["text"], n["desc"])
                if (label.startswith("~") and any(re.search(label[1:], h) for h in hay)) or label in hay:
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
def go_home(d):
    d.sh("am", "force-stop", PKG)
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
    go_home(d)
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
            ["Cut your picture", "Beskjær bildet"]]


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
    go_home(d)
    d.tap(["Friends", "Venner"])
    d.tap(["Group ride", "Gruppetur"])
    return [["~(?i)^(your groups|gruppene dine)$"], ["Demo ride"]]


def scenario_groupdetail(d):
    """One group opened from the list (demo phone data): the way back and the sharing switch, above the riders."""
    scenario_groups(d)
    d.tap(["Demo ride"])
    return [["Your groups", "Gruppene dine"], ["Share my position", "Del posisjonen min"]]


def scenario_friends(d):
    """My friends (demo phone data: Ola shares his position, Anne does not). Look over the sheet for the rows and switches."""
    go_home(d)
    d.tap(["Friends", "Venner"])
    d.tap(["My friends", "Mine venner"])
    return [["Show my code", "Vis koden min"], ["Scan a QR code", "Skann QR-kode"]]


def scenario_nudge(d):
    """"Get attention" on a friend (demo phone data): the demo friend sees it and tries to reach the rider back, whose alert must fit."""
    scenario_friends(d)
    bell = ["Get attention", "Vil ha kontakt"]
    for _ in range(3):  # the friends are below the fold on a low screen (landscape, large text)
        if d.find(bell) is not None:
            break
        w, h = d.screen()
        d.sh("input", "swipe", str(w * 2 // 3), str(h * 3 // 4), str(w * 2 // 3), str(h // 4), "400")
        time.sleep(1.5)
    d.tap(bell, wait=9)
    return [["OK, I see it", "OK, jeg ser det"], ["~is trying to reach you$", "~prøver å få kontakt$"]]


SCENARIOS = {"dashboard": scenario_dashboard, "settings": scenario_settings, "crop": scenario_crop,
             "mappoint": scenario_mappoint, "sendtablet": scenario_sendtablet, "groups": scenario_groups,
             "groupdetail": scenario_groupdetail, "friends": scenario_friends, "nudge": scenario_nudge}


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
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    d = Device(a.serial)
    wanted = [v for v in VARIANTS if v[0] in a.variants.split(",")]
    shots = {s: [] for s in a.scenarios.split(",")}
    failures = []
    try:
        for v in wanted:
            for rot in [int(r) for r in a.rotations.split(",")]:
                apply_variant(d, v, rot)
                for s in shots:
                    tag = f"{v[0]}-{'land' if rot else 'port'}"
                    try:
                        must = SCENARIOS[s](d)
                        problems = check(d, must)
                    except LookupError as e:
                        problems = [f"could not get there: {e}"]
                    path = os.path.join(a.out, f"{s}_{tag}.png")
                    d.screenshot(path)
                    shots[s].append((f"{tag}{'  !' if problems else ''}", path))
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
    print(f"\n{len(failures)} problem(s). Screenshots and contact sheets in {a.out}/")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
